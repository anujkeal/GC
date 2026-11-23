# GC — Smart Product Photo Finder

GC is a Kotlin/Jetpack Compose Android inventory catalogue backed by a Python FastAPI product-image matching service.

## Product image matching architecture

1. **Normalizer** — `server/normalizer.py` plus editable `abbreviations.json` and `brands.json`. Expands stock shorthand, extracts scale/color/size, and preserves exact model/SKU/EAN/article codes. Unknown compressed abbreviations are flagged; an optional OpenAI-compatible normalizer can be enabled with environment variables.
2. **Free multi-source discovery** — `server/search.py` searches exact EAN, exact article + brand, Hamleys product pages, DDGS Images and Bing via icrawler. It also checks Open Food Facts by exact barcode and Wikimedia Commons for supplementary open-image candidates. Each source is isolated so an outage does not stop the remaining search tiers.
3. **Optional Google Lens enrichment** — if `GOOGLE_LENS_BASE_URL` points to a separately running Google Lens Scraper service, GC sends one metadata-supported seed image per product to its `/search?image=...` endpoint and adds visual matches as candidates. Lens is optional: GC continues to work without it.
4. **Verifier** — `server/verifier.py` scores identifier evidence (+40), key-token coverage (+30), trusted domain (+15), and conflicting attributes (-30). It rejects low-resolution images, known stock-watermark sources and OCR-detected stock marks, and perceptual duplicates. It vision-checks up to eight finalists when `OPENAI_API_KEY` is configured.
5. **Confidence** — 80+ with positive vision confirmation is auto-accepted; 50–79 or no vision confirmation is staff review; below 50 is rejected. Without a vision key, candidates are never mislabeled as verified.
6. **Cache/review** — `server/cache.py` stores SQLite jobs, image decisions, rejection logs and the manual review queue. Staff-approved photos have the highest trust and are not overwritten by automatic search.
7. **API** — `server/main.py` provides CSV/XLSX job upload, progress/results, review queue, staff approval and rejection diagnostics. The legacy Android search route remains available.

### Free-source notes

- Open Food Facts is used only when the returned barcode exactly matches the CSV EAN. Its catalogue is strongest for food and grocery items.
- Wikimedia Commons is a supplementary source; a Commons match is not proof that the image is the exact retail SKU.
- DDGS and Bing icrawler are public-web discovery fallbacks. Their availability and rate limits are controlled by their respective services.
- Google Lens Scraper is MIT-licensed open-source software, but it is a separate browser service. It requires its own host with Camoufox/Firefox dependencies and may encounter Google CAPTCHA or IP throttling. Do not assume the free Render web service has enough memory or browser support to host it alongside GC.
- No paid search API is required for the default pipeline. Hosting, bandwidth and compute can still have costs or free-tier limits.

### Enable Google Lens (optional)

1. Deploy [omkarcloud/google-lens-scraper](https://github.com/omkarcloud/google-lens-scraper) separately using its own installation instructions.
2. Make its API reachable from the GC backend.
3. Set `GOOGLE_LENS_BASE_URL` on the GC service to the base URL, for example `http://your-lens-service:8000` (do not include `/search`).
4. GC will automatically call `/search?image=<seed-image-url>` once per product when it has a candidate with sufficient metadata support.

If the variable is unset, no Lens request is made. Google Lens results still pass through GC's normal metadata, image-quality, duplicate and confidence checks.

## Render deployment

The Android client uses the fixed service hostname `https://gc-keal-ddgs-api.onrender.com`. Deploy the repository Blueprint once:

[![Deploy to Render](https://render.com/images/deploy-to-render-button.svg)](https://render.com/deploy?repo=https://github.com/anujkeal/GC)

The Render service is `gc-keal-ddgs-api`; its health endpoint is `/health`. Free instances sleep when idle, so the app's HTTP client allows a long cold-start window.

### Optional vision verification

Set these Render environment variables to enable final visual identity checks:
- `OPENAI_API_KEY` — secret key for an OpenAI-compatible vision endpoint.
- `OPENAI_VISION_MODEL` — defaults to `gpt-4o-mini`.
- `OPENAI_BASE_URL` — defaults to `https://api.openai.com/v1`.

Without a vision key, matching candidates remain in the manual-review queue; they are not presented as verified. No key is required to run search, scoring, image-quality checks or staff review.

### Storage note

The SQLite cache is stored under `/app/data/gc.sqlite3`. Render's free filesystem is ephemeral; use a persistent Render disk or external PostgreSQL if cache retention across instance replacement/redeploy is required.

## API

- `POST /jobs` — multipart `file` (CSV/XLSX), optional `force_research`; returns `job_id`.
- `GET /jobs/{job_id}` — status, done/total, results.
- `GET /review-queue` — products needing staff review.
- `PUT /products/{id}/images` — staff approval/replacement. Configure `STAFF_API_KEY` in Render to enable staff approvals and forced re-search; send it as `X-Staff-Key`.
- `GET /products/{id}/rejections` — candidate rejection reasons.
- `GET /health` — health check.

## Tests and Android build

```bash
python -m pip install -r server/requirements.txt
PYTHONPATH=server pytest -q server/tests
PYTHONPATH=server python -m compileall -q server
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```
