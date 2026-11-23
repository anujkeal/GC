# GC — Smart Product Photo Finder

Native Android stock catalogue built with Kotlin, Jetpack Compose and Room.

## Product catalogue
- Imports stock CSV files and preserves EAN/article identifiers as text.
- Recognises stock headers including Article, EAN, Short Text, MRP, IST TO and Qty.
- Searches Google Images automatically through the Serper Images API after import.
- Fetches up to five image results per product and displays them together; no manual image approval is required.
- Stores image result URLs in the local Room catalogue so results remain associated with each product.
- Allows retrying photo search for products without results.
- API key is entered once in the app's API settings and stored in private app preferences; it is not embedded in the APK or committed to this repository.

## Google Images API setup
1. Create an account at https://serper.dev/.
2. Copy the API key from the Serper dashboard.
3. Open GC → API settings, paste the key, and choose **Save and find photos**.
4. Import your stock CSV. GC will search and show up to five images per product.

Image results are search candidates, not guaranteed verified product matches. Use EAN-based search first and compare the visible product packaging/model before relying on a photo. API usage and limits are governed by Serper.

## Build
Requirements: JDK 17, Android SDK platform 35 and Gradle 8.9.
```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```
GitHub Actions runs tests and uploads the debug APK as `gc-debug-apk`.

## CSV columns
Supported aliases include EAN/Barcode, Article/Article Code, Short Text/Product Name/Article Description, Brand, MRP/Price and Qty/Quantity.
