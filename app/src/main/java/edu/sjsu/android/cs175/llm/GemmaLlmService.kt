package edu.sjsu.android.cs175.llm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import edu.sjsu.android.cs175.util.ImageStorage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device LLM service backed by Gemma 4 E2B via LiteRT-LM.
 *
 * Written in Kotlin because LiteRT-LM is a Kotlin-first API. This is the
 * only Kotlin file in the otherwise-Java project; it implements the Java
 * [LlmService] interface so call-sites stay Java.
 *
 * ### Why OCR + text-only
 * The published `gemma-4-E2B-it.litertlm` (April 2026) is *text-only*. The
 * vision encoder isn't bundled — the README says vision/audio companion
 * models will be "loaded as needed" but they aren't available yet. So we
 * run ML Kit Text Recognition on the document image first, then hand the
 * extracted text + the user's question to Gemma 4 as pure text. When Google
 * publishes the vision encoder, switch to `Content.ImageFile(...)`.
 */
class GemmaLlmService(appContext: Context) : LlmService {

    private val appContext = appContext.applicationContext
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val statusLive = MutableLiveData<LlmService.Status>(LlmService.Status.notStarted())
    private val engineLock = Any()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile private var engine: Engine? = null
    @Volatile private var resolved: ModelConfig.Resolved? = null
    @Volatile private var modelInfoCached: LlmService.ModelInfo =
        LlmService.ModelInfo("Preparing…", LlmService.Backend.UNAVAILABLE, null)

    override fun statusLiveData(): LiveData<LlmService.Status> = statusLive

    override fun currentStatus(): LlmService.Status =
        statusLive.value ?: LlmService.Status.notStarted()

    override fun modelInfo(): LlmService.ModelInfo = modelInfoCached

    override fun initialize() {
        if (engine != null) return
        if (currentStatus().kind == LlmService.StatusKind.LOADING) return
        statusLive.postValue(LlmService.Status.loading())
        ioExecutor.execute { loadOnBackground() }
    }

    override fun isReady(): Boolean = currentStatus().kind == LlmService.StatusKind.READY

    override fun generateSummary(
        imagePath: String,
        listener: LlmService.Listener
    ): LlmService.Cancellable = runRequest(imagePath, Prompts.SUMMARY, null, listener)

    override fun askQuestion(
        imagePath: String,
        question: String,
        history: List<LlmService.HistoryTurn>,
        listener: LlmService.Listener
    ): LlmService.Cancellable = runRequest(imagePath, question, history, listener)

    override fun analyzeDocument(
        imagePath: String,
        listener: LlmService.AnalysisListener
    ): LlmService.Cancellable {
        val cancelled = AtomicBoolean(false)
        ioExecutor.execute {
            if (cancelled.get()) return@execute

            if (!isReady()) {
                loadOnBackground()
                if (!isReady()) {
                    listener.onResult(LlmService.AnalysisResult.fallback())
                    return@execute
                }
            }
            val eng = engine ?: run {
                listener.onResult(LlmService.AnalysisResult.fallback())
                return@execute
            }

            try {
                val extracted = ocr(imagePath)
                if (cancelled.get()) return@execute
                Log.d(TAG, "analyzeDocument OCR=${extracted.length} chars")

                if (extracted.trim().length < 20) {
                    listener.onResult(LlmService.AnalysisResult.fallback())
                    return@execute
                }

                val head = extracted.take(10_000).trim()
                val reply = runAnalysisQuery(eng, head, cancelled)
                if (cancelled.get()) return@execute

                val result = parseAnalysis(reply)
                Log.d(TAG, "analyzeDocument -> category=${result.category} " +
                        "title=\"${result.title}\" summary=${result.summary.length} chars")
                listener.onResult(result)
            } catch (t: Throwable) {
                Log.e(TAG, "analyzeDocument failed", t)
                listener.onError(t)
            }
        }
        return LlmService.Cancellable { cancelled.set(true) }
    }

    /**
     * Single LLM call that asks for all three artifacts at once using a
     * strict, structured output format. Merges what used to be two passes
     * (classify + summarize) into one for roughly 2× speedup.
     */
    private fun runAnalysisQuery(
        eng: Engine,
        docHead: String,
        cancelled: AtomicBoolean
    ): String {
        synchronized(engineLock) {
            val systemInstr = Contents.of(
                "You analyze documents and produce structured output in exactly " +
                "this format, with no other text before or after:\n\n" +
                "CATEGORY: <one of: Lease, Medical, Warranty, Insurance, Tax, Bill, Contract, Other>\n" +
                "TITLE: <a short 2-6 word descriptive title>\n" +
                "SUMMARY: <a 1-2 sentence factual summary noting document type, " +
                "key parties or vendors, any dates, and any dollar amounts that appear>"
            )
            val convoCfg = ConversationConfig(
                systemInstr,
                emptyList(),
                emptyList(),
                SamplerConfig(40, 0.9, 0.3, 0),
                true,
                null,
                emptyMap()
            )
            eng.createConversation(convoCfg).use { conversation: Conversation ->
                val userMessage = docHead + "\n\nAnalyze this document."
                val response: Message = conversation.sendMessage(Contents.of(userMessage))
                return extractText(response)
            }
        }
    }

    /**
     * Parses Gemma's structured reply. Tolerant of surrounding whitespace,
     * code-fence wrappers, label variations, and missing fields.
     */
    private fun parseAnalysis(raw: String): LlmService.AnalysisResult {
        val clean = raw.trim()
            .removePrefix("```").removeSuffix("```")
            .trim()

        var category: String? = null
        var title: String? = null
        val summary = StringBuilder()
        var summaryMode = false

        for (line in clean.lines()) {
            val trimmed = line.trim()
            val lower = trimmed.lowercase()
            when {
                lower.startsWith("category:") -> {
                    category = trimmed.substringAfter(':').trim()
                    summaryMode = false
                }
                lower.startsWith("title:") -> {
                    title = trimmed.substringAfter(':').trim()
                        .trim('"', '*', '#', ' ')
                    summaryMode = false
                }
                lower.startsWith("summary:") -> {
                    summary.clear()
                    summary.append(trimmed.substringAfter(':').trim())
                    summaryMode = true
                }
                summaryMode && trimmed.isNotEmpty() -> {
                    // Continuation line of the summary (wrapped text).
                    summary.append(' ').append(trimmed)
                }
            }
        }

        // AnalysisResult is a Java class — positional args only.
        return LlmService.AnalysisResult(
            normalizeCategory(category),
            (title ?: "Untitled document").ifBlank { "Untitled document" }.take(60),
            summary.toString().trim().take(500)
        )
    }

    private fun normalizeCategory(raw: String?): String {
        if (raw.isNullOrBlank()) return "Other"
        val cleaned = raw.trim('.', ',', '"', '*', '#', ' ')
        val allowed = listOf(
            "Lease", "Medical", "Warranty", "Insurance",
            "Tax", "Bill", "Contract", "Other"
        )
        return allowed.firstOrNull { it.equals(cleaned, ignoreCase = true) } ?: "Other"
    }

    override fun shutdown() {
        runCatching { engine?.close() }
        engine = null
        runCatching { recognizer.close() }
        statusLive.postValue(LlmService.Status.notStarted())
    }

    // ------------------------------------------------------------------

    private fun loadOnBackground() {
        val picked = ModelConfig.resolve(appContext)
        if (picked == null) {
            val err = "No model file found. Download will auto-start on launch, " +
                    "or push manually to /data/local/tmp/llm/."
            Log.w(TAG, err)
            statusLive.postValue(LlmService.Status.error(err))
            modelInfoCached =
                LlmService.ModelInfo("No model installed", LlmService.Backend.UNAVAILABLE, null)
            return
        }
        resolved = picked

        try {
            // No vision/audio backend — model is text-only in this version.
            // maxNumTokens is the TOTAL context (input + output).
            // EngineConfig positional order from Config.kt:
            //   modelPath, backend, visionBackend, audioBackend,
            //   maxNumTokens, maxNumImages, cacheDir
            // (named args don't compile against the published .aar's stripped
            //  Kotlin metadata, so everything is passed positionally.)
            val cfg = EngineConfig(
                picked.path,
                Backend.CPU(),
                null,
                null,
                4096,
                null,
                appContext.cacheDir.absolutePath
            )
            val e = Engine(cfg)
            e.initialize()
            engine = e

            val sizeMb = File(picked.path).length() / (1024L * 1024L)
            modelInfoCached =
                LlmService.ModelInfo(picked.displayName, LlmService.Backend.TEXT_WITH_OCR, sizeMb)
            statusLive.postValue(LlmService.Status.ready())
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load LiteRT-LM engine", t)
            val msg = t.message ?: t.javaClass.simpleName
            statusLive.postValue(LlmService.Status.error("Failed to initialize engine: $msg"))
        }
    }

    private fun runRequest(
        imagePath: String,
        question: String,
        history: List<LlmService.HistoryTurn>?,
        listener: LlmService.Listener
    ): LlmService.Cancellable {
        val cancelled = AtomicBoolean(false)

        ioExecutor.execute {
            if (cancelled.get()) return@execute

            if (!isReady()) {
                loadOnBackground()
                if (!isReady()) {
                    listener.onError(IllegalStateException("Model not ready"))
                    return@execute
                }
            }

            val eng = engine ?: run {
                listener.onError(IllegalStateException("Engine not loaded"))
                return@execute
            }

            try {
                // 1. OCR the document image.
                val extracted = ocr(imagePath)
                if (cancelled.get()) return@execute
                Log.d(TAG, "OCR extracted ${extracted.length} chars. Preview: " +
                        extracted.take(120).replace('\n', ' '))

                // If OCR got nothing useful, don't bother the model — tell the
                // user directly so they know the image is the weak link.
                if (extracted.trim().length < 20) {
                    val msg = "I couldn't read any text from the document image. " +
                            "Try a sharper photo, a higher-resolution screenshot, " +
                            "or a printed/typed document (ML Kit's Latin text " +
                            "recognizer can't handle handwriting or very low " +
                            "contrast)."
                    listener.onPartial(msg)
                    listener.onComplete(msg)
                    return@execute
                }

                // 2. Send extracted text + question to Gemma 4.
                runOnce(eng, extracted, question, history, listener, cancelled)
            } catch (t: Throwable) {
                Log.e(TAG, "Generation failed", t)
                listener.onError(t)
            }
        }

        return LlmService.Cancellable { cancelled.set(true) }
    }

    /**
     * OCRs every page of the document. For a plain image this is one file;
     * for a PDF it's the rendered pages [ImageStorage] wrote during import.
     */
    private fun ocr(imagePath: String): String {
        val pages = ImageStorage.allPagesFor(imagePath)
        val sb = StringBuilder()
        for ((idx, path) in pages.withIndex()) {
            val pageText = ocrOnePage(path)
            if (pages.size > 1) sb.append("--- Page ${idx + 1} ---\n")
            sb.append(pageText.trim()).append("\n\n")
        }
        return sb.toString().trim()
    }

    private fun ocrOnePage(imagePath: String): String {
        val bitmap: Bitmap = ImageStorage.loadScaled(imagePath, 2400)
            ?: throw IllegalStateException("Could not load image: $imagePath")
        try {
            val input = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(recognizer.process(input))
            return result?.text ?: ""
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun runOnce(
        eng: Engine,
        extractedText: String,
        question: String,
        history: List<LlmService.HistoryTurn>?,
        listener: LlmService.Listener,
        cancelled: AtomicBoolean
    ) {
        synchronized(engineLock) {
            // ConversationConfig positional order:
            //   systemInstruction, initialMessages, tools, samplerConfig,
            //   automaticToolCalling, channels, extraContext
            // SamplerConfig positional order: topK, topP, temperature, seed.
            val convoCfg = ConversationConfig(
                Contents.of(Prompts.SYSTEM),
                emptyList(),
                emptyList(),
                SamplerConfig(40, 0.95, 0.7, 0),
                true,
                null,
                emptyMap()
            )
            eng.createConversation(convoCfg).use { conversation: Conversation ->
                val prompt = buildPrompt(extractedText, question, history)
                val message = Contents.of(prompt)

                val response: Message = conversation.sendMessage(message)
                if (cancelled.get()) return

                val text = extractText(response)
                listener.onPartial(text)
                listener.onComplete(text.trim())
            }
        }
    }

    private fun extractText(msg: Message?): String {
        if (msg == null) return ""
        val sb = StringBuilder()
        for (c in msg.contents.contents) {
            if (c is Content.Text) sb.append(c.text)
        }
        return sb.toString()
    }

    /**
     * Minimal prompt — document text followed by the question. Cap the
     * document to ~12k chars (~3k tokens) so we never exceed the engine's
     * maxNumTokens budget with the OCR text alone.
     */
    private fun buildPrompt(
        extractedText: String,
        question: String,
        history: List<LlmService.HistoryTurn>?
    ): String {
        val MAX_DOC_CHARS = 12_000
        val doc = extractedText.trim().let {
            if (it.length > MAX_DOC_CHARS)
                it.substring(0, MAX_DOC_CHARS) + "\n…(document truncated)…"
            else it
        }

        val sb = StringBuilder()
        sb.append(doc).append("\n\n")

        if (history != null && history.isNotEmpty()) {
            val start = maxOf(0, history.size - Prompts.HISTORY_TURNS)
            for (i in start until history.size) {
                val t = history[i]
                val label = if (t.role == "user") "Q" else "A"
                sb.append(label).append(": ").append(t.content.trim()).append('\n')
            }
            sb.append('\n')
        }

        sb.append(question.trim())
        return sb.toString()
    }

    companion object {
        private const val TAG = "GemmaLlmService"
    }
}
