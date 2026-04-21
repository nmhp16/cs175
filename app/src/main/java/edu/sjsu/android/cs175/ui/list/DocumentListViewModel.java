package edu.sjsu.android.cs175.ui.list;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;

import java.util.List;

import edu.sjsu.android.cs175.data.Category;
import edu.sjsu.android.cs175.data.db.DocumentEntity;
import edu.sjsu.android.cs175.data.repository.DocumentRepository;

public class DocumentListViewModel extends ViewModel {

    public static final class Query {
        public final String text;
        @Nullable public final Category category;
        public Query(String text, @Nullable Category category) {
            this.text = text == null ? "" : text;
            this.category = category;
        }
    }

    private final DocumentRepository repository;
    private final MutableLiveData<Query> query = new MutableLiveData<>(new Query("", null));
    private final LiveData<List<DocumentEntity>> documents;

    public DocumentListViewModel(DocumentRepository repository) {
        this.repository = repository;
        this.documents = Transformations.switchMap(query,
                q -> repository.searchDocuments(q.text, q.category));
    }

    public LiveData<Query> getQuery() { return query; }

    public LiveData<List<DocumentEntity>> getDocuments() { return documents; }

    public void setText(String text) {
        Query cur = query.getValue();
        Category cat = cur == null ? null : cur.category;
        query.setValue(new Query(text, cat));
    }

    public void setCategory(@Nullable Category category) {
        Query cur = query.getValue();
        String text = cur == null ? "" : cur.text;
        query.setValue(new Query(text, category));
    }

    public void delete(long id) {
        repository.getIoExecutor().execute(() -> repository.deleteDocument(id));
    }

    public void rename(long id, String newTitle) {
        repository.getIoExecutor().execute(() -> repository.rename(id, newTitle));
    }
}
