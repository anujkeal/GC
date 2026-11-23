# GC — Smart Product Photo Finder

Native Android stock catalogue built with Kotlin, Jetpack Compose and Room.

## Image search engine: DDGS
- The Android app calls a small Python service in `server/`.
- The service uses the official `ddgs` Python package; no Google, Serper, or other search API key is needed.
- DuckDuckGo Images is queried first; DDGS Bing Images is the fallback backend.
- Searches use EAN, article number, normalized product name, and Hamleys-specific queries.
- Results are relevance-ranked and capped at five. The app shows fewer when there are not five credible matches instead of filling slots with unrelated pictures.
- The DDGS backend is a separate web service because `ddgs` is a Python package and cannot be imported directly into the native Kotlin Android process.

## Deploy the image service once
1. Open the repository on Render and choose **New → Blueprint**.
2. Select `anujkeal/GC`. Render reads `render.yaml` and builds `server/Dockerfile`.
3. Wait for the service health check at `/health` to pass.
4. The service URL is normally `https://gc-ddgs-api.onrender.com`. If Render assigns a different URL, open **API settings** in GC and paste the actual service URL.
5. Import your stock CSV or tap **Recheck all product photos**.

The free Render service may sleep when idle, so the first search after inactivity can take longer while it wakes up. DDGS uses public search-engine results; exact SKU identity cannot be guaranteed if the listing does not expose the EAN or exact model text.

## Run the server locally
```bash
cd server
python -m venv .venv
# Activate the virtual environment, then:
pip install -r requirements.txt
uvicorn app:app --host 0.0.0.0 --port 8000
```
Health check: `http://localhost:8000/health`

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
