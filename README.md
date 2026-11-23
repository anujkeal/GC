# GC — Smart Product Photo Finder

Native Android stock catalogue built with Kotlin, Jetpack Compose and Room.

## Product photo search
- No API key, account, or separate server required.
- After CSV import, searches public Bing Images results using each product's EAN and product name.
- Displays up to five image thumbnails per product automatically; there is no manual approval step.
- Saves image result URLs with the local product record and supports retrying failed searches.
- Results are candidate matches, not a guarantee that every image depicts the exact SKU. Search uses EAN and product text to improve relevance; visually verify packaging/model before using a photo to identify stock.

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
