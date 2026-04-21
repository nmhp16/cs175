package edu.sjsu.android.cs175.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ChatMessageDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(ChatMessageEntity message);

    @Query("SELECT * FROM chat_messages WHERE document_id = :documentId ORDER BY timestamp ASC")
    LiveData<List<ChatMessageEntity>> observeForDocument(long documentId);

    @Query("SELECT * FROM chat_messages WHERE document_id = :documentId ORDER BY timestamp ASC")
    List<ChatMessageEntity> getForDocument(long documentId);

    @Query("DELETE FROM chat_messages WHERE document_id = :documentId")
    void deleteForDocument(long documentId);

    @Query("DELETE FROM chat_messages")
    void deleteAll();
}
