package edu.sjsu.android.cs175.llm;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Resolves the on-device Gemma 4 E2B model file. Checked locations, in order:
 *   1. App-internal storage (filesDir/models/) — where the downloader drops it
 *   2. /data/local/tmp/llm/ — for developer adb-push workflow
 */
public final class ModelConfig {

    /** Gemma 4 E2B multimodal — ~2.58 GB, runs on LiteRT-LM 0.10.2. Apache 2.0 (public download). */
    public static final String GEMMA_4_E2B_FILENAME = "gemma-4-E2B-it.litertlm";

    private static final String ADB_STAGING_DIR = "/data/local/tmp/llm";
    private static final String INTERNAL_MODELS_SUBDIR = "models";

    private ModelConfig() {}

    public static final class Resolved {
        public final String path;
        public final LlmService.Backend backend;
        public final String displayName;

        public Resolved(String path, LlmService.Backend backend, String displayName) {
            this.path = path;
            this.backend = backend;
            this.displayName = displayName;
        }
    }

    @Nullable
    public static Resolved resolve(Context context) {
        String path = locate(context, GEMMA_4_E2B_FILENAME);
        if (path == null) return null;
        return new Resolved(
                path,
                LlmService.Backend.GEMMA_3N_MULTIMODAL, // enum name kept; means "multimodal"
                "Gemma 4 E2B (multimodal)");
    }

    public static File modelsDir(Context context) {
        File dir = new File(context.getFilesDir(), INTERNAL_MODELS_SUBDIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    @Nullable
    private static String locate(Context context, String filename) {
        File internal = new File(modelsDir(context), filename);
        if (internal.exists() && internal.length() > 0) return internal.getAbsolutePath();

        File staged = new File(ADB_STAGING_DIR, filename);
        if (staged.exists() && staged.canRead()) {
            try {
                copyFile(staged, internal);
                return internal.getAbsolutePath();
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private static void copyFile(File from, File to) throws IOException {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }
}
