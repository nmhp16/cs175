package edu.sjsu.android.cs175.ui.chat;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import io.noties.markwon.Markwon;
import io.noties.markwon.linkify.LinkifyPlugin;

import edu.sjsu.android.cs175.R;
import edu.sjsu.android.cs175.data.db.ChatMessageEntity;
import edu.sjsu.android.cs175.databinding.ItemChatMessageBinding;

public class ChatMessageAdapter
        extends ListAdapter<ChatMessageEntity, ChatMessageAdapter.MessageViewHolder> {

    public interface Listener {
        void onLongPressAssistant(ChatMessageEntity msg);
    }

    private final Listener listener;
    private Markwon markwon;

    public ChatMessageAdapter(Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (markwon == null) {
            markwon = markwonFor(parent.getContext());
        }
        ItemChatMessageBinding b = ItemChatMessageBinding.inflate(inflater, parent, false);
        return new MessageViewHolder(b);
    }

    private static Markwon markwonFor(Context ctx) {
        return Markwon.builder(ctx)
                .usePlugin(LinkifyPlugin.create())
                .build();
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        holder.bind(getItem(position));
    }

    class MessageViewHolder extends RecyclerView.ViewHolder {
        private final ItemChatMessageBinding b;

        MessageViewHolder(ItemChatMessageBinding b) {
            super(b.getRoot());
            this.b = b;
        }

        void bind(ChatMessageEntity msg) {
            boolean isUser = msg.isUser();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) b.bubble.getLayoutParams();
            lp.gravity = isUser ? Gravity.END : Gravity.START;
            b.bubble.setLayoutParams(lp);

            if (isUser) {
                b.bubble.setBackgroundResource(R.drawable.bg_bubble_user);
                b.bubble.setTextColor(
                        com.google.android.material.color.MaterialColors.getColor(
                                b.bubble, com.google.android.material.R.attr.colorOnPrimary));
            } else {
                b.bubble.setBackgroundResource(R.drawable.bg_bubble_assistant);
                b.bubble.setTextColor(
                        com.google.android.material.color.MaterialColors.getColor(
                                b.bubble, com.google.android.material.R.attr.colorOnSurfaceVariant));
            }

            String content = msg.content == null || msg.content.isEmpty() ? "…" : msg.content;

            // User bubbles: plain text (the user typed it, no Markdown to render).
            // Assistant bubbles: render Markdown so **bold**, *italic*, and
            // bulleted lists show properly instead of raw asterisks.
            if (isUser) {
                b.bubble.setText(content);
            } else {
                markwon.setMarkdown(b.bubble, content);
            }

            b.bubble.setOnLongClickListener(v -> {
                if (!isUser) listener.onLongPressAssistant(msg);
                return !isUser;
            });
        }
    }

    private static final DiffUtil.ItemCallback<ChatMessageEntity> DIFF =
            new DiffUtil.ItemCallback<ChatMessageEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull ChatMessageEntity a,
                                               @NonNull ChatMessageEntity b) {
                    return a.id == b.id;
                }
                @Override
                public boolean areContentsTheSame(@NonNull ChatMessageEntity a,
                                                  @NonNull ChatMessageEntity b) {
                    return a.id == b.id
                            && a.timestamp == b.timestamp
                            && a.role.equals(b.role)
                            && a.content.equals(b.content);
                }
            };
}
