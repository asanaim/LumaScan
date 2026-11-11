# LumaScan (v1.3 — Complete Production Suite)

LumaScan is a privacy-first, 100% offline document scanner, multi-script Optical Character Recognition (OCR), and searchable PDF generator for Android, built with Kotlin and Jetpack Compose as a modern replacement for Microsoft Lens.

## 🚀 1-Click Direct Download for Non-IT Users
Share this direct link with anyone on Android (no GitHub account required):
- **[Direct Download LumaScan.apk](https://github.com/asanaim/LumaScan/releases/download/latest/LumaScan.apk)**
- **[Web Download Landing Page](https://asanaim.github.io/LumaScan/)**

## ✨ Core Features
1. **Searchable PDF ("Sandwich PDF"):** Embeds word-aligned selectable text over the image using Android's native Skia `PdfDocument` engine.
2. **Multi-Script Offline OCR:** English/Latin, Bangla + English (`ben+eng`), and Devanagari + English (`hin+eng`) with zero cloud dependencies.
3. **Smart Auto-Naming:** Inspects document top headings and names files automatically (e.g. `Electricity_Bill_2026.pdf`).
4. **PDF File Size Optimizer:** 85% JPEG stream compression reduces 40MB+ scans down to ~2MB with crystal-clear 300 DPI text edges.
5. **ID Card 2-in-1 Page Stitcher:** Stitches Front & Back of an ID card onto a single A4 page.
6. **Tap-to-Redact Privacy Mode:** Automatically blacks out sensitive phone/email/card numbers from both the image and text layers.
7. **Table-to-CSV Export:** Reconstructs spatial word boxes into Excel/Sheets-compatible `.csv` files.
8. **Business Card Contact Saver:** One-tap save detected phone/email to Android Contacts.
9. **Persistent Local History:** Local SQLite database remembers all scans and allows instant keyword search.
10. **Embedded PDF Download Link:** Every generated PDF includes a discrete footer link redirecting to the app download page.
