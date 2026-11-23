# GC — Smart Product Photo Finder

Android inventory catalogue built with Kotlin, Jetpack Compose, Room and a CSV normalization engine.

## Included in this initial implementation
- CSV import using Android Storage Access Framework.
- Quoted CSV parsing, including commas in quoted descriptions, UTF-8 BOM handling and column aliases.
- Separate original and cleaned descriptions; EAN and article codes are preserved as strings.
- Room-backed local catalogue and import history.
- Local search by product name, EAN, article code and brand.
- Editable normalized product names.
- Google Custom Search JSON API image-search client with HTTPS-only results, source metadata and confidence labels.
- GitHub Actions unit-test and debug-APK workflow.

## Image search setup
Create a Programmable Search Engine with image search enabled and enable the Custom Search JSON API in Google Cloud. Add to your local untracked `local.properties`:
```properties
GOOGLE_CSE_KEY=your_api_key
GOOGLE_CSE_ID=your_search_engine_id
```
Never commit this file. Image search integration is implemented as a client but is not yet connected to catalogue UI, image selection or persistent image caching. No claim is made that live search has been tested; credentials and API quota are required.

## Build
Use JDK 17, Android SDK platform 35 and Gradle 8.9:
```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```
GitHub Actions publishes the `gc-debug-apk` artifact after a successful build.

## CSV columns
A product description column is required. Supported aliases include EAN/Barcode, Article/Article Code, Product Name/Article Description, Brand, MRP/Price and Qty/Quantity.
