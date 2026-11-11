# Creative Scan by Kowsiq

Native Android document scanner. Kotlin, Jetpack Compose (Material 3), CameraX, OpenCV 4.9, MVVM.
Package: `com.kowsiq.creativescan` · minSdk 26 (Android 8.0) · targetSdk 34 · fully offline (no INTERNET permission).

> **Build status:** the source was written but **has not been compiled yet**, because the authoring
> environment had no Android SDK and no access to Maven/Google servers. Build it once on your PC
> (steps below). If the build prints errors, send them back and they can be fixed quickly.

## Build on Windows (Android Studio — easiest)

1. Install **Android Studio** (latest stable): https://developer.android.com/studio
   (it installs a JDK 17+ and the Android SDK for you; accept the licences).
2. Unzip `CreativeScan.zip` to a short path, e.g. `C:\CreativeScan`.
3. Android Studio → **Open** → choose the `CreativeScan` folder.
4. Wait for **Gradle sync** to finish (first time downloads ~500 MB; internet needed *only for building*).
   If asked to install an SDK platform (Android 14 / API 34), click *Install*.
5. Menu **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
6. When "APK(s) generated" appears click **locate**. The file is:

   `C:\CreativeScan\app\build\outputs\apk\debug\app-debug.apk`

## Build with the command line (Windows)

Needs JDK 17 and the Android SDK (Android Studio installs both). Open **PowerShell** in the project folder:

```powershell
# one-time: tell Gradle where the SDK is (adjust the path if different)
"sdk.dir=C\:\\Users\\$env:USERNAME\\AppData\\Local\\Android\\Sdk" | Out-File -Encoding ascii local.properties

# if gradlew.bat does not exist yet, create the wrapper (needs Gradle installed) or just open the project in Android Studio once
.\gradlew.bat assembleDebug
```

Output: `app\build\outputs\apk\debug\app-debug.apk`

### Release APK (optional)

1. Create a keystore:
   `keytool -genkeypair -v -keystore creativescan.jks -keyalg RSA -keysize 2048 -validity 10000 -alias creativescan`
2. Create `keystore.properties` in the project root:
   ```
   storeFile=creativescan.jks
   storePassword=YOUR_PASSWORD
   keyAlias=creativescan
   keyPassword=YOUR_PASSWORD
   ```
3. `.\gradlew.bat assembleRelease` → `app\build\outputs\apk\release\app-release.apk`
   (without `keystore.properties` the release APK is unsigned and cannot be installed.)

## Install on an Android phone

1. Copy `app-debug.apk` to the phone (USB cable, Google Drive, Bluetooth…).
2. Open the file on the phone. If Android blocks it, allow **Install unknown apps** for the app you opened it from
   (Settings → Apps → Special access). Play Protect may warn because the APK is not from Play Store.
3. Tap **Install**, then **Open**, and allow the Camera permission.

Or with USB debugging on: `adb install -r app\build\outputs\apk\debug\app-debug.apk`

## Where files are saved

`Documents/Creative Scan/` (PDF and JPEG). Android 10+ uses MediaStore (no storage permission).
Android 8–9 asks for the storage permission once.

## Features

- CameraX preview, live edge detection overlay, guidance messages, auto-capture when steady, manual shutter,
  flashlight, tap-to-focus, focus button, zoom slider, permission handling.
- OpenCV document detection (Canny + Otsu contours, 4-corner quad), perspective correction, auto-straighten. Detection contour/mask Mats and warp point matrices are explicitly released to reduce native-memory growth during long camera sessions.
- Manual 4-corner adjustment with magnifier, auto-detect and full-image buttons.
- Filters: Original Color, Enhanced Color, Grayscale, Black & White, Document Clean, Photo Mode
  (illumination normalisation, CLAHE, white balance, bilateral denoise, adaptive threshold, unsharp mask).
  Brightness / contrast / sharpness, rotate, before/after, revert to original. Original capture is never modified.
- Multi-page: add, retake, delete, reorder, rotate, per-page filter, apply filter to all, thumbnails, page numbers.
- Two-sided ID card copy: capture front and back separately, with a front-side retake option while capturing the back. Each side is detected and perspective-corrected to standard ID-1 dimensions (85.60 × 53.98 mm), then both are centred horizontally and vertically as a pair on one white A4 portrait page (200 dpi). PDF export preserves the exact A4 page size and physical card dimensions; review the composed page before exporting PDF/JPEG.
- PDF export (own writer, JPEG streams): A4 auto portrait/landscape, fit-to-page with margins or native size,
  Standard (150 dpi, q75) / High Quality (up to 300 dpi, q92). Images are never upscaled.
- JPEG export: quality 60–100 (default 95), optional original copy, custom file name.
- Documents: thumbnails, search, sort by date/name, rename, delete, open, share, recent scans.
- Interrupted scans are restored after the app is closed.
- Not included: folders (marked "if practical" in the brief).

## Version 1.1.0 changes

- Added dedicated **ID Card Scan** on the home screen.
- Guided front-side then back-side capture, with an option to retake the front before capturing the back.
- Added a fixed ID-1 aspect-ratio perspective correction path and A4 two-sided card-copy composition.
- PDF writer now places a pre-composed A4 card-copy raster edge-to-edge on an A4 PDF page, avoiding an unintended second margin that would shrink the physical card size.
- Explicitly release OpenCV contour, hierarchy, kernel, polygon, and perspective-transform Mats to reduce native-memory growth during repeated scanning.
- Excluded x86_64 OpenCV binaries from phone APKs; ARM64 and ARM32 phone support remains.

**Build verification:** every push runs two GitHub Actions workflows. *Build APK* runs `gradle assembleDebug` and publishes
`CreativeScan-debug.apk` as a GitHub Release. *Unit tests* runs JVM tests for the ID-card A4 layout (both cards centred, ISO ID-1
size at 200 dpi) and for the PDF writer (valid structure, page order, edge-to-edge A4 card page, rotation). Camera capture,
OpenCV detection and on-device JPEG/PDF export are not covered by automated tests; check them on a real phone.

Portrait-held ID cards are not stretched: they are corrected at their own aspect ratio and then rotated to landscape.

## Project layout

```
app/src/main/java/com/kowsiq/creativescan/
  core/   ImageProcessor (OpenCV), PdfWriter, ImageIO, Models
  data/   DocumentRepository (MediaStore), AppSettings
  ui/     AppViewModel, AppRoot, Common, theme/, screens/ (Home, Camera, Edit, Pages, Documents, Info)
```
