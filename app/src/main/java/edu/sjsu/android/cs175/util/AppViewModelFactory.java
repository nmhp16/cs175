package edu.sjsu.android.cs175.util;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import edu.sjsu.android.cs175.DocuMindApp;
import edu.sjsu.android.cs175.ui.add.AddDocumentViewModel;
import edu.sjsu.android.cs175.ui.chat.DocumentChatViewModel;
import edu.sjsu.android.cs175.ui.list.DocumentListViewModel;
import edu.sjsu.android.cs175.ui.settings.PrivacyInfoViewModel;

/**
 * Hands ViewModels their dependencies. Replaces Hilt for this project size.
 */
public class AppViewModelFactory implements ViewModelProvider.Factory {

    private final DocuMindApp app;

    private AppViewModelFactory(DocuMindApp app) {
        this.app = app;
    }

    public static AppViewModelFactory from(Application application) {
        return new AppViewModelFactory((DocuMindApp) application);
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(DocumentListViewModel.class)) {
            return (T) new DocumentListViewModel(app.getRepository());
        }
        if (modelClass.isAssignableFrom(AddDocumentViewModel.class)) {
            return (T) new AddDocumentViewModel(
                    app,
                    app.getRepository(),
                    app.getLlmService());
        }
        if (modelClass.isAssignableFrom(DocumentChatViewModel.class)) {
            return (T) new DocumentChatViewModel(
                    app.getRepository(),
                    app.getLlmService());
        }
        if (modelClass.isAssignableFrom(PrivacyInfoViewModel.class)) {
            return (T) new PrivacyInfoViewModel(
                    app.getRepository(),
                    app.getLlmService());
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass);
    }
}
