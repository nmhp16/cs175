package edu.sjsu.android.cs175.llm;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import java.util.List;

/**
 * Abstracts a local LLM backend. All calls are offline.
 * <p>
 * Two user-visible modes:
 *   - Multimodal: image + question → answer (Gemma 3n)
 *   - Text-only + OCR fallback: OCR the image first, then answer from text.
 */
public interface LlmService {

    enum Backend { GEMMA_3N_MULTIMODAL, TEXT_WITH_OCR, UNAVAILABLE }

    enum StatusKind { NOT_STARTED, LOADING, READY, ERROR }

    /** Immutable status snapshot suitable for LiveData observers. */
    final class Status {
        public final StatusKind kind;
        @Nullable public final String errorMessage;

        private Status(StatusKind kind, @Nullable String errorMessage) {
            this.kind = kind;
            this.errorMessage = errorMessage;
        }

        public static Status notStarted() { return new Status(StatusKind.NOT_STARTED, null); }
        public static Status loading()    { return new Status(StatusKind.LOADING, null); }
        public static Status ready()      { return new Status(StatusKind.READY, null); }
        public static Status error(String msg) { return new Status(StatusKind.ERROR, msg); }

        public boolean isReady() { return kind == StatusKind.READY; }
    }

    final class ModelInfo {
        public final String name;
        public final Backend backend;
        @Nullable public final Long sizeMb;
        public ModelInfo(String name, Backend backend, @Nullable Long sizeMb) {
            this.name = name;
            this.backend = backend;
            this.sizeMb = sizeMb;
        }
    }

    final class HistoryTurn {
        public final String role;
        public final String content;
        public HistoryTurn(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /** Streaming callback invoked as tokens arrive. */
    interface Listener {
        /** Called repeatedly with the accumulated text so far. */
        void onPartial(String accumulatedText);
        /** Called exactly once when the generation finishes cleanly. */
        void onComplete(String finalText);
        /** Called if something goes wrong. */
        void onError(Throwable t);
    }

    /** Called when a kicked-off request should be cancelled. */
    interface Cancellable {
        void cancel();
    }

    /** Structured result from one-shot document analysis. */
    final class AnalysisResult {
        public final String category;
        public final String title;
        public final String summary;

        public AnalysisResult(String category, String title, String summary) {
            this.category = category;
            this.title = title;
            this.summary = summary;
        }

        public static AnalysisResult fallback() {
            return new AnalysisResult("Other", "Untitled document", "");
        }
    }

    /** Listener for auto-analysis of a new document. */
    interface AnalysisListener {
        void onResult(AnalysisResult result);
        void onError(Throwable t);
    }

    LiveData<Status> statusLiveData();

    Status currentStatus();

    ModelInfo modelInfo();

    /** Eager load. Safe to call multiple times. */
    void initialize();

    boolean isReady();

    /** Stream a 2-sentence summary. Image must be a file path on internal storage. */
    Cancellable generateSummary(String imagePath, Listener listener);

    /** Stream an answer to a question about a document. */
    Cancellable askQuestion(String imagePath,
                            String question,
                            List<HistoryTurn> history,
                            Listener listener);

    /**
     * One-shot: OCR the document, ask the model to classify it, and return a
     * suggested category + title. Runs asynchronously; the listener is invoked
     * exactly once (either onResult or onError).
     */
    Cancellable analyzeDocument(String imagePath, AnalysisListener listener);

    void shutdown();
}
