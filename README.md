# PhotoSorter (Android)

Three tools in one app:

1. **Trips - group by trip** — organizes the gallery into per-trip folders like `japan-trip`,
   `swiss-trip`. How it works:

   1. reads the library (newest first) and each photo's EXIF GPS location,
   2. **the AI scans every photo** — image + date + GPS coordinates go to the model, which returns
      a folder like `japan-trip` (Tokyo and Kyoto photos both land there) or a category for
      non-travel photos (`food`, `screenshots`, ...). Slightly different answers are normalized
      into one folder ("japan-trip" / "japan" → same group),
   3. you review the groups, then press **Move** or **Copy**:
      - *Copy* — files a copy into `Pictures/PhotoSorter/<folder>/`, originals stay put.
      - *Move* — copies first, **verifies the copy's size**, then deletes the original; requires a
        free-space check and a second confirmation press. A failed delete can never lose data
        (worst case: a duplicate remains). On Android 14/15 your phone may confirm each deletion.

   Already-filed photos are skipped (shared index with tab 2), so you can run the scan in batches
   across days to stay within API quotas. On-device mode can only sort by content categories —
   use cloud mode for real place names.

2. **Sort & describe** — for the newest N photos it produces a **folder name** and a
   **one-line description**, then **copies** the photo into `Pictures/PhotoSorter/<folder>/` and
   saves the description three ways:

   1. a `<name>.description.txt` side file next to the copy (works everywhere),
   2. the copy's EXIF `UserComment` tag (best-effort),
   3. an in-app index (so re-runs skip already-filed photos and don't burn API quota).

   Originals are **never modified or moved** — Android scoped storage blocks apps from
   reorganizing other apps' shared files, and copying is safer than moving.

3. **Duplicates - save storage** — scans the *entire* library on-device (no cloud, no key):
   a byte-exact pass (SHA-256) plus a perceptual-hash pass that catches re-saves, crops and
   re-edits. Groups are shown with per-photo keep/delete switches; deleting goes through
   `MediaStore` and Android 14/15 additionally shows its own system confirmations.
   Nothing is deleted until you press the button twice.

## AI modes

| Mode | Quality | Cost | Privacy | Needs |
|---|---|---|---|---|
| Cloud: Gemini | Best captions, can web-verify landmarks/products | per request (free tier is tight) | images sent to the model | API key + internet |
| On-device VLM: Qwen2.5-VL-3B via llama.cpp | real place/trip identification fully offline (~10-30 s/photo) | free, one-time ~3.3 GB download | all on the phone | model files in `Android/data/com.example.photosorter/files/llm/` (in-app button or adb push) |
| On-device labels: MediaPipe EfficientNet-Lite0 | content categories only (food, animals...) - no place names | free, offline, repeatable | all on the phone | `labeler.tflite` in assets (bundled) |

### On-device VLM setup (one-time, ~3.3 GB)

The Trips tab has a **Download local AI model** button. Or push the files with adb:

```bash
adb push Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf /sdcard/Android/data/com.example.photosorter/files/llm/
adb push mmproj-F16.gguf                  /sdcard/Android/data/com.example.photosorter/files/llm/
# from https://huggingface.co/unsloth/Qwen2.5-VL-3B-Instruct-GGUF
```

The VLM is compiled into the app from `native/llama.cpp` (pinned commit) via NDK - arm64-v8a only.
It reads each photo's pixels + EXIF date/GPS and answers with a folder name like `japan-trip`;
it can also read text in photos (signs, headlines), which helps a lot with place identification.
Expect ~10-30 s per photo on a modern phone; the scan runs one photo at a time.

The older labeler (`labeler.tflite`, bundled) remains as the automatic fallback when the VLM model
files are missing - it only produces content categories, not place names.

## Requirements

- Android 8.0+ (API 26); tested against API 34 (Android 14/15).
- [Android Studio](https://developer.android.com/studio) (Hedgehog or newer). Java 17 is bundled.
- A free Gemini API key for cloud mode: https://aistudio.google.com/app/apikey

## Setup

1. Open this folder in Android Studio (File → Open → pick the `photosorter` folder).
2. If it complains the Gradle wrapper is missing: run `gradle wrapper --gradle-version 8.9` once in
   the project root (any Gradle install), or copy `gradlew`, `gradlew.bat` and `gradle/wrapper/`
   from any recent Android project into this folder.
3. For on-device mode: download `labeler.tflite` into `app/src/main/assets/` (URL above).
4. Run on a device/emulator.
5. In the app: grant photo access, pick the tab.

## Notes on deletion & permissions

- Deleting uses `ContentResolver.delete()` on MediaStore URIs with the photo permission you grant.
  On Android 14/15 the OS also shows its own "delete this photo?" dialog for third-party apps.
- Trips "Move" mode deletes originals too - but only per-photo, immediately after a size-verified
  copy exists in the target folder, and only after you press the confirm button twice. If a delete
  ever fails, the original is left in place and the error is shown (no data loss, at worst a dup).
- If you granted *partial* photo access ("Select photos"), the app can only see — and clean up —
  the selected subset. Use "Allow access to all photos" for a full cleanup.
- Saved-space estimates come from `MediaStore.SIZE`; if a file's size reads as 0 you'll see
  "size unknown" for that item.

## Cost & quotas (cloud mode)

- Both the Sort and Trips tabs cost ~1 API call per photo (images + EXIF hints in one request).
  The free Gemini tier is limited (~250 requests/day for flash models - check current numbers), so
  use "Max photos" to run the Trips scan in batches; already-filed photos are skipped on re-runs.
- Images are downscaled to ~512 px JPEG before upload to keep cost low;
  web-search grounding adds a bit per call and requires a model that supports it (the app retries
  automatically without the tool if the model or plan rejects it).
- Quotas: Gemini's free tier is limited and has been shrinking (check the current numbers at
  https://ai.google.dev/gemini-api/docs/rate-limits). For big batches use a paid plan or
  on-device mode.

## Privacy

- Your key is kept in plain app SharedPreferences (fine for a personal local app; switch to
  EncryptedSharedPreferences if you distribute the app). On-device mode needs no key at all.
- In cloud mode images go only to the model you specify. In on-device mode nothing leaves the phone.

## Project layout

```
app/src/main/java/com/example/photosorter/
  MainActivity.kt   Compose UI (three tabs: Sort & describe | Trips | Duplicates)
  AppModel.kt       state, permissions, job orchestration (max 3 concurrent), delete flow
  PhotoRepo.kt      MediaStore queries (recent N + whole library with sizes)
  Trips.kt          folder grouping + AI answer normalization ("japan-trip"/"japan" -> one group)
  Gemini.kt         cloud: per-photo vision classification (Sort prompt + Trips prompt), optional google_search
  LocalAi.kt        offline fallback: MediaPipe labeler -> content categories
  VlmAi.kt          offline primary: Qwen2.5-VL via llama.cpp JNI (model files in external dir)
  ImageTools.kt     downscale helpers (base64/bytes for cloud+VLM, small EXIF-rotated for hashing)
  Duplicates.kt     SHA-256 exact pass + 64-bit DCT perceptual pass, clusters, delete
  Organizer.kt      copy/move to Pictures/PhotoSorter/<folder>/, sidecar, EXIF, index, open-copy

app/src/main/cpp/       JNI bridge (vlm_jni.cpp) + CMake wiring
native/llama.cpp        pinned llama.cpp checkout (built for arm64-v8a by the NDK)
```

## Ideas / upgrade paths

- Better offline captions: small VLM (Gemma 3n E4B / LLaVA 7B Q4 GGUF via llama.cpp) behind `LocalAi.kt`.
- Blur / low-quality filter as a third "safe to delete" category; screenshots-only category.
- Move (delete original) via SAF, background batching via WorkManager.
- Secure key storage (EncryptedSharedPreferences), i18n, portrait lock during a run.
