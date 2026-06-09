# HealthBridge — Product Requirements Document
**Version:** 1.0  
**Status:** Ready for Wireframing  
**Last Updated:** June 2026  
**Intended Consumer:** Claude Code (wireframing + development)

---

## CLAUDE CODE INSTRUCTIONS

This PRD is your complete brief. Build wireframes first — all screens listed in Section 2 — before writing any logic. The app is **Android-only**. The user manually imports an Apple Health export ZIP, the app parses it, deduplicates against a local SQLite fingerprint database, and writes only new records to Google Health Connect. No backend. No accounts. No cloud sync. Everything runs on-device.

Design language: clean, dark-mode first, medical/data aesthetic. Think linear gradients of deep navy to near-black, with a single electric teal accent (`#00D4B8`). Typography: monospace for data values, sans-serif for labels. No gradients on buttons — flat, high contrast. The app should feel like a precision instrument, not a wellness app.

---

## 1. Executive Summary

### Problem Statement
Users who own both iPhone and Android devices have no reliable, privacy-preserving way to mirror their Apple Health data to Google Health Connect. Existing solutions either require paid subscriptions, are unreliable with complex health data types (GPS routes, HRV intervals), or lock data in proprietary cloud systems the user does not control.

### Proposed Solution
HealthBridge is an Android-only, fully offline application. The user exports their Apple Health data as a ZIP from their iPhone (a native iOS feature requiring no third-party app), transfers the ZIP to their Android device, and HealthBridge handles the rest: parsing, deduplication, and writing to Health Connect. No accounts. No internet required. No recurring cost.

### Success Criteria
1. **Deduplication accuracy:** 0 duplicate records written to Health Connect across 5 consecutive imports of the same dataset.
2. **Parse speed:** Full 5-year Apple Health export (avg ~200MB XML) parsed and fingerprinted in under 90 seconds on a mid-range Android device (Snapdragon 6-series equivalent).
3. **Data fidelity:** Workout type, start time, end time, duration, calorie data, heart rate samples, and GPS routes preserved with 100% field accuracy after conversion.
4. **Supported data types on launch:** Workouts, Steps, Heart Rate, Sleep, Active Energy, Resting Heart Rate, VO2 Max.
5. **User action count per sync:** User should complete a full import in 4 taps or fewer after onboarding.

---

## 2. User Experience & Functionality

### User Persona
**Aryan — The Cross-Platform Athlete**  
Records all workouts on iPhone (Apple Watch or iPhone native). Uses Android as primary daily driver. Wants health data visible in Google Fit, Garmin Connect, or any app that reads from Health Connect. Technically literate but does not want to manage servers or write code. Syncs manually — weekly or after a training block.

---

### Screen Inventory (Wireframe All of These)

#### Screen 1: Onboarding (3 slides, one-time)
- Slide 1: "Your health data, your device." — Explain the flow in one diagram: iPhone export → HealthBridge → Health Connect.
- Slide 2: How to export from iPhone (step-by-step with icons: Health app → Profile → Export All Health Data).
- Slide 3: Grant Health Connect permissions — a single button triggers the Android permission flow for all required data types.

#### Screen 2: Home / Dashboard
**Components:**
- Last sync banner: "Last synced: 14 June 2026 — 847 new records written"
- If never synced: "No syncs yet. Import your first export to get started."
- Summary cards (post-sync only): Workouts synced / Steps synced / Sleep records synced — shown as counts, not charts.
- Primary CTA button: "Import Apple Health Export" (full-width, teal, bottom of screen).
- Secondary link: "View Sync History"

#### Screen 3: File Import Screen
- Full-screen file picker trigger.
- Accepts: `.zip` files only.
- After file selected: show filename, file size, and date modified.
- Validation state: if file is not a valid Apple Health export (checked by presence of `export.xml` inside ZIP), show inline error: "This doesn't look like an Apple Health export. Export from: iPhone Health app → Profile icon → Export All Health Data."
- CTA: "Start Processing"

#### Screen 4: Processing Screen
**This screen has three sequential stages, shown as a stepped progress indicator:**

Stage 1 — Unpacking  
`Unpacking export.xml from ZIP...`  
Progress: file extraction progress bar.

Stage 2 — Parsing  
`Reading health records...`  
Live counter: "124,847 records scanned" (updates every 500ms).  
Sub-label: current data type being parsed (e.g. "Heart Rate samples...").

Stage 3 — Deduplication  
`Checking against 43,291 known records...`  
Result preview appears inline as it resolves:  
- Workouts: 12 new / 847 already synced  
- Steps: 34 new / 12,400 already synced  
- Heart Rate: 203 new / 89,234 already synced  
- (etc. for all supported types)

No cancel button during Stage 2 and 3 (processing is fast enough). Show a cancel option only during Stage 1 (file I/O).

#### Screen 5: Delta Review Screen (Most Important Screen)
This is the confirmation screen before anything is written to Health Connect.

**Header:** "Ready to sync [X] new records"  
**Subheader:** "[Y] records already on this device — skipped"

**Data type breakdown list (each row):**
- Icon + Type name (e.g. 🏃 Workouts)
- Count of new records
- Date range of new records (e.g. "Jun 1 – Jun 14")
- Chevron → tappable to expand into a list of individual items

**Expandable workout list (on tap):**  
Each workout shows: type, date, duration, calories. No editing. Read-only preview only.

**Bottom section:**
- "Nothing new to sync" empty state if delta = 0 (with message: "All records from this export are already on your device.")
- Primary CTA: "Write to Health Connect" (teal, full-width)
- Secondary: "Cancel" (text link, no button style)

#### Screen 6: Writing Screen
- Simple progress bar: "Writing 1,247 records to Health Connect..."
- Live counter of records written.
- On completion: success state with checkmark animation.
- Summary: "Sync complete. 1,247 records added."
- CTA: "Done" → returns to Home.
- Error state (if Health Connect write fails mid-way): show count of records successfully written vs failed, with a retry option for the failed batch only.

#### Screen 7: Sync History Log
- Chronological list of all past syncs.
- Each row: date/time of sync, records written (by type), source file name.
- Tap to expand: full breakdown by data type.
- No delete option in MVP. (Reason: deleting history doesn't affect Health Connect data — it only affects the fingerprint DB. Exposing this creates confusion.)

#### Screen 8: Settings
- **Health Connect Permissions:** Shows current permission status per data type. Button to re-trigger permission grant.
- **Supported Data Types:** Toggle list — user can turn off data types they don't want synced (e.g. turn off Sleep if they track sleep separately). Default: all on.
- **Fingerprint Database:** Shows record count and storage size. Single destructive action: "Reset sync history" — with a modal: "This will not delete any data from Health Connect. It will cause all records to be re-evaluated on your next import, which may result in duplicates if records already exist in Health Connect. Are you sure?" — Two buttons: "Reset" (red) / "Cancel".
- **App version + open source notice.**

---

### User Stories

| # | Story | Acceptance Criteria |
|---|---|---|
| US-01 | As a user, I want to import my Apple Health ZIP so my workout history appears on Android. | ZIP parsed without crash; all supported data types extracted; delta written to Health Connect; success screen shown. |
| US-02 | As a user, I want to re-import the same ZIP without creating duplicates. | 0 new records written on second import of identical ZIP. Dedup screen shows "already synced" for all records. |
| US-03 | As a user, I want to see exactly what will be written before it happens. | Delta Review screen shows per-type counts and date ranges. No write occurs until "Write to Health Connect" is tapped. |
| US-04 | As a user, I want to exclude certain data types from syncing. | Toggling off a type in Settings means it is skipped entirely at the parsing stage (not parsed, not fingerprinted, not written). |
| US-05 | As a user, I want a log of all past syncs. | Sync History screen shows all past sessions with timestamps, record counts, and source file names. |
| US-06 | As a user, I want the app to tell me if my file is not a valid Apple Health export. | Validation runs immediately after file selection. Error shown inline before processing begins. |

---

### Non-Goals (Do Not Build in MVP)
- ❌ Android → Apple Health sync (out of scope permanently for this version).
- ❌ Cloud backup or remote sync.
- ❌ Automatic/background sync triggered by file detection.
- ❌ Account creation or login of any kind.
- ❌ Editing or correcting individual health records within the app.
- ❌ Support for Apple Health data types not listed in success criteria (e.g. nutrition logs, medications, menstrual data).
- ❌ iOS companion app.
- ❌ Widgets or Health Connect read/display features (the app writes only — it is not a health dashboard).

---

## 3. Technical Specifications

### Architecture Overview

```
[User's .zip file]
        │
        ▼
┌─────────────────────┐
│   ZipExtractor      │  Extracts export.xml from ZIP into temp storage.
│   (Kotlin stdlib)   │  Validates presence of export.xml before proceeding.
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│   XmlStreamParser   │  SAX-based streaming parser (NOT DOM — file is too large).
│   (Android XmlPull) │  Reads <Record> and <Workout> nodes.
│                     │  Extracts: type, startDate, endDate, sourceName,
│                     │  sourceVersion, value, unit, device.
│                     │  For workouts: also extracts WorkoutRoute (GPX points)
│                     │  and WorkoutStatistics sub-elements.
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│  FingerprintEngine  │  For each parsed record, generates a SHA-256 hash of:
│                     │  type + startDate + endDate + sourceName
│                     │  (Note: value/calories deliberately excluded from hash
│                     │   — edits are ignored per design decision.)
│                     │  Checks hash against SQLite FingerprintDB.
│                     │  Outputs: List<NewRecord> (only unhashed records).
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│  DataTypeFilter     │  Drops records whose type is toggled off in Settings.
│                     │  Drops unsupported data types silently (no error).
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│  HealthConnectMapper│  Converts Apple Health type strings to Health Connect
│                     │  ExerciseSessionRecord, StepsRecord, HeartRateRecord,
│                     │  SleepSessionRecord, ActiveCaloriesBurnedRecord,
│                     │  RestingHeartRateRecord, Vo2MaxRecord.
│                     │  Apple type → Health Connect type mapping table
│                     │  defined as a sealed class (extensible for v2 types).
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│  HealthConnectWriter│  Batches records into groups of 500 (Health Connect
│                     │  batch insert limit). Writes each batch sequentially.
│                     │  On success: stores fingerprints in SQLite DB.
│                     │  On partial failure: marks failed batch, allows retry.
└────────┬────────────┘
         │
         ▼
┌─────────────────────┐
│  SyncLogger         │  Writes sync session record to SQLite SyncLog table:
│                     │  timestamp, source_file, records_written (by type),
│                     │  records_skipped, duration_ms.
└─────────────────────┘
```

---

### Data Layer — SQLite Schema

```sql
-- Fingerprint deduplication table
CREATE TABLE fingerprints (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    hash          TEXT NOT NULL UNIQUE,       -- SHA-256 of type+startDate+endDate+source
    data_type     TEXT NOT NULL,              -- e.g. "HKQuantityTypeIdentifierStepCount"
    record_date   TEXT NOT NULL,              -- ISO8601 startDate (for display/debug only)
    synced_at     INTEGER NOT NULL            -- Unix timestamp of when it was written
);

CREATE INDEX idx_fingerprints_hash ON fingerprints(hash);

-- Sync session log
CREATE TABLE sync_log (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    synced_at       INTEGER NOT NULL,         -- Unix timestamp
    source_filename TEXT NOT NULL,
    duration_ms     INTEGER NOT NULL,
    records_written TEXT NOT NULL,            -- JSON: {"Workout": 12, "Steps": 34, ...}
    records_skipped INTEGER NOT NULL,
    status          TEXT NOT NULL             -- "success" | "partial" | "failed"
);
```

---

### Apple Health XML → Health Connect Type Mapping

| Apple Health Type | Health Connect Record Type |
|---|---|
| `HKWorkoutActivityType*` | `ExerciseSessionRecord` |
| `HKQuantityTypeIdentifierStepCount` | `StepsRecord` |
| `HKQuantityTypeIdentifierHeartRate` | `HeartRateRecord` |
| `HKCategoryTypeIdentifierSleepAnalysis` | `SleepSessionRecord` |
| `HKQuantityTypeIdentifierActiveEnergyBurned` | `ActiveCaloriesBurnedRecord` |
| `HKQuantityTypeIdentifierRestingHeartRate` | `RestingHeartRateRecord` |
| `HKQuantityTypeIdentifierVO2Max` | `Vo2MaxRecord` |

Workout subtype mapping (Apple → Health Connect `ExerciseType`):

| Apple Workout Type | Health Connect ExerciseType |
|---|---|
| `HKWorkoutActivityTypeRunning` | `RUNNING` |
| `HKWorkoutActivityTypeCycling` | `BIKING` |
| `HKWorkoutActivityTypeSwimming` | `SWIMMING_OPEN_WATER` |
| `HKWorkoutActivityTypeWalking` | `WALKING` |
| `HKWorkoutActivityTypeYoga` | `YOGA` |
| `HKWorkoutActivityTypeHighIntensityIntervalTraining` | `HIGH_INTENSITY_INTERVAL_TRAINING` |
| `HKWorkoutActivityTypeStrengthTraining` | `STRENGTH_TRAINING` |
| All others | `OTHER_WORKOUT` |

---

### Health Connect Permissions Required

Declare all of the following in `AndroidManifest.xml`:

```xml
<!-- Read (needed to check for existing records if ever required) -->
<uses-permission android:name="android.permission.health.READ_EXERCISE"/>
<uses-permission android:name="android.permission.health.READ_STEPS"/>
<uses-permission android:name="android.permission.health.READ_HEART_RATE"/>
<uses-permission android:name="android.permission.health.READ_SLEEP"/>
<uses-permission android:name="android.permission.health.READ_ACTIVE_CALORIES_BURNED"/>

<!-- Write (primary requirement) -->
<uses-permission android:name="android.permission.health.WRITE_EXERCISE"/>
<uses-permission android:name="android.permission.health.WRITE_STEPS"/>
<uses-permission android:name="android.permission.health.WRITE_HEART_RATE"/>
<uses-permission android:name="android.permission.health.WRITE_SLEEP"/>
<uses-permission android:name="android.permission.health.WRITE_ACTIVE_CALORIES_BURNED"/>
<uses-permission android:name="android.permission.health.WRITE_RESTING_HEART_RATE"/>
<uses-permission android:name="android.permission.health.WRITE_VO2_MAX"/>
```

---

### Technology Stack

| Layer | Choice | Reason |
|---|---|---|
| Language | Kotlin | Required for Health Connect SDK |
| UI Framework | Jetpack Compose | Modern Android, no XML layouts |
| Navigation | Compose Navigation | Single-activity architecture |
| Local DB | Room (SQLite wrapper) | Type-safe queries, Migration support |
| XML Parsing | XmlPullParser (Android built-in) | Streaming — handles 200MB+ files without OOM |
| Hashing | Java `MessageDigest` SHA-256 | Built-in, no dependency |
| Health Connect | `androidx.health.connect:connect-client` | Official SDK |
| Coroutines | Kotlinx Coroutines | Async parsing + write operations |
| File Access | Android Storage Access Framework | Required for ZIP file picker |

---

### Security & Privacy

- **No network calls.** The app has no internet permission declared. All processing is on-device.
- **No analytics.** No crash reporting SDK, no telemetry.
- **Temp file cleanup:** Extracted XML is written to `cacheDir` and deleted immediately after parsing completes (success or failure).
- **SQLite encryption:** Not required for MVP (fingerprint hashes contain no health data — only type + date strings). Note in settings that the database contains no personal health values.
- **Health Connect data governance:** All data written via Health Connect is subject to Android's Health Connect privacy policy. App must include a privacy policy URL in Play Store listing to pass health data policy review (required for apps using Health Connect write permissions).

---

## 4. Risks & Roadmap

### Technical Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Apple changes XML export schema | Low | High | Parser uses type-string matching, not positional parsing. Schema changes add new types but don't break existing ones. |
| Health Connect batch write limit causes silent failures | Medium | High | Batch in groups of 500. Verify each batch response before storing fingerprints. Never store fingerprint unless write confirmed. |
| XmlPullParser runs out of memory on very large exports | Low | High | Use SAX streaming — never load full XML into memory. Process and discard nodes one at a time. |
| Health Connect Play Store policy review rejection | Medium | Medium | Include privacy policy URL. Make app purpose explicit in store listing. App does not "collect" data — it mirrors from user's own export. |
| GPS route data (WorkoutRoute) parsing complexity | High | Low | Defer GPS/route data to v1.1. Log a warning in the sync session if route data is found but not written. |

---

### Phased Roadmap

#### MVP (Build This First)
- All 8 screens wireframed and functional.
- Supported data types: Workouts (no GPS), Steps, Heart Rate, Sleep, Active Energy, Resting Heart Rate, VO2 Max.
- Manual file import via Storage Access Framework.
- Fingerprint deduplication with SQLite.
- Sync history log.
- Settings: permission management + data type toggles + fingerprint DB reset.

#### v1.1
- GPS route data support for workouts (`WorkoutRoute` → `ExerciseRoute` in Health Connect).
- Heart rate interval samples within workouts (currently Health Connect stores these as `ExerciseSegment` — mapping is non-trivial).
- Export formats: allow user to export sync log as CSV for their own records.

#### v2.0
- Automated import trigger: watch a specific folder (e.g. Google Drive folder) for new ZIP files and prompt user when detected — without running in background.
- Additional data types: Blood Oxygen, Blood Pressure, Body Weight, Body Fat Percentage.
- Multi-source support: allow import from Garmin Connect export or Fitbit export format (separate parsers, same dedup + write pipeline).

---

## 5. File Structure for Claude Code

```
healthbridge-android/
├── app/
│   ├── src/main/
│   │   ├── java/com/healthbridge/
│   │   │   ├── ui/
│   │   │   │   ├── onboarding/       # OnboardingScreen.kt
│   │   │   │   ├── home/             # HomeScreen.kt
│   │   │   │   ├── import/           # ImportScreen.kt, ProcessingScreen.kt
│   │   │   │   ├── delta/            # DeltaReviewScreen.kt
│   │   │   │   ├── writing/          # WritingScreen.kt
│   │   │   │   ├── history/          # SyncHistoryScreen.kt
│   │   │   │   ├── settings/         # SettingsScreen.kt
│   │   │   │   └── theme/            # Theme.kt, Color.kt, Type.kt
│   │   │   ├── parser/
│   │   │   │   ├── AppleHealthParser.kt
│   │   │   │   ├── AppleHealthRecord.kt  # Data classes
│   │   │   │   └── WorkoutTypeMapper.kt
│   │   │   ├── fingerprint/
│   │   │   │   ├── FingerprintEngine.kt
│   │   │   │   └── FingerprintDatabase.kt  # Room DB
│   │   │   ├── healthconnect/
│   │   │   │   ├── HealthConnectManager.kt
│   │   │   │   ├── HealthConnectMapper.kt
│   │   │   │   └── HealthConnectWriter.kt
│   │   │   ├── sync/
│   │   │   │   ├── SyncEngine.kt      # Orchestrates full pipeline
│   │   │   │   └── SyncLogger.kt
│   │   │   └── MainActivity.kt
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle
└── README.md
```

---

## 6. Design Tokens for Claude Code

```kotlin
// Color.kt
val TealAccent = Color(0xFF00D4B8)
val BackgroundDark = Color(0xFF0A0E1A)
val SurfaceDark = Color(0xFF131929)
val SurfaceElevated = Color(0xFF1C2437)
val TextPrimary = Color(0xFFE8EDF5)
val TextSecondary = Color(0xFF8A96B0)
val ErrorRed = Color(0xFFFF4D4D)
val SuccessGreen = Color(0xFF00D4B8)  // same as teal — keep palette tight
val DividerColor = Color(0xFF232B3E)

// Typography roles
// Display/headers: Inter or Roboto, weight 600, letter-spacing -0.5
// Body: Roboto, weight 400
// Data values (counts, timestamps): Roboto Mono, weight 500
// Labels/captions: Roboto, weight 400, TextSecondary color

// Spacing scale (dp)
// 4, 8, 12, 16, 24, 32, 48

// Corner radius
// Cards: 12dp
// Buttons: 8dp
// Chips/tags: 4dp

// Primary button style
// Background: TealAccent, text: BackgroundDark (dark text on teal)
// Full width, height 56dp, corner 8dp

// No shadows. Elevation expressed through background color difference only.
```

---

*End of PRD. Claude Code: start with Screen 2 (Home) and Screen 5 (Delta Review) — these are the two screens that communicate the core value proposition and will validate the design language before the others are built.*
