package edu.sjsu.android.cs175.ui.settings;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import edu.sjsu.android.cs175.data.repository.DocumentRepository;
import edu.sjsu.android.cs175.llm.LlmService;

public class PrivacyInfoViewModel extends ViewModel {

    public static final class UiState {
        public final String modelName;
        public final LlmService.Backend backend;
        @Nullable public final Long sizeMb;
        public final boolean wipedJustNow;

        public UiState(String modelName, LlmService.Backend backend,
                       @Nullable Long sizeMb, boolean wipedJustNow) {
            this.modelName = modelName;
            this.backend = backend;
            this.sizeMb = sizeMb;
            this.wipedJustNow = wipedJustNow;
        }
    }

    private final DocumentRepository repository;
    private final LlmService llmService;

    private final MutableLiveData<UiState> uiState = new MutableLiveData<>();

    public PrivacyInfoViewModel(DocumentRepository repository, LlmService llmService) {
        this.repository = repository;
        this.llmService = llmService;
        refresh();
    }

    public LiveData<UiState> getUiState() { return uiState; }

    public void refresh() {
        LlmService.ModelInfo info = llmService.modelInfo();
        UiState cur = uiState.getValue();
        boolean justWiped = cur != null && cur.wipedJustNow;
        uiState.setValue(new UiState(info.name, info.backend, info.sizeMb, justWiped));
    }

    public void wipeEverything() {
        repository.getIoExecutor().execute(() -> {
            repository.wipeEverything();
            uiState.postValue(new UiState(
                    currentName(), currentBackend(), currentSize(), true));
        });
    }

    public void clearWipedFlag() {
        UiState cur = uiState.getValue();
        if (cur == null) return;
        uiState.setValue(new UiState(cur.modelName, cur.backend, cur.sizeMb, false));
    }

    private String currentName() {
        UiState s = uiState.getValue();
        return s == null ? llmService.modelInfo().name : s.modelName;
    }
    private LlmService.Backend currentBackend() {
        UiState s = uiState.getValue();
        return s == null ? llmService.modelInfo().backend : s.backend;
    }
    @Nullable
    private Long currentSize() {
        UiState s = uiState.getValue();
        return s == null ? llmService.modelInfo().sizeMb : s.sizeMb;
    }
}
