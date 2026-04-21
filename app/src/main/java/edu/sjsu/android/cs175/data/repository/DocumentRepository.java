package edu.sjsu.android.cs175.data.repository;

import android.content.Context;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;

import edu.sjsu.android.cs175.data.Category;
import edu.sjsu.android.cs175.data.db.AppDatabase;
import edu.sjsu.android.cs175.data.db.ChatMessageDao;
import edu.sjsu.android.cs175.data.db.ChatMessageEntity;
import edu.sjsu.android.cs175.data.db.DocumentDao;
import edu.sjsu.android.cs175.data.db.DocumentEntity;
import edu.sjsu.android.cs175.util.ImageStorage;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single access point for Room data. All write/read methods that block
 * should be called on the provided executor.
 */
public class DocumentRepository {

    private final Context appContext;
    private final DocumentDao documentDao;
    private final ChatMessageDao chatMessageDao;
    private final ExecutorService ioExecutor;

    public DocumentRepository(Context appContext,
                              DocumentDao documentDao,
                              ChatMessageDao chatMessageDao) {
        this.appContext = appContext.getApplicationContext();
        this.documentDao = documentDao;
        this.chatMessageDao = chatMessageDao;
        this.ioExecutor = Executors.newSingleThreadExecutor();
    }

    public static DocumentRepository create(Context context) {
        AppDatabase db = AppDatabase.get(context);
        return new DocumentRepository(context, db.documentDao(), db.chatMessageDao());
    }

    public ExecutorService getIoExecutor() {
        return ioExecutor;
    }

    // ---- observers (LiveData, main thread) ----

    @MainThread
    public LiveData<List<DocumentEntity>> observeDocuments() {
        return documentDao.observeAll();
    }

    @MainThread
    public LiveData<List<DocumentEntity>> searchDocuments(String query, @Nullable Category category) {
        String q = query == null ? "" : query.trim();
        String c = category == null ? null : category.name();
        return documentDao.search(q, c);
    }

    @MainThread
    public LiveData<DocumentEntity> observeDocument(long id) {
        return documentDao.observeById(id);
    }

    @MainThread
    public LiveData<List<ChatMessageEntity>> observeMessages(long documentId) {
        return chatMessageDao.observeForDocument(documentId);
    }

    // ---- writes (call on ioExecutor) ----

    @WorkerThread
    public long createDocument(String title,
                               Category category,
                               String imagePath,
                               @Nullable String thumbnailPath) {
        String titleSafe = title == null || title.trim().isEmpty() ? "Untitled" : title.trim();
        DocumentEntity entity = new DocumentEntity(
                titleSafe,
                category.name(),
                imagePath,
                thumbnailPath,
                null,
                System.currentTimeMillis());
        return documentDao.insert(entity);
    }

    @WorkerThread
    public void setSummary(long id, String summary) {
        documentDao.updateSummary(id, summary);
    }

    @WorkerThread
    public void rename(long id, String newTitle) {
        String safe = newTitle == null || newTitle.trim().isEmpty() ? "Untitled" : newTitle.trim();
        documentDao.updateTitle(id, safe);
    }

    @WorkerThread
    public void deleteDocument(long id) {
        DocumentEntity doc = documentDao.getById(id);
        if (doc == null) return;
        documentDao.delete(doc); // cascades messages
        // Clean up all pages (single image = 1 file; PDF = N files).
        for (String page : ImageStorage.allPagesFor(doc.imagePath)) {
            deleteFileQuiet(page);
        }
        if (doc.thumbnailPath != null) deleteFileQuiet(doc.thumbnailPath);
    }

    @WorkerThread
    public DocumentEntity getDocument(long id) {
        return documentDao.getById(id);
    }

    @WorkerThread
    public List<ChatMessageEntity> getMessages(long documentId) {
        return chatMessageDao.getForDocument(documentId);
    }

    @WorkerThread
    public long addMessage(long documentId, String role, String content) {
        ChatMessageEntity msg = new ChatMessageEntity(
                documentId, role, content, System.currentTimeMillis());
        return chatMessageDao.insert(msg);
    }

    @WorkerThread
    public void wipeEverything() {
        chatMessageDao.deleteAll();
        documentDao.deleteAll();
        ImageStorage.wipeAll(appContext);
    }

    private static void deleteFileQuiet(String path) {
        try {
            new File(path).delete();
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }
}
