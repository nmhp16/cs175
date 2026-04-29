package edu.sjsu.android.cs175.llm;

import android.content.Context;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads the on-device LLM model on first launch. Features:
 *   - Resume-on-failure via HTTP Range requests (keeps a .part file)
 *   - Automatic retry on transient network errors
 *   - No read timeout (big downloads over the emulator's virtual network
 *     pause for seconds at a time, which a 60s read-timeout kills)
 */
public class ModelDownloader {

    private static final String TAG = "ModelDownloader";

    public static final String DEFAULT_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/"
                    + "gemma-4-E2B-it.litertlm?download=true";

    public static final String TARGET_FILENAME = ModelConfig.GEMMA_4_E2B_FILENAME;

    /** How many times we retry after a transient failure before giving up. */
    private static final int MAX_RETRIES = 5;

    /** Backoff between retries, in milliseconds. */
    private static final long RETRY_DELAY_MS = 3_000;

    public enum State { IDLE, CONNECTING, DOWNLOADING, COMPLETE, FAILED, ALREADY_PRESENT }

    public static final class Progress {
        public final State state;
        public final long bytesDownloaded;
        public final long totalBytes;
        @Nullable public final String errorMessage;

        public Progress(State state, long bytesDownloaded, long totalBytes,
                        @Nullable String errorMessage) {
            this.state = state;
            this.bytesDownloaded = bytesDownloaded;
            this.totalBytes = totalBytes;
            this.errorMessage = errorMessage;
        }

        public int percent() {
            if (totalBytes <= 0) return -1;
            return (int) Math.min(100, (bytesDownloaded * 100L) / totalBytes);
        }

        public static Progress idle() { return new Progress(State.IDLE, 0, 0, null); }
        public static Progress alreadyPresent() {
            return new Progress(State.ALREADY_PRESENT, 0, 0, null);
        }
    }

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<Progress> progress = new MutableLiveData<>(Progress.idle());
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile String url = DEFAULT_URL;

    public ModelDownloader(Context appContext) {
        this.appContext = appContext.getApplicationContext();
    }

    public LiveData<Progress> progressLiveData() { return progress; }

    public void setUrl(String url) {
        if (url != null && !url.isEmpty()) this.url = url;
    }

    public String url() { return url; }

    @MainThread
    public void ensureModelDownloaded() {
        if (isModelPresent()) {
            progress.setValue(Progress.alreadyPresent());
            return;
        }
        if (!running.compareAndSet(false, true)) return;
        progress.setValue(new Progress(State.CONNECTING, 0, 0, null));
        executor.execute(this::downloadWithRetries);
    }

    public boolean isModelPresent() {
        File target = new File(ModelConfig.modelsDir(appContext), TARGET_FILENAME);
        return target.exists() && target.length() > 0;
    }

    public File targetFile() {
        return new File(ModelConfig.modelsDir(appContext), TARGET_FILENAME);
    }

    // ------------------------------------------------------------------

    private void downloadWithRetries() {
        File targetFile = targetFile();
        File partFile = new File(targetFile.getParentFile(), targetFile.getName() + ".part");

        String lastError = null;
        try {
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    downloadAttempt(partFile, targetFile);
                    // Success if we reach here.
                    return;
                } catch (Throwable t) {
                    lastError = t.getMessage() == null
                            ? t.getClass().getSimpleName() : t.getMessage();
                    Log.w(TAG, "Download attempt " + attempt + "/" + MAX_RETRIES
                            + " failed: " + lastError, t);

                    if (attempt < MAX_RETRIES) {
                        long soFar = partFile.length();
                        progress.postValue(new Progress(
                                State.CONNECTING, soFar, -1,
                                "Retry " + attempt + "/" + MAX_RETRIES + ": " + lastError));
                        try { Thread.sleep(RETRY_DELAY_MS); }
                        catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            // All retries exhausted.
            progress.postValue(new Progress(
                    State.FAILED,
                    partFile.exists() ? partFile.length() : 0,
                    -1,
                    "Download failed after " + MAX_RETRIES + " attempts. "
                            + "Last error: " + lastError));
        } finally {
            running.set(false);
        }
    }

    /**
     * One attempt. Resumes from the existing .part file via HTTP Range if any
     * bytes have already been downloaded.
     */
    private void downloadAttempt(File partFile, File targetFile) throws IOException {
        long existing = partFile.exists() ? partFile.length() : 0;

        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;

        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(30_000);
            // Disable read timeout — the emulator's virtual network
            // can stall for tens of seconds on large downloads.
            conn.setReadTimeout(0);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "DocuMind/1.0 (Android)");
            if (existing > 0) {
                conn.setRequestProperty("Range", "bytes=" + existing + "-");
            }

            int code = conn.getResponseCode();

            // 206 = partial content (resume worked). 200 = full content (server
            // ignored our Range — restart from zero).
            boolean appending;
            if (existing > 0 && code == HttpURLConnection.HTTP_PARTIAL) {
                appending = true;
            } else if (code == HttpURLConnection.HTTP_OK) {
                appending = false;
                existing = 0;
                if (partFile.exists()) partFile.delete();
            } else {
                throw new IOException("HTTP " + code + " from " + url);
            }

            long contentLength = conn.getContentLengthLong();
            long total = appending
                    ? (contentLength > 0 ? existing + contentLength : -1)
                    : contentLength;

            progress.postValue(new Progress(State.DOWNLOADING, existing, total, null));

            in = conn.getInputStream();
            out = new FileOutputStream(partFile, appending);

            byte[] buf = new byte[64 * 1024];
            long downloaded = existing;
            long lastPublished = existing;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                downloaded += n;
                if (downloaded - lastPublished > 512 * 1024) {
                    progress.postValue(new Progress(State.DOWNLOADING, downloaded, total, null));
                    lastPublished = downloaded;
                }
            }
            out.flush();
            out.close();
            out = null;

            if (targetFile.exists()) targetFile.delete();
            if (!partFile.renameTo(targetFile)) {
                throw new IOException("Could not rename .part file into place");
            }

            progress.postValue(new Progress(
                    State.COMPLETE, downloaded,
                    total > 0 ? total : downloaded, null));
        } finally {
            closeQuiet(in);
            closeQuiet(out);
            if (conn != null) conn.disconnect();
        }
    }

    private static void closeQuiet(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) { }
    }
}
