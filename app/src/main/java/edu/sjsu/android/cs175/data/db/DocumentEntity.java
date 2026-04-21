package edu.sjsu.android.cs175.data.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "documents",
        indices = {
                @Index("category"),
                @Index("created_at")
        }
)
public class DocumentEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    public String title;

    @NonNull
    public String category;

    @NonNull
    @ColumnInfo(name = "image_path")
    public String imagePath;

    @Nullable
    @ColumnInfo(name = "thumbnail_path")
    public String thumbnailPath;

    @Nullable
    @ColumnInfo(name = "auto_summary")
    public String autoSummary;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    public DocumentEntity(@NonNull String title,
                          @NonNull String category,
                          @NonNull String imagePath,
                          @Nullable String thumbnailPath,
                          @Nullable String autoSummary,
                          long createdAt) {
        this.title = title;
        this.category = category;
        this.imagePath = imagePath;
        this.thumbnailPath = thumbnailPath;
        this.autoSummary = autoSummary;
        this.createdAt = createdAt;
    }
}
