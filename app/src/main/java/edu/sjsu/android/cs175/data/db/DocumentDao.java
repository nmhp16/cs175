package edu.sjsu.android.cs175.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface DocumentDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(DocumentEntity document);

    @Delete
    void delete(DocumentEntity document);

    @Query("DELETE FROM documents WHERE id = :id")
    void deleteById(long id);

    @Query("UPDATE documents SET auto_summary = :summary WHERE id = :id")
    void updateSummary(long id, String summary);

    @Query("UPDATE documents SET title = :title WHERE id = :id")
    void updateTitle(long id, String title);

    @Query("SELECT * FROM documents WHERE id = :id")
    DocumentEntity getById(long id);

    @Query("SELECT * FROM documents WHERE id = :id")
    LiveData<DocumentEntity> observeById(long id);

    @Query("SELECT * FROM documents ORDER BY created_at DESC")
    LiveData<List<DocumentEntity>> observeAll();

    @Query("SELECT * FROM documents " +
            "WHERE (:category IS NULL OR category = :category) " +
            "  AND ( :query = '' " +
            "        OR title LIKE '%' || :query || '%' " +
            "        OR IFNULL(auto_summary, '') LIKE '%' || :query || '%' ) " +
            "ORDER BY created_at DESC")
    LiveData<List<DocumentEntity>> search(String query, String category);

    @Query("DELETE FROM documents")
    void deleteAll();
}
