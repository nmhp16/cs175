package edu.sjsu.android.cs175.ui.chat;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import edu.sjsu.android.cs175.data.db.ChatMessageEntity;
import edu.sjsu.android.cs175.data.db.DocumentEntity;
import edu.sjsu.android.cs175.data.repository.DocumentRepository;
import edu.sjsu.android.cs175.llm.LlmService;

public class DocumentChatViewModel extends ViewModel {

    private final DocumentRepository repository;
    private final LlmService llmService;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Long documentId;

    private final MutableLiveData<Long> idLive = new MutableLiveData<>();
    private final MediatorLiveData<DocumentEntity> documentLive = new MediatorLiveData<>();
    private final MediatorLiveData<List<ChatMessageEntity>> messagesLive = new MediatorLiveData<>();

    private final MutableLiveData<String> inProgress = new MutableLiveData<>(null);
    private final MutableLiveData<Boolean> generating = new MutableLiveData<>(false);
    private final MutableLiveData<String> errorMessage = new MutableLiveData<>(null);

    @Nullable private LlmService.Cancellable activeRequest;

    @Nullable private LiveData<DocumentEntity> docSource;
    @Nullable private LiveData<List<ChatMessageEntity>> msgSource;

    public DocumentChatViewModel(DocumentRepository repository, LlmService llmService) {
        this.repository = repository;
        this.llmService = llmService;
    }

    /** Called once by the fragment with the nav-arg documentId. */
    @MainThread
    public void attach(long documentId) {
        if (this.documentId != null && this.documentId == documentId) return;
        this.documentId = documentId;
        idLive.setValue(documentId);

        if (docSource != null) documentLive.removeSource(docSource);
        if (msgSource != null) messagesLive.removeSource(msgSource);

        docSource = repository.observeDocument(documentId);
        msgSource = repository.observeMessages(documentId);

        documentLive.addSource(docSource, documentLive::setValue);
        messagesLive.addSource(msgSource, messagesLive::setValue);
    }

    public LiveData<DocumentEntity> getDocument() { return documentLive; }
    public LiveData<List<ChatMessageEntity>> getMessages() { return messagesLive; }
    public LiveData<String> getInProgress() { return inProgress; }
    public LiveData<Boolean> isGenerating() { return generating; }
    public LiveData<String> getErrorMessage() { return errorMessage; }
    public LiveData<LlmService.Status> getModelStatus() { return llmService.statusLiveData(); }

    public void askQuestion(String question) {
        if (question == null) return;
        final String trimmed = question.trim();
        if (trimmed.isEmpty()) return;
        if (Boolean.TRUE.equals(generating.getValue())) return;

        final Long docId = documentId;
        if (docId == null) {
            errorMessage.setValue("Document is still loading");
            return;
        }
        final DocumentEntity doc = documentLive.getValue();
        if (doc == null) {
            errorMessage.setValue("Document is still loading");
            return;
        }

        errorMessage.setValue(null);

        // Persist user message immediately.
        repository.getIoExecutor().execute(() -> {
            repository.addMessage(docId, ChatMessageEntity.ROLE_USER, trimmed);

            if (!llmService.isReady()) {
                llmService.initialize();
            }
            if (!llmService.isReady()) {
                main.post(() -> errorMessage.setValue(
                        "The on-device model isn't ready yet. Try again in a moment."));
                return;
            }

            final List<ChatMessageEntity> history =
                    new ArrayList<>(repository.getMessages(docId));
            final List<LlmService.HistoryTurn> turns = new ArrayList<>(history.size());
            for (ChatMessageEntity m : history) {
                turns.add(new LlmService.HistoryTurn(m.role, m.content));
            }

            main.post(() -> {
                generating.setValue(true);
                inProgress.setValue("");
            });

            activeRequest = llmService.askQuestion(
                    doc.imagePath,
                    trimmed,
                    turns,
                    new LlmService.Listener() {
                        @Override
                        public void onPartial(String accumulatedText) {
                            main.post(() -> inProgress.setValue(accumulatedText));
                        }

                        @Override
                        public void onComplete(String finalText) {
                            String cleaned = finalText == null ? "" : finalText.trim();
                            if (!cleaned.isEmpty()) {
                                repository.getIoExecutor().execute(
                                        () -> repository.addMessage(docId,
                                                ChatMessageEntity.ROLE_ASSISTANT,
                                                cleaned));
                            }
                            main.post(() -> {
                                inProgress.setValue(null);
                                generating.setValue(false);
                            });
                        }

                        @Override
                        public void onError(Throwable t) {
                            String msg = t == null || t.getMessage() == null
                                    ? "Something went wrong generating a response"
                                    : t.getMessage();
                            main.post(() -> {
                                errorMessage.setValue(msg);
                                inProgress.setValue(null);
                                generating.setValue(false);
                            });
                        }
                    });
        });
    }

    public void cancelGeneration() {
        if (activeRequest != null) activeRequest.cancel();
        activeRequest = null;
        inProgress.setValue(null);
        generating.setValue(false);
    }

    public void rename(String newTitle) {
        Long id = documentId;
        if (id == null) return;
        repository.getIoExecutor().execute(() -> repository.rename(id, newTitle));
    }

    public void deleteDocument(Runnable onDone) {
        Long id = documentId;
        if (id == null) return;
        repository.getIoExecutor().execute(() -> {
            repository.deleteDocument(id);
            main.post(onDone);
        });
    }

    public void clearError() {
        errorMessage.setValue(null);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (activeRequest != null) activeRequest.cancel();
    }
}
