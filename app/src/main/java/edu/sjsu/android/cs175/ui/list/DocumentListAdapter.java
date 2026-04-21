package edu.sjsu.android.cs175.ui.list;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;

import edu.sjsu.android.cs175.R;
import edu.sjsu.android.cs175.data.Category;
import edu.sjsu.android.cs175.data.db.DocumentEntity;
import edu.sjsu.android.cs175.databinding.ItemDocumentCardBinding;

public class DocumentListAdapter
        extends ListAdapter<DocumentEntity, DocumentListAdapter.DocumentViewHolder> {

    public interface Listener {
        void onClick(DocumentEntity doc);
        void onLongPress(DocumentEntity doc);
    }

    private final Listener listener;
    private final DateFormat dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM);

    public DocumentListAdapter(Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    @NonNull
    @Override
    public DocumentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        ItemDocumentCardBinding b = ItemDocumentCardBinding.inflate(inflater, parent, false);
        return new DocumentViewHolder(b);
    }

    @Override
    public void onBindViewHolder(@NonNull DocumentViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    class DocumentViewHolder extends RecyclerView.ViewHolder {
        private final ItemDocumentCardBinding b;
        DocumentViewHolder(ItemDocumentCardBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        void bind(DocumentEntity doc) {
            b.title.setText(doc.title);

            String summary = TextUtils.isEmpty(doc.autoSummary)
                    ? b.getRoot().getContext().getString(R.string.summary_pending)
                    : doc.autoSummary;
            b.summary.setText(summary);

            b.date.setText(dateFormat.format(new Date(doc.createdAt)));

            Category cat = Category.fromName(doc.category);
            b.categoryPill.setText(cat.getDisplayName());

            String imagePath = doc.thumbnailPath != null ? doc.thumbnailPath : doc.imagePath;
            Glide.with(b.thumbnail.getContext())
                    .load(new File(imagePath))
                    .centerCrop()
                    .into(b.thumbnail);

            b.card.setOnClickListener(v -> listener.onClick(doc));
            b.card.setOnLongClickListener(v -> {
                listener.onLongPress(doc);
                return true;
            });
        }
    }

    private static final DiffUtil.ItemCallback<DocumentEntity> DIFF =
            new DiffUtil.ItemCallback<DocumentEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull DocumentEntity a,
                                               @NonNull DocumentEntity b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull DocumentEntity a,
                                                  @NonNull DocumentEntity b) {
                    return a.id == b.id
                            && a.createdAt == b.createdAt
                            && equal(a.title, b.title)
                            && equal(a.category, b.category)
                            && equal(a.autoSummary, b.autoSummary)
                            && equal(a.imagePath, b.imagePath);
                }

                private boolean equal(Object x, Object y) {
                    return x == null ? y == null : x.equals(y);
                }
            };
}
