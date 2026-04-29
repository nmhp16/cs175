package edu.sjsu.android.cs175.ui.chat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import edu.sjsu.android.cs175.R;
import edu.sjsu.android.cs175.data.Category;
import edu.sjsu.android.cs175.data.db.ChatMessageEntity;
import edu.sjsu.android.cs175.data.db.DocumentEntity;
import edu.sjsu.android.cs175.databinding.FragmentDocumentChatBinding;
import edu.sjsu.android.cs175.llm.LlmService;
import edu.sjsu.android.cs175.util.AppViewModelFactory;
import edu.sjsu.android.cs175.util.CategoryPrompts;

public class DocumentChatFragment extends Fragment {

    private FragmentDocumentChatBinding binding;
    private DocumentChatViewModel viewModel;
    private ChatMessageAdapter adapter;
    private boolean summaryExpanded = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentDocumentChatBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(this,
                AppViewModelFactory.from(requireActivity().getApplication()))
                .get(DocumentChatViewModel.class);

        long docId = getArguments() == null ? -1L : getArguments().getLong("documentId", -1L);
        if (docId <= 0) {
            navigateBack();
            return;
        }
        viewModel.attach(docId);

        setupToolbar();
        setupMessages();
        setupInput();
        setupSummaryCard();

        viewModel.getDocument().observe(getViewLifecycleOwner(), this::renderDocument);
        viewModel.getMessages().observe(getViewLifecycleOwner(), this::renderMessages);
        viewModel.getInProgress().observe(getViewLifecycleOwner(), ignored -> {
            // The in-progress text appears as a trailing "fake" bubble rendered
            // by the adapter. We rebuild the list whenever it changes.
            renderMessages(viewModel.getMessages().getValue());
        });
        viewModel.isGenerating().observe(getViewLifecycleOwner(), this::renderGenerating);
        viewModel.getModelStatus().observe(getViewLifecycleOwner(), this::renderStatus);
        viewModel.getErrorMessage().observe(getViewLifecycleOwner(), this::renderError);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // ------------------------------------------------------------------

    private void setupToolbar() {
        binding.toolbar.setNavigationOnClickListener(v -> navigateBack());
        binding.toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            DocumentEntity doc = viewModel.getDocument().getValue();
            if (doc == null) return false;
            if (id == R.id.action_view_image) {
                showImageDialog(doc.imagePath);
                return true;
            }
            if (id == R.id.action_rename) {
                showRenameDialog(doc.title);
                return true;
            }
            if (id == R.id.action_delete) {
                showDeleteConfirm();
                return true;
            }
            return false;
        });
    }

    private void setupMessages() {
        adapter = new ChatMessageAdapter(this::copyToClipboard);
        LinearLayoutManager lm = new LinearLayoutManager(requireContext());
        lm.setStackFromEnd(true);
        binding.messages.setLayoutManager(lm);
        binding.messages.setAdapter(adapter);
    }

    private void setupInput() {
        binding.sendButton.setOnClickListener(v -> {
            if (Boolean.TRUE.equals(viewModel.isGenerating().getValue())) {
                viewModel.cancelGeneration();
            } else {
                sendCurrent();
            }
        });
        binding.inputText.setOnEditorActionListener((tv, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrent();
                return true;
            }
            return false;
        });
    }

    private void sendCurrent() {
        String text = binding.inputText.getText() == null
                ? "" : binding.inputText.getText().toString();
        if (text.trim().isEmpty()) return;
        binding.inputText.setText("");
        viewModel.askQuestion(text);
    }

    private void setupSummaryCard() {
        binding.summaryCard.setOnClickListener(v -> {
            summaryExpanded = !summaryExpanded;
            binding.summaryText.setVisibility(summaryExpanded ? View.VISIBLE : View.GONE);
            binding.summaryChevron.setRotation(summaryExpanded ? 0f : 180f);
        });
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private void renderDocument(@Nullable DocumentEntity doc) {
        if (doc == null) return;
        binding.toolbar.setTitle(doc.title);
        String summary = doc.autoSummary;
        binding.summaryText.setText(
                (summary == null || summary.isEmpty())
                        ? getString(R.string.summary_missing)
                        : summary);
        Glide.with(this)
                .load(new File(doc.imagePath))
                .centerCrop()
                .into(binding.summaryThumb);

        renderSuggestionChips(Category.fromName(doc.category));
    }

    private void renderMessages(@Nullable List<ChatMessageEntity> messages) {
        List<ChatMessageEntity> combined = new ArrayList<>();
        if (messages != null) combined.addAll(messages);

        String partial = viewModel.getInProgress().getValue();
        if (partial != null) {
            combined.add(new ChatMessageEntity(
                    /* documentId */ -1L,
                    ChatMessageEntity.ROLE_ASSISTANT,
                    partial,
                    System.currentTimeMillis()));
            // Give the pseudo-item a stable negative id so DiffUtil doesn't think it's
            // the same as a real assistant message.
            combined.get(combined.size() - 1).id = -1L;
        }

        adapter.submitList(combined, () -> {
            int last = adapter.getItemCount() - 1;
            if (last >= 0) {
                binding.messages.scrollToPosition(last);
            }
        });

        // Only show suggestion chips when the chat is empty and the model is ready.
        boolean empty = combined.isEmpty();
        binding.suggestionsScroller.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void renderGenerating(Boolean isGenerating) {
        boolean gen = Boolean.TRUE.equals(isGenerating);
        binding.inputText.setEnabled(!gen);
        binding.inputText.setHint(gen
                ? R.string.chat_input_hint_thinking
                : R.string.chat_input_hint);
        binding.sendButton.setIconResource(gen
                ? android.R.drawable.ic_menu_close_clear_cancel
                : android.R.drawable.ic_menu_send);
    }

    private void renderStatus(LlmService.Status status) {
        if (status == null) return;
        switch (status.kind) {
            case READY:
                binding.statusBanner.setVisibility(View.GONE);
                enableInputIfReady(true);
                break;
            case ERROR:
                binding.statusBanner.setVisibility(View.VISIBLE);
                binding.statusSpinner.setVisibility(View.GONE);
                binding.statusText.setText(getString(R.string.chat_status_error,
                        status.errorMessage == null ? "" : status.errorMessage));
                enableInputIfReady(false);
                break;
            default:
                binding.statusBanner.setVisibility(View.VISIBLE);
                binding.statusSpinner.setVisibility(View.VISIBLE);
                binding.statusText.setText(R.string.chat_status_loading);
                enableInputIfReady(false);
                break;
        }
    }

    private void enableInputIfReady(boolean ready) {
        if (Boolean.TRUE.equals(viewModel.isGenerating().getValue())) return;
        binding.inputText.setEnabled(ready);
        binding.sendButton.setEnabled(ready);
    }

    private void renderError(@Nullable String message) {
        if (message == null || message.isEmpty()) return;
        com.google.android.material.snackbar.Snackbar.make(
                        binding.getRoot(), message,
                        com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                .setAction(R.string.action_dismiss, v -> viewModel.clearError())
                .show();
    }

    private void renderSuggestionChips(Category category) {
        binding.suggestionChips.removeAllViews();
        List<String> suggestions = CategoryPrompts.suggestionsFor(category);
        for (String s : suggestions) {
            Chip chip = new Chip(requireContext());
            chip.setText(s);
            chip.setOnClickListener(v -> viewModel.askQuestion(s));
            binding.suggestionChips.addView(chip);
        }
    }

    // ------------------------------------------------------------------

    private void showRenameDialog(String initial) {
        edu.sjsu.android.cs175.databinding.DialogRenameBinding db =
                edu.sjsu.android.cs175.databinding.DialogRenameBinding.inflate(getLayoutInflater());
        db.input.setText(initial);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.action_rename)
                .setView(db.getRoot())
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String value = db.input.getText() == null ? "" : db.input.getText().toString();
                    if (!value.trim().isEmpty()) viewModel.rename(value);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showDeleteConfirm() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.chat_delete_title)
                .setMessage(R.string.chat_delete_body)
                .setPositiveButton(R.string.action_delete,
                        (d, w) -> viewModel.deleteDocument(this::navigateBack))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showImageDialog(String imagePath) {
        ImageView iv = new ImageView(requireContext());
        iv.setAdjustViewBounds(true);
        Glide.with(this).load(new File(imagePath)).fitCenter().into(iv);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.chat_image_title)
                .setView(iv)
                .setPositiveButton(R.string.action_close, null)
                .show();
    }

    private void copyToClipboard(ChatMessageEntity msg) {
        ClipboardManager cm = (ClipboardManager)
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("DocuMind answer", msg.content));
        Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void navigateBack() {
        NavHostFragment.findNavController(this).popBackStack();
    }
}
