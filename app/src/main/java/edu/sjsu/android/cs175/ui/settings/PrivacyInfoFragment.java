package edu.sjsu.android.cs175.ui.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import edu.sjsu.android.cs175.R;
import edu.sjsu.android.cs175.databinding.FragmentPrivacyInfoBinding;
import edu.sjsu.android.cs175.llm.LlmService;
import edu.sjsu.android.cs175.util.AppViewModelFactory;

public class PrivacyInfoFragment extends Fragment {

    private FragmentPrivacyInfoBinding binding;
    private PrivacyInfoViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentPrivacyInfoBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewModel = new ViewModelProvider(this,
                AppViewModelFactory.from(requireActivity().getApplication()))
                .get(PrivacyInfoViewModel.class);

        binding.toolbar.setNavigationOnClickListener(v ->
                NavHostFragment.findNavController(this).popBackStack());

        binding.nukeButton.setOnClickListener(v -> showWipeConfirm());

        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        viewModel.refresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void render(PrivacyInfoViewModel.UiState state) {
        if (state == null) return;
        binding.modelName.setText(state.modelName);
        int backendRes;
        switch (state.backend) {
            case GEMMA_3N_MULTIMODAL: backendRes = R.string.backend_multimodal; break;
            case TEXT_WITH_OCR:       backendRes = R.string.backend_ocr; break;
            case UNAVAILABLE:
            default:                  backendRes = R.string.backend_none; break;
        }
        binding.modelBackend.setText(backendRes);

        if (state.sizeMb != null) {
            binding.modelSize.setVisibility(View.VISIBLE);
            binding.modelSize.setText(getString(R.string.model_size, state.sizeMb));
        } else {
            binding.modelSize.setVisibility(View.GONE);
        }

        binding.wipedIndicator.setVisibility(state.wipedJustNow ? View.VISIBLE : View.GONE);
        if (state.wipedJustNow) {
            binding.wipedIndicator.postDelayed(viewModel::clearWipedFlag, 3_000);
        }
    }

    private void showWipeConfirm() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.privacy_wipe_confirm_title)
                .setMessage(R.string.privacy_wipe_confirm_body)
                .setPositiveButton(R.string.privacy_wipe_confirm_positive,
                        (d, w) -> viewModel.wipeEverything())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
