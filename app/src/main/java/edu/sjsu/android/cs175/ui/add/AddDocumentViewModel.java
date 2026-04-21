package edu.sjsu.android.cs175.ui.add;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import edu.sjsu.android.cs175.data.Category;
import edu.sjsu.android.cs175.data.repository.DocumentRepository;
import edu.sjsu.android.cs175.llm.LlmService;
import edu.sjsu.android.cs175.util.ImageStorage;

public class AddDocumentViewModel extends ViewModel {

    public static final class UiState {
        @Nullable public final Uri pickedUri;
        @Nullable public final String stagedImagePath;
        @Nullable public final String stagedThumbPath;
        public final String title;
        public final Category category;
        public final String detectedSummary;
        public final boolean isSaving;
        public final boolean isAnalyzing;
        @Nullable public final String errorMessage;

        public UiState(@Nullable Uri pickedUri,
                       @Nullable String stagedImagePath,
                       @Nullable String stagedThumbPath,
                       String title, Category category, String detectedSummary,
                       boolean isSaving, boolean isAnalyzing,
                       @Nullable String errorMessage) {
            this.pickedUri = pickedUri;
            this.stagedImagePath = stagedImagePath;
            this.stagedThumbPath = stagedThumbPath;
            this.title = title;
            this.category = category;
            this.detectedSummary = detectedSummary;
            this.isSaving = isSaving;
            this.isAnalyzing = isAnalyzing;
            this.errorMessage = errorMessage;
        }

        public UiState withUri(Uri uri) {
            return new UiState(uri, null, null, title, category, "",
                    isSaving, isAnalyzing, null);
        }
        public UiState withStaged(String imagePath, String thumbPath) {
            return new UiState(pickedUri, imagePath, thumbPath, title, category, detectedSummary,
                    isSaving, isAnalyzing, errorMessage);
        }
        public UiState withTitle(String t) {
            return new UiState(pickedUri, stagedImagePath, stagedThumbPath,
                    t, category, detectedSummary, isSaving, isAnalyzing, errorMessage);
        }
        public UiState withCategory(Category c) {
            return new UiState(pickedUri, stagedImagePath, stagedThumbPath,
                    title, c, detectedSummary, isSaving, isAnalyzing, errorMessage);
        }
        public UiState withSummary(String s) {
            return new UiState(pickedUri, stagedImagePath, stagedThumbPath,
                    title, category, s == null ? "" : s,
                    isSaving, isAnalyzing, errorMessage);
        }
        public UiState saving(boolean s) {
            return new UiState(pickedUri, stagedImagePath, stagedThumbPath,
                    title, category, detectedSummary, s, isAnalyzing, errorMessage);
        }
        public UiState analyzing(boolean a) {
            return new UiState(pickedUri, stagedImagePath, stagedThumbPath,
                    title, category, detectedSummary, isSaving, a, errorMessage);
        }
        public UiState error(@Nullable String e) {
            return new UiState(pickedUri, stagedImagePath, stagedThumbPath,
                    title, category, detectedSummary, false, isAnalyzing, e);
        }

        public static UiState initial() {
            return new UiState(null, null, null, "", Category.OTHER, "",
                    false, false, null);
        }
    }

    public interface SaveCallback {
        void onCreated(long id);
    }

    private static final String TAG = "AddDocumentVM";

    private final Application application;
    private final DocumentRepository repository;
    private final LlmService llmService;

    private final MutableLiveData<UiState> uiState = new MutableLiveData<>(UiState.initial());

    public AddDocumentViewModel(Application application,
                                DocumentRepository repository,
                                LlmService llmService) {
        this.application = application;
        this.repository = repository;
        this.llmService = llmService;
    }

    public LiveData<UiState> getUiState() { return uiState; }

    public UiState current() {
        UiState s = uiState.getValue();
        return s == null ? UiState.initial() : s;
    }

    public void onImagePicked(@Nullable Uri uri) {
        if (uri == null) return;

        // If the user re-picks, delete the previously staged files so they
        // don't leak.
        deleteStagedFiles(current());

        UiState next = current().withUri(uri);
        uiState.setValue(next);

        // Seed title from filename while Gemma works on a better one.
        if (next.title == null || next.title.isEmpty()) {
            String suggested = ImageStorage.suggestTitleFromUri(application, uri);
            if (suggested != null && !suggested.isEmpty()) {
                uiState.setValue(current().withTitle(suggested));
            }
        }

        // Copy the picked file into app-private storage (documents/) once.
        // The same staged path is used for both auto-analysis and final save,
        // so nothing is copied twice.
        uiState.setValue(current().analyzing(true));
        repository.getIoExecutor().execute(() -> {
            try {
                ImageStorage.Saved saved = ImageStorage.copyFromUri(application, uri);
                uiState.postValue(current()
                        .withStaged(saved.imagePath, saved.thumbnailPath));
                launchAutoAnalysis(saved.imagePath);
            } catch (Exception e) {
                Log.e(TAG, "Staging failed", e);
                uiState.postValue(current()
                        .analyzing(false)
                        .error("Could not import document: " +
                                (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
            }
        });
    }

    /** Deletes the staged image + thumbnail + any PDF page files. */
    private void deleteStagedFiles(UiState state) {
        if (state.stagedImagePath != null) {
            for (String page : ImageStorage.allPagesFor(state.stagedImagePath)) {
                new java.io.File(page).delete();
            }
        }
        if (state.stagedThumbPath != null) {
            new java.io.File(state.stagedThumbPath).delete();
        }
    }

    public void save(SaveCallback callback) {
        UiState s = current();
        if (s.pickedUri == null) return;
        uiState.setValue(s.saving(true).error(null));

        repository.getIoExecutor().execute(() -> {
            try {
                // Reuse staged files if available; only fall back to a fresh
                // copy if staging somehow didn't happen.
                String imagePath = s.stagedImagePath;
                String thumbPath = s.stagedThumbPath;
                if (imagePath == null || thumbPath == null) {
                    ImageStorage.Saved saved =
                            ImageStorage.copyFromUri(application, s.pickedUri);
                    imagePath = saved.imagePath;
                    thumbPath = saved.thumbnailPath;
                }

                String titleSafe = s.title == null || s.title.trim().isEmpty()
                        ? "Untitled" : s.title.trim();
                long id = repository.createDocument(
                        titleSafe, s.category, imagePath, thumbPath);

                // The analysis pass already produced a summary — save it now
                // instead of making a second LLM call after the fact.
                if (s.detectedSummary != null && !s.detectedSummary.isEmpty()) {
                    repository.setSummary(id, s.detectedSummary);
                }

                uiState.postValue(UiState.initial());
                postToMain(() -> callback.onCreated(id));
            } catch (Exception e) {
                Log.e(TAG, "Save failed", e);
                uiState.postValue(s.error(e.getMessage() == null
                        ? "Could not save document"
                        : e.getMessage()));
            }
        });
    }

    /**
     * OCR the already-staged document and ask Gemma to classify it. Updates
     * the title + category fields once the model responds — but only if the
     * user hasn't manually edited them in the meantime.
     */
    private void launchAutoAnalysis(String stagedImagePath) {
        new Thread(() -> {
            // Wait for the LLM if it's still warming up (first launch).
            if (!llmService.isReady()) llmService.initialize();
            long deadline = System.currentTimeMillis() + 60_000;
            while (!llmService.isReady() && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(500); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    uiState.postValue(current().analyzing(false));
                    return;
                }
            }
            if (!llmService.isReady()) {
                uiState.postValue(current().analyzing(false));
                return;
            }

            llmService.analyzeDocument(stagedImagePath,
                    new LlmService.AnalysisListener() {
                        @Override
                        public void onResult(LlmService.AnalysisResult result) {
                            applyAnalysisResult(result);
                        }
                        @Override
                        public void onError(Throwable t) {
                            Log.w(TAG, "Auto-analysis failed", t);
                            uiState.postValue(current().analyzing(false));
                        }
                    });
        }, "auto-analysis").start();
    }

    private void applyAnalysisResult(LlmService.AnalysisResult result) {
        UiState next = current().analyzing(false);
        if (result.title != null && !result.title.trim().isEmpty()) {
            next = next.withTitle(result.title.trim());
        }
        next = next.withCategory(Category.fromName(result.category));
        if (result.summary != null) {
            next = next.withSummary(result.summary);
        }
        uiState.postValue(next);
    }

    private void postToMain(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        // If the VM dies while the user has a staged-but-unsaved document,
        // clean up the files. Staged paths are cleared in save() once they're
        // attached to a DB row, so this only runs on cancel/navigate-away.
        UiState cur = current();
        repository.getIoExecutor().execute(() -> deleteStagedFiles(cur));
    }
}
