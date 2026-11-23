# GC — Smart Product Photo Finder

Native Android stock catalogue built with Kotlin, Jetpack Compose and Room.

## Product photo matching
- No API key or separate server.
- Searches product-name, EAN and article-code queries across Hamleys India, Hamleys, FirstCry, Toys R Us India, Amazon India and general Bing Images results.
- Expands common stock abbreviations (for example R/C, TRCK, MNSTR, HIGHSPD and AMPHI).
- Filters results by product-title token overlap and prioritises Hamleys product pages.
- Displays up to five relevant candidate images; if the search cannot find five convincing matches, it shows fewer rather than filling the row with unrelated pictures.
- **Recheck all product photos** clears previously stored mismatched results and runs the improved search again.

Search-engine results cannot guarantee an exact SKU match based only on a stock description. The app uses strict title relevance filtering to avoid unrelated photos; exact EAN matches are strongest when retailer listings expose the barcode.

## CSV import
- Preserves EAN and article identifiers as text.
- Recognises stock headers including Article, EAN, Short Text, MRP, IST TO and Qty.

## Build
Requirements: JDK 17, Android SDK platform 35 and Gradle 8.9.
```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```
GitHub Actions runs tests and uploads the debug APK as `gc-debug-apk`.
