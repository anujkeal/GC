# GC — Smart Product Photo Finder

Native Android stock catalogue built with Kotlin, Jetpack Compose and Room.

## Product photo search
- Searches DuckDuckGo Images directly from the Android app.
- No API key, third-party account, separate backend, or server deployment is required.
- Searches product descriptions and exact EAN/article identifiers.
- Expands common stock abbreviations such as R/C, TRCK, MNSTR and HIGHSPD.
- Ranks results by product-title relevance and shows up to five matches.
- If fewer than five relevant results exist, the app shows fewer rather than filling the gallery with unrelated images.

DuckDuckGo's image-search web endpoint is undocumented and may rate-limit or change. It does not guarantee an exact SKU match unless the result title or source page contains enough identifying product information.

## CSV import
- Preserves EAN and article identifiers as text.
- Recognises stock headers including Article, EAN, Short Text, MRP, IST TO and Qty.

## Android build
Requirements: JDK 17, Android SDK platform 35 and Gradle 8.9.
```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```
GitHub Actions runs tests and uploads the debug APK as `gc-debug-apk`.
