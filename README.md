# LumaScan

LumaScan is a privacy-first, 100% offline document scanner, multi-script Optical Character Recognition (OCR), and searchable PDF generator for Android, built with Kotlin and Jetpack Compose as a modern replacement for Microsoft Lens.

## Key Features
- **Automatic Edge Detection & Perspective Correction:** Captures documents, receipts, business cards, and whiteboards with automatic shutter release and manual corner adjustment.
- **Whiteboard & Document Filters:** Removes glare, shadows, and uneven lighting for crisp readable scans.
- **Multi-Script Offline OCR:**
  - **English / Latin** (Bundled ML Kit v2)
  - **Bangla + English** (Offline Tesseract 5 LSTM `ben+eng`)
  - **Devanagari + English** (Bundled ML Kit v2)
  - **Chinese / Japanese / Korean + English** (Bundled ML Kit v2)
- **Searchable PDF Generation ("Sandwich PDF"):** Embeds an invisible, word-aligned selectable text layer directly over the scanned document bitmap using Android's native `PdfDocument` engine.
- **Zero Network Permissions:** Operates without `android.permission.INTERNET`, guaranteeing that scanned documents and extracted text never leave your device.
- **Direct Local Export:** Saves searchable multi-page `.pdf` files and `.txt` transcriptions directly to `Downloads/LumaScan`.

## How to Get the Installable `.apk` on GitHub (No PC Setup Needed)
1. Create a new repository on GitHub named **LumaScan**.
2. Upload/extract all files from `LumaScan-Android-Project.zip` into the repository and commit to the `main` branch.
3. Click the **Actions** tab in your GitHub repository.
4. The **Build LumaScan APK** workflow will automatically download the Bangla + English Tesseract models, compile the Android project in the cloud, and publish **`LumaScan-MultiScript-SearchablePDF-APK`** as a downloadable artifact.

## Requirements
- Android 7.0 (API 24) or higher

## License
MIT License
