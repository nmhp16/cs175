# DocuMind

On-device document Q&A for Android. Import a PDF or photo of a document, and DocuMind auto-classifies it (category + title + summary) and lets you chat with it — all running locally on the phone.

## How it works

- **LLM:** Gemma 4 E2B via LiteRT-LM (Google's on-device runtime). Model file `gemma-4-E2B-it.litertlm` (~2.58 GB) is downloaded once on first launch, then the app runs fully offline.
- **OCR:** ML Kit Text Recognition extracts text from image-based documents before handing it to the model (Gemma 4's vision encoder isn't bundled in the `.litertlm` yet).
- **Storage:** Room for document/chat persistence, Glide for thumbnails, Markwon for rendering model replies.
- **Single-pass analysis:** one inference produces category, title, and summary together.

## Project layout

```
app/src/main/java/edu/sjsu/android/cs175/
├── MainActivity.java, DocuMindApp.java
├── llm/      — GemmaLlmService, ModelDownloader, Prompts
├── data/     — Room entities, DAOs, repositories
├── ui/       — add / chat / list / settings fragments
└── util/     — ViewModel factory, image storage, category prompts
```

- `minSdk` 29, `targetSdk`/`compileSdk` 36, Java 17, AGP 9.1.1
- Namespace: `edu.sjsu.android.cs175`

## Building

```bash
./gradlew assembleDebug
```

Open in Android Studio and run on an emulator or device.

## Running on the Android Emulator

The model file is ~2.58 GB and Gemma 4 E2B needs headroom to load, so the default emulator image (usually 6 GB storage / 2 GB RAM) is not enough. Resize the emulator **before** first launch:

1. In Android Studio, open **Tools → Device Manager**.
2. Find your virtual device in the list and click the pencil / **Edit** icon.
3. Click **Show Advanced Settings** (or **Additional settings** in newer Studio versions).
4. Set:
   - **Internal Storage:** `32 GB`
   - **RAM:** `8 GB` (`8192 MB`)
   - **VM heap:** bump to `512 MB` if the option is shown.
5. Click **Finish** / **Save**. If prompted to wipe data, accept — the new sizes only take effect on a fresh image.
6. Cold-boot the emulator (Device Manager → ▾ menu → **Cold Boot Now**).

On a physical device, just make sure you have ~4 GB free before first launch.

## First launch

1. App opens and checks for the model file in internal storage.
2. If missing, it downloads `gemma-4-E2B-it.litertlm` from the LiteRT community repo. This is the only time the app uses the network — the `INTERNET` permission exists solely for this step.
3. Once downloaded, add a document (PDF or image) and DocuMind analyzes and opens it for chat.

## Permissions

- `INTERNET` / `ACCESS_NETWORK_STATE` — one-time model download only.
- GPU native libs (`libOpenCL.so`, `libvndksupport.so`) are declared optional; LiteRT-LM falls back to CPU if unavailable (typical on emulators).
