# DocumentFacilitator

An offline Android document viewer and editor. No network permission, no third-party
libraries, no AndroidX: the APK contains only the app's own code, so it stays small and
light on RAM.

## What it does

| Area | Details |
|---|---|
| Formats (view) | PDF, images (PNG/JPEG/GIF/WebP/BMP/HEIC), DOCX, XLSX, PPTX, ODT, ODS, ODP, RTF, EPUB, HTML, Markdown, CSV/TSV, plain text and source code, legacy DOC/XLS/PPT (text and cell values only) |
| Modes | **VIEW** (read only), **EDIT** (change the document's own content), **FILL** (finds the blanks to fill in, offline) |
| Two documents | Split view inside one window (draggable divider, side by side or stacked), or "Open in separate window" |
| Android multi-window | Resizable; works in split-screen, freeform and on a second display next to other apps |
| States | Save any number of named versions (draft, highlighted copy, ...) inside the app, load them later, export them to the phone when wanted. Unexported work is auto-kept if the app is closed |
| Other tools | Undo/redo, find, print, share, night colours, text size, go to page, rotate/delete PDF pages, signature drawing, image insertion, word count, new documents |

### EDIT mode per type

* **PDF / images** - highlight, pen, text, boxes, lines, check marks, images, signatures,
  *Erase* (cover) and *Replace* (cover existing content and retype over it). PDFs are saved as
  an incremental update: the original text and vectors stay intact and searchable.
* **DOCX / PPTX** - type directly in the text. Text changes are written back into the original
  file without touching any formatting. If a DOCX edit changes structure (new paragraphs,
  bold, images), the document is rebuilt as a new, simpler DOCX and the app says so.
* **XLSX** - tap a cell to edit values or formulas; only changed cells are rewritten.
* **CSV, ODS, XLS** - edit in the grid, save as CSV or a new XLSX.
* **ODT, RTF, EPUB, DOC, HTML** - rich-text editing, saved as DOCX or HTML.
* **Text / Markdown / code** - native text editor with find and replace; Markdown has a preview.

### FILL mode

* PDFs with real form fields: text, check boxes, radio buttons, drop-downs and signature
  fields are listed and filled; values are stored as real form values.
* Flat PDFs and scanned images: the page picture is analysed on the device for fill-in
  lines, underscore runs, empty boxes and table cells, check boxes, radio circles and large
  empty areas (text, signature or image).
* Office and text documents: content controls, form fields, `____` blanks, `[ ]` / ☐ boxes,
  `[Placeholder]`, `{{name}}`, empty table cells next to a caption, and "Label:" lines.
* *Prev* / *Next* walk through the fields.

## Known limits

* Editing existing PDF text is done by covering and retyping, not by rewriting the PDF's
  text objects. A cover box hides content but does not remove it from the file.
* Password-protected PDFs are not supported. Finding text inside PDFs needs Android 15+.
* Text typed into PDFs uses the built-in Helvetica font; text outside Latin-1 is embedded as
  an image.
* PPTX and legacy/ODF formats are shown as reflowed content, not pixel-exact page layout.
* Very large sheets show the first 2000 rows and 100 columns.

## Build

Requires JDK 17 and the Android SDK (platform 35).

```
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk (minified, debug-signed)
./gradlew :core:test               # document engine tests
```

`.github/workflows/build.yml` runs both and uploads the APKs as an artifact.

On a machine without the Android SDK, `DF_CORE_ONLY=1 ./gradlew :core:test` builds and
tests the document engine alone.

## Layout

* `core/` - pure Kotlin document engine (format converters, lossless OOXML/XLSX patching,
  PDF parser and incremental writer, form filling, blank detection) with unit tests.
* `app/` - Android UI: honeycomb home screen, viewer panes, page view, in-page editor
  (`assets/doc.js`), state storage.
