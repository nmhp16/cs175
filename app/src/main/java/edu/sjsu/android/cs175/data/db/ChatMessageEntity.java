package edu.sjsu.android.cs175.data.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "chat_messages",
        foreignKeys = @ForeignKey(
                entity = DocumentEntity.class,
                parentColumns = "id",
                childColumns = "document_id",
                onDelete = ForeignKey.CASCADE
        ),
        indices = {
                @Index("document_id"),
                @Index("timestamp")
        }
)
public class ChatMessageEntity {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "document_id")
    public long documentId;

    @NonNull
    public String role;

    @NonNull
    public String content;

    public long timestamp;

    public ChatMessageEntity(long documentId,
                             @NonNull String role,
                             @NonNull String content,
                             long timestamp) {
        this.documentId = documentId;
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    public boolean isUser() {
        return ROLE_USER.equals(role);
    }
}
