# GC — Smart Product Photo Finder

Native Android inventory catalogue built with Kotlin, Jetpack Compose, Room and a product normalization engine.

## Implemented
- CSV import via Android Storage Access Framework.
- RFC-style quoted CSV parsing, commas inside quoted fields, UTF-8 BOM handling and flexible header aliases.
- Separate original description and editable normalized product name.
- EAN and article codes retained as strings (leading zeroes are not discarded).
- Room-backed product catalogue and import history.
- Local search across product name, EAN, article code and brand.
- Product detail editor.
- Google Custom Search JSON API image search action, up to five HTTPS candidates, source metadata, basic confidence labels and manual candidate selection.
- Unit tests for quoted CSV cells, identifier preservation, numeric fields and known abbreviation expansion.
- GitHub Actions workflow for unit tests, debug APK build and artifact upload.

## Current scope and remaining work
This is an early functional foundation, not yet the complete feature set in the product specification. CSV preview/manual column mapping, batch/background image processing, persistent image caching, candidate rejection/history, advanced filters/sorting, duplicate review UI, CSV/PDF export and complete settings/history screens are not implemented yet. Search candidates are not automatically verified as exact products; confidence labels are heuristic and must be reviewed by a person. Google image search has not been live-tested with credentials.

## Google image search credentials
Create a Programmable Search Engine with image search enabled and enable the Custom Search JSON API in Google Cloud. Put the following in your local, untracked `local.properties` file:
```properties
GOOGLE_CSE_KEY=your_api_key
GOOGLE_CSE_ID=your_search_engine_id
```
Never commit `local.properties`. API quota and billing are controlled by Google. The app displays a configuration error when credentials are missing.

## Build
Requirements: JDK 17, Android SDK platform 35 and Gradle 8.9.
```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```
The GitHub Actions workflow runs both commands and uploads `gc-debug-apk` when successful.

## CSV columns
A product description column is required. Supported aliases include EAN/Barcode, Article/Article Code, Product Name/Article Description, Brand, MRP/Price and Qty/Quantity.
