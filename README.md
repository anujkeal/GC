# GC — Smart Product Photo Finder

Native Android stock catalogue built with Kotlin, Jetpack Compose and Room.

## Automatic product photo search
GC uses the Python `ddgs` package through a small hosted service. The Android app connects automatically: **no API key, no API settings, and no server URL to paste**.

- Searches DuckDuckGo Images through DDGS.
- Searches EAN, article number, normalized product name and Hamleys-specific product queries.
- Ranks matches against product titles and identifiers.
- Returns up to five relevant results; it deliberately returns fewer rather than filling the gallery with unrelated pictures.

DDGS image search is a metasearch interface and cannot guarantee an exact SKU match if search listings do not expose the product identifier. Search engines may rate-limit requests. Render's free web service sleeps after 15 minutes without traffic, so the first request after idle can take about a minute to wake up.

## One-time service deployment
The image-search service is hosted separately because `ddgs` is a Python package, not an Android library. Deploy it once using the button below; after deployment, the Android app calls it automatically.

[![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com/deploy?repo=https://github.com/anujkeal/GC)

After Render finishes:
1. Open the service named `gc-keal-ddgs-api`.
2. Open its URL and confirm `/health` returns `{"status":"ok","engine":"ddgs"}`.
3. Install the latest GC APK. No app settings are required.

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
