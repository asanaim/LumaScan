# LumaScan (v1.2 — Fast & Light Edition)

LumaScan is a privacy-first, 100% offline document scanner, multi-script Optical Character Recognition (OCR), and searchable PDF generator for Android, built with Kotlin and Jetpack Compose as a faster, lighter replacement for Microsoft Lens.

## New in v1.2 (Faster, 70% Lighter, Zero Quality Loss)
- **70% Smaller APK (`arm64-v8a` Native Filter & Trimmed Bloat):** Strips unused 32-bit/x86 binaries and unused CJK neural network weights while keeping **English/Latin**, **Bangla + English (`ben+eng`)**, and **Devanagari + English** 100% offline.
- **Parallel Multi-Core OCR (`async`/`awaitAll`):** Processes multi-page scans concurrently across CPU cores and caches the `TessBaseAPI` LSTM model in memory for instant subsequent scans.
- **Smart Auto-File Naming:** Automatically names your exported `.pdf`, `.txt`, and `.csv` files after the document's top heading instead of generic timestamps.
- **ID Card 2-in-1 Page Stitcher:** Composites the front and back of an ID card or passport onto a single A4 page automatically.
- **Tap-to-Redact Privacy Mode (PII Sanitizer):** Automatically detects sensitive ID numbers, phone numbers, and emails, draws permanent black redaction bars over the image, and strips those tokens from the underlying PDF text layer.
- **Table-to-CSV Exporter & Business Card Contact Saver:** Reconstructs spatial word coordinates into downloadable `.csv` spreadsheets and extracts phone/email metadata into Android Contacts with one tap.
- **Instant Full-Text Keyword Search:** Filter across all scanned documents and OCR transcriptions in real time.
