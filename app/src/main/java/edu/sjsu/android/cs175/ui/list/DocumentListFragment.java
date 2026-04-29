package edu.sjsu.android.cs175.ui.list;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.GridLayoutManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.chip.Chip;
import com.google.android.material.textfield.TextInputEditText;

import edu.sjsu.android.cs175.DocuMindApp;
import edu.sjsu.android.cs175.R;
import edu.sjsu.android.cs175.data.Category;
import edu.sjsu.android.cs175.data.db.DocumentEntity;
import edu.sjsu.android.cs175.databinding.DialogRenameBinding;
import edu.sjsu.android.cs175.databinding.FragmentDocumentListBinding;
import edu.sjsu.android.cs175.databinding.SheetDocumentActionsBinding;
import edu.sjsu.android.cs175.llm.ModelDownloader;
import edu.sjsu.android.cs175.util.AppViewModelFactory;

public class DocumentListFragment extends Fragment {

    private FragmentDocumentListBinding binding;
    private DocumentListViewModel viewModel;
    private DocumentListAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentDocumentListBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(this,
                AppViewModelFactory.from(requireActivity().getApplication()))
                .get(DocumentListViewModel.class);

        adapter = new DocumentListAdapter(new DocumentListAdapter.Listener() {
            @Override
            public void onClick(DocumentEntity doc) {
                Bundle args = new Bundle();
                args.putLong("documentId", doc.id);
                NavHostFragment.findNavController(DocumentListFragment.this)
                        .navigate(R.id.action_list_to_chat, args);
            }

            @Override
            public void onLongPress(DocumentEntity doc) {
                showActionsSheet(doc);
            }
        });

        int spanCount = Math.max(1, getResources().getDisplayMetrics().widthPixels / dp(180));
        binding.documentGrid.setLayoutManager(new GridLayoutManager(requireContext(), spanCount));
        binding.documentGrid.setAdapter(adapter);

        setupChips();
        setupSearchToggle();
        setupFab();

        viewModel.getDocuments().observe(getViewLifecycleOwner(), docs -> {
            adapter.submitList(docs);
            boolean empty = docs == null || docs.isEmpty();
            binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
            binding.documentGrid.setVisibility(empty ? View.GONE : View.VISIBLE);
        });

        binding.searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                viewModel.setText(s == null ? "" : s.toString());
            }
        });

        binding.emptyCta.setOnClickListener(v -> navigateToAdd());

        observeModelDownload();
    }

    private void observeModelDownload() {
        DocuMindApp app = (DocuMindApp) requireActivity().getApplication();
        ModelDownloader downloader = app.getModelDownloader();
        if (downloader == null) return;

        binding.downloadRetry.setOnClickListener(v -> downloader.ensureModelDownloaded());

        downloader.progressLiveData().observe(getViewLifecycleOwner(), p -> {
            if (p == null) {
                binding.downloadBanner.setVisibility(View.GONE);
                return;
            }
            switch (p.state) {
                case IDLE:
                case ALREADY_PRESENT:
                case COMPLETE:
                    binding.downloadBanner.setVisibility(View.GONE);
                    break;
                case CONNECTING:
                    binding.downloadBanner.setVisibility(View.VISIBLE);
                    binding.downloadText.setText(R.string.download_connecting);
                    binding.downloadBar.setIndeterminate(true);
                    binding.downloadBar.setVisibility(View.VISIBLE);
                    binding.downloadRetry.setVisibility(View.GONE);
                    break;
                case DOWNLOADING:
                    binding.downloadBanner.setVisibility(View.VISIBLE);
                    binding.downloadBar.setVisibility(View.VISIBLE);
                    int pct = p.percent();
                    if (pct >= 0) {
                        binding.downloadBar.setIndeterminate(false);
                        binding.downloadBar.setProgress(pct);
                        binding.downloadText.setText(getString(
                                R.string.download_progress,
                                pct,
                                formatBytes(p.bytesDownloaded),
                                formatBytes(p.totalBytes)));
                    } else {
                        binding.downloadBar.setIndeterminate(true);
                        binding.downloadText.setText(getString(
                                R.string.download_progress_unknown,
                                formatBytes(p.bytesDownloaded)));
                    }
                    binding.downloadRetry.setVisibility(View.GONE);
                    break;
                case FAILED:
                    binding.downloadBanner.setVisibility(View.VISIBLE);
                    binding.downloadBar.setVisibility(View.GONE);
                    binding.downloadText.setText(getString(
                            R.string.download_failed,
                            p.errorMessage == null ? "Unknown error" : p.errorMessage));
                    binding.downloadRetry.setVisibility(View.VISIBLE);
                    break;
            }
        });
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024) return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", mb);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // ------------------------------------------------------------------

    private void setupChips() {
        binding.categoryChipGroup.removeAllViews();
        addChip(null, true);
        for (Category c : Category.values()) {
            addChip(c, false);
        }
        binding.categoryChipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                viewModel.setCategory(null);
                return;
            }
            int id = checkedIds.get(0);
            Chip chip = binding.categoryChipGroup.findViewById(id);
            Object tag = chip == null ? null : chip.getTag();
            viewModel.setCategory(tag instanceof Category ? (Category) tag : null);
        });
    }

    private void addChip(@Nullable Category c, boolean checked) {
        Chip chip = new Chip(requireContext());
        chip.setId(View.generateViewId());
        chip.setText(c == null ? getString(R.string.filter_all) : c.getDisplayName());
        chip.setTag(c);
        chip.setCheckable(true);
        chip.setChecked(checked);
        binding.categoryChipGroup.addView(chip);
    }

    private void setupSearchToggle() {
        binding.toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_search) {
                boolean showing = binding.searchLayout.getVisibility() == View.VISIBLE;
                binding.searchLayout.setVisibility(showing ? View.GONE : View.VISIBLE);
                if (!showing) binding.searchInput.requestFocus();
                else viewModel.setText("");
                return true;
            }
            if (id == R.id.action_privacy) {
                NavHostFragment.findNavController(this).navigate(R.id.action_list_to_privacy);
                return true;
            }
            return false;
        });
    }

    private void setupFab() {
        binding.fabAdd.setOnClickListener(v -> navigateToAdd());
    }

    private void navigateToAdd() {
        NavHostFragment.findNavController(this).navigate(R.id.action_list_to_add);
    }

    private void showActionsSheet(DocumentEntity doc) {
        BottomSheetDialog sheet = new BottomSheetDialog(requireContext());
        SheetDocumentActionsBinding sb =
                SheetDocumentActionsBinding.inflate(getLayoutInflater());
        sb.title.setText(doc.title);
        sb.actionRename.setOnClickListener(v -> {
            sheet.dismiss();
            showRenameDialog(doc);
        });
        sb.actionDelete.setOnClickListener(v -> {
            sheet.dismiss();
            showDeleteConfirm(doc);
        });
        sheet.setContentView(sb.getRoot());
        sheet.show();
    }

    private void showRenameDialog(DocumentEntity doc) {
        DialogRenameBinding db = DialogRenameBinding.inflate(getLayoutInflater());
        db.input.setText(doc.title);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.action_rename)
                .setView(db.getRoot())
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String value = db.input.getText() == null ? "" : db.input.getText().toString();
                    if (!TextUtils.isEmpty(value)) viewModel.rename(doc.id, value);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showDeleteConfirm(DocumentEntity doc) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.chat_delete_title)
                .setMessage(getString(R.string.chat_delete_body))
                .setPositiveButton(R.string.action_delete, (dialog, which) -> viewModel.delete(doc.id))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
