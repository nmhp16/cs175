package edu.sjsu.android.cs175.ui.add;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.bumptech.glide.Glide;

import edu.sjsu.android.cs175.R;
import edu.sjsu.android.cs175.data.Category;
import edu.sjsu.android.cs175.databinding.FragmentAddDocumentBinding;
import edu.sjsu.android.cs175.util.AppViewModelFactory;

public class AddDocumentFragment extends Fragment {

    private FragmentAddDocumentBinding binding;
    private AddDocumentViewModel viewModel;
    private ActivityResultLauncher<String[]> pickMedia;

    private static final String[] SUPPORTED_MIMES = {"image/*", "application/pdf"};

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentAddDocumentBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pickMedia = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                (Uri uri) -> viewModel.onImagePicked(uri));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(this,
                AppViewModelFactory.from(requireActivity().getApplication()))
                .get(AddDocumentViewModel.class);

        binding.toolbar.setNavigationOnClickListener(v -> navigateBack());
        binding.cancelButton.setOnClickListener(v -> navigateBack());
        binding.pickButton.setOnClickListener(v -> launchPicker());
        binding.imageContainer.setOnClickListener(v -> launchPicker());
        binding.changeImageButton.setOnClickListener(v -> launchPicker());

        binding.saveButton.setOnClickListener(v -> viewModel.save(id -> {
            Bundle args = new Bundle();
            args.putLong("documentId", id);
            NavHostFragment.findNavController(this).navigate(R.id.action_add_to_chat, args);
        }));

        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    // ------------------------------------------------------------------

    private void launchPicker() {
        pickMedia.launch(SUPPORTED_MIMES);
    }

    private void render(AddDocumentViewModel.UiState state) {
        boolean hasImage = state.pickedUri != null;
        binding.pickPlaceholder.setVisibility(hasImage ? View.GONE : View.VISIBLE);
        binding.preview.setVisibility(hasImage ? View.VISIBLE : View.GONE);
        binding.changeImageButton.setVisibility(hasImage ? View.VISIBLE : View.GONE);

        if (hasImage) {
            Glide.with(this).load(state.pickedUri).centerCrop().into(binding.preview);
        }

        // Detected card: only show once analysis has settled on a title
        // (analyzing == false AND we have a non-empty title from auto-fill).
        boolean showDetected = hasImage && !state.isAnalyzing
                && state.title != null && !state.title.trim().isEmpty();
        binding.detectedCard.setVisibility(showDetected ? View.VISIBLE : View.GONE);
        if (showDetected) {
            binding.detectedTitle.setText(state.title);
            binding.detectedCategoryChip.setText(state.category.getDisplayName());
            if (state.detectedSummary != null && !state.detectedSummary.isEmpty()) {
                binding.detectedSummary.setVisibility(View.VISIBLE);
                binding.detectedSummary.setText(state.detectedSummary);
            } else {
                binding.detectedSummary.setVisibility(View.GONE);
            }
        }

        // Save is only enabled after analysis finishes so the user doesn't
        // save prematurely with "Untitled" / "Other".
        binding.saveButton.setEnabled(hasImage && !state.isSaving && !state.isAnalyzing);
        binding.savingProgress.setVisibility(state.isSaving ? View.VISIBLE : View.GONE);
        binding.analyzingRow.setVisibility(state.isAnalyzing ? View.VISIBLE : View.GONE);

        if (state.errorMessage != null) {
            binding.errorText.setVisibility(View.VISIBLE);
            binding.errorText.setText(state.errorMessage);
        } else {
            binding.errorText.setVisibility(View.GONE);
        }
    }

    private void navigateBack() {
        NavHostFragment.findNavController(this).popBackStack();
    }
}
