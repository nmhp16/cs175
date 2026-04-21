package edu.sjsu.android.cs175.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Copies user-picked images into app-private internal storage. Photos never
 * leave this directory and no external permissions are used.
 * <p>
 * Full images:  filesDir/documents/&lt;uuid&gt;.jpg (scaled down to ~1600 px)
 * Thumbnails:   filesDir/thumbnails/&lt;uuid&gt;.jpg (scaled to 320 px)
 */
public final class ImageStorage {

    private static final String DOCS_DIR = "documents";
    private static final String THUMBS_DIR = "thumbnails";
    // Bumped from 1600 to 2400 to give ML Kit OCR more pixels to work with
    // on photographed documents. Screenshots are already ~4x denser per
    // inch than paper photos, so they don't need the extra resolution, but
    // 2400 doesn't hurt them either.
    private static final int MAX_LONG_EDGE = 2400;
    private static final int THUMB_LONG_EDGE = 320;

    private ImageStorage() {}

    public static final class Saved {
        public final String imagePath;
        public final String thumbnailPath;
        public Saved(String imagePath, String thumbnailPath) {
            this.imagePath = imagePath;
            this.thumbnailPath = thumbnailPath;
        }
    }

    public static File documentsDir(Context context) {
        File dir = new File(context.getFilesDir(), DOCS_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File thumbsDir(Context context) {
        File dir = new File(context.getFilesDir(), THUMBS_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Max PDF pages to import. More pages → more OCR time + bigger prompts. */
    private static final int MAX_PDF_PAGES = 10;
    /** Width in pixels at which to rasterise PDF pages for OCR. */
    private static final int PDF_RENDER_WIDTH = 2000;

    @WorkerThread
    public static Saved copyFromUri(Context context, Uri uri) throws IOException {
        String mime = context.getContentResolver().getType(uri);
        if (mime != null && mime.equalsIgnoreCase("application/pdf")) {
            return copyPdfFromUri(context, uri);
        }
        return copyImageFromUri(context, uri);
    }

    @WorkerThread
    private static Saved copyImageFromUri(Context context, Uri uri) throws IOException {
        String id = UUID.randomUUID().toString();
        Bitmap full = decodeUriSampled(context, uri, MAX_LONG_EDGE);
        if (full == null) throw new IOException("Unable to decode picked image");

        File fullFile = new File(documentsDir(context), id + ".jpg");
        writeJpeg(full, fullFile, 95);

        Bitmap thumb = scaleLongEdge(full, THUMB_LONG_EDGE);
        File thumbFile = new File(thumbsDir(context), id + ".jpg");
        writeJpeg(thumb, thumbFile, 80);

        if (thumb != full) thumb.recycle();
        full.recycle();

        return new Saved(fullFile.getAbsolutePath(), thumbFile.getAbsolutePath());
    }

    /**
     * Renders each page of a PDF to a JPEG on internal storage. Page 1 lives
     * at {@code documents/<uuid>.jpg}; subsequent pages at
     * {@code documents/<uuid>_p<N>.jpg}. The OCR pipeline picks them all up
     * via {@link #allPagesFor(String)}.
     */
    @WorkerThread
    private static Saved copyPdfFromUri(Context context, Uri uri) throws IOException {
        String id = UUID.randomUUID().toString();
        ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) throw new IOException("Could not open PDF");

        File firstPageFile = null;
        File thumbFile = null;

        try (PdfRenderer renderer = new PdfRenderer(pfd)) {
            int total = Math.min(renderer.getPageCount(), MAX_PDF_PAGES);
            if (total <= 0) throw new IOException("PDF contains no pages");

            for (int i = 0; i < total; i++) {
                Bitmap pageBitmap = renderPdfPage(renderer, i);
                try {
                    File target = (i == 0)
                            ? new File(documentsDir(context), id + ".jpg")
                            : new File(documentsDir(context), id + "_p" + (i + 1) + ".jpg");
                    writeJpeg(pageBitmap, target, 92);

                    if (i == 0) {
                        firstPageFile = target;
                        // Build a thumbnail from page 1.
                        Bitmap thumb = scaleLongEdge(pageBitmap, THUMB_LONG_EDGE);
                        thumbFile = new File(thumbsDir(context), id + ".jpg");
                        writeJpeg(thumb, thumbFile, 80);
                        if (thumb != pageBitmap) thumb.recycle();
                    }
                } finally {
                    if (!pageBitmap.isRecycled()) pageBitmap.recycle();
                }
            }
        } finally {
            pfd.close();
        }

        if (firstPageFile == null || thumbFile == null) {
            throw new IOException("Failed to render any PDF pages");
        }
        return new Saved(firstPageFile.getAbsolutePath(), thumbFile.getAbsolutePath());
    }

    private static Bitmap renderPdfPage(PdfRenderer renderer, int pageIndex) {
        try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
            int srcWidth = page.getWidth();
            int srcHeight = page.getHeight();
            float scale = (float) PDF_RENDER_WIDTH / (float) srcWidth;
            int width = PDF_RENDER_WIDTH;
            int height = Math.max(1, (int) (srcHeight * scale));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        }
    }

    /**
     * Returns all page files for a given {@code imagePath}. For plain images
     * this is just that one file. For PDFs it's page 1 plus any
     * {@code <uuid>_p<N>.jpg} pages that sit next to it.
     */
    public static List<String> allPagesFor(String imagePath) {
        List<String> out = new ArrayList<>();
        out.add(imagePath);
        File first = new File(imagePath);
        File parent = first.getParentFile();
        String name = first.getName();
        if (parent == null || !name.toLowerCase().endsWith(".jpg")) return out;
        String base = name.substring(0, name.length() - 4); // strip .jpg
        for (int i = 2; ; i++) {
            File page = new File(parent, base + "_p" + i + ".jpg");
            if (!page.exists()) break;
            out.add(page.getAbsolutePath());
        }
        return out;
    }

    /**
     * Loads an image from a file path, downsampled to roughly {@code maxLongEdge}
     * on its longest side. Respects EXIF orientation.
     */
    @WorkerThread
    @Nullable
    public static Bitmap loadScaled(String path, int maxLongEdge) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxLongEdge);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap raw = BitmapFactory.decodeFile(path, opts);
        if (raw == null) return null;
        Bitmap rotated = applyExifRotation(path, raw);
        return scaleLongEdge(rotated, maxLongEdge);
    }

    public static void wipeAll(Context context) {
        deleteChildren(documentsDir(context));
        deleteChildren(thumbsDir(context));
    }

    // ---- internals ----

    private static void deleteChildren(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            try { f.delete(); } catch (Exception ignored) { }
        }
    }

    @Nullable
    private static Bitmap decodeUriSampled(Context context, Uri uri, int maxLongEdge) {
        ContentResolver resolver = context.getContentResolver();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        } catch (IOException e) {
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxLongEdge);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap raw;
        try (InputStream in = resolver.openInputStream(uri)) {
            raw = BitmapFactory.decodeStream(in, null, opts);
        } catch (IOException e) {
            return null;
        }
        if (raw == null) return null;

        Bitmap rotated = raw;
        try (InputStream exifStream = resolver.openInputStream(uri)) {
            if (exifStream != null) {
                ExifInterface exif = new ExifInterface(exifStream);
                int orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
                rotated = rotate(raw, orientationDegrees(orientation));
            }
        } catch (IOException ignored) {
            // some providers don't allow EXIF reads; ignore
        }

        return scaleLongEdge(rotated, maxLongEdge);
    }

    private static Bitmap applyExifRotation(String path, Bitmap bitmap) {
        try {
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            return rotate(bitmap, orientationDegrees(orientation));
        } catch (IOException e) {
            return bitmap;
        }
    }

    private static int orientationDegrees(int orientation) {
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:  return 90;
            case ExifInterface.ORIENTATION_ROTATE_180: return 180;
            case ExifInterface.ORIENTATION_ROTATE_270: return 270;
            default: return 0;
        }
    }

    private static Bitmap rotate(Bitmap bitmap, int degrees) {
        if (degrees == 0) return bitmap;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
        if (out != bitmap) bitmap.recycle();
        return out;
    }

    private static Bitmap scaleLongEdge(Bitmap bitmap, int longEdge) {
        int current = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (current <= longEdge) return bitmap;
        float ratio = (float) longEdge / (float) current;
        int w = Math.max(1, (int) (bitmap.getWidth() * ratio));
        int h = Math.max(1, (int) (bitmap.getHeight() * ratio));
        return Bitmap.createScaledBitmap(bitmap, w, h, true);
    }

    private static int sampleSizeFor(int width, int height, int maxLongEdge) {
        int longEdge = Math.max(width, height);
        int sample = 1;
        while (longEdge / sample > maxLongEdge * 2) sample *= 2;
        return Math.min(sample, 8);
    }

    private static void writeJpeg(Bitmap bitmap, File file, int quality) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        }
    }

    @Nullable
    public static String suggestTitleFromUri(Context context, Uri uri) {
        String name = null;
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && c.moveToFirst()) {
                    name = c.getString(idx);
                }
            }
        } catch (Exception ignored) { }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        base = base.replace('_', ' ').replace('-', ' ').trim();
        return base.isEmpty() ? null : base;
    }
}
