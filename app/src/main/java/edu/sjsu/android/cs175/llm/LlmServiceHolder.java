package edu.sjsu.android.cs175.llm;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.List;

/**
 * Keeps the actual MediaPipe-backed service construction behind a single
 * entry point that can't crash the app on startup. If MediaPipe's native
 * libs or its Java classes aren't available on the device/classpath, we
 * return a {@link #disabledStub} that reports "AI unavailable" but never
 * throws.
 */
public final class LlmServiceHolder {

    private static final String TAG = "LlmServiceHolder";

    private LlmServiceHolder() {}

    /**
     * Attempt to build the real MediaPipe-backed service. Any failure (class
     * not found, linkage error, etc.) is caught and converted to a stub.
     */
    public static LlmService create(Context appContext) {
        try {
            return new GemmaLlmService(appContext);
        } catch (Throwable t) {
            Log.e(TAG, "Could not construct GemmaLlmService", t);
            return disabledStub("On-device AI failed to load: " + safeMessage(t));
        }
    }

    /**
     * A no-op LlmService used when MediaPipe isn't available at all. Always
     * reports {@link LlmService.StatusKind#ERROR} so the chat UI shows the
     * banner instead of spinning forever.
     */
    public static LlmService disabledStub(String errorMessage) {
        final MutableLiveData<LlmService.Status> status = new MutableLiveData<>(
                LlmService.Status.error(errorMessage));
        final LlmService.ModelInfo info = new LlmService.ModelInfo(
                "AI unavailable", LlmService.Backend.UNAVAILABLE, null);

        return new LlmService() {
            @Override public LiveData<Status> statusLiveData() { return status; }
            @Override public Status currentStatus() { return status.getValue(); }
            @Override public ModelInfo modelInfo() { return info; }
            @Override public void initialize() { /* no-op */ }
            @Override public boolean isReady() { return false; }

            @Override
            public Cancellable generateSummary(String imagePath, Listener listener) {
                listener.onError(new IllegalStateException(errorMessage));
                return () -> { };
            }

            @Override
            public Cancellable askQuestion(String imagePath, String question,
                                           List<HistoryTurn> history, Listener listener) {
                listener.onError(new IllegalStateException(errorMessage));
                return () -> { };
            }

            @Override
            public Cancellable analyzeDocument(String imagePath, AnalysisListener listener) {
                listener.onResult(AnalysisResult.fallback());
                return () -> { };
            }

            @Override public void shutdown() { /* no-op */ }
        };
    }

    @NonNull
    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}
