package edu.sjsu.android.cs175;

import android.app.Application;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;

import edu.sjsu.android.cs175.data.repository.DocumentRepository;
import edu.sjsu.android.cs175.llm.LlmService;
import edu.sjsu.android.cs175.llm.LlmServiceHolder;
import edu.sjsu.android.cs175.llm.ModelDownloader;

/**
 * Service locator, one-time model download, and LLM warm-up.
 * <p>
 * Startup sequence:
 *   1. Build repository and LLM service (never throws — falls back to stub).
 *   2. Kick off model download if the .task file isn't already present.
 *      INTERNET is used here and only here; once the file is on disk, the
 *      app never makes another network call.
 *   3. Initialize the LLM once the model is on disk.
 */
public class DocuMindApp extends Application {

    private static final String TAG = "DocuMindApp";

    private DocumentRepository repository;
    private LlmService llmService;
    private ModelDownloader modelDownloader;

    @Override
    public void onCreate() {
        super.onCreate();

        repository = DocumentRepository.create(this);

        try {
            llmService = LlmServiceHolder.create(this);
        } catch (Throwable t) {
            Log.e(TAG, "LLM service construction failed — using stub", t);
            llmService = LlmServiceHolder.disabledStub(
                    t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }

        modelDownloader = new ModelDownloader(this);

        if (modelDownloader.isModelPresent()) {
            safeInitLlm();
        } else {
            modelDownloader.progressLiveData().observeForever(new Observer<ModelDownloader.Progress>() {
                @Override
                public void onChanged(ModelDownloader.Progress p) {
                    if (p == null) return;
                    if (p.state == ModelDownloader.State.COMPLETE
                            || p.state == ModelDownloader.State.ALREADY_PRESENT) {
                        safeInitLlm();
                        modelDownloader.progressLiveData().removeObserver(this);
                    }
                }
            });
            modelDownloader.ensureModelDownloaded();
        }
    }

    private void safeInitLlm() {
        try {
            llmService.initialize();
        } catch (Throwable t) {
            Log.e(TAG, "LLM init failed", t);
        }
    }

    public DocumentRepository getRepository() { return repository; }

    public LlmService getLlmService() { return llmService; }

    public ModelDownloader getModelDownloader() { return modelDownloader; }

    @Nullable
    public static DocuMindApp from(android.content.Context ctx) {
        return (DocuMindApp) ctx.getApplicationContext();
    }
}
