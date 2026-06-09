# HealthBridge — Android

> Mirror an **Apple Health** export into **Google Health Connect**, entirely on-device.
> No backend. No account. No network. The app only **writes** to Health Connect — it is
> not a dashboard.

HealthBridge takes the `export.zip` you get from the iOS Health app, parses it locally,
deduplicates every record against a local fingerprint database, and writes **only the
records that are new** to Health Connect. Run it again next month and it writes just the
delta — never a duplicate.

> [!IMPORTANT]
> **This module is a SCAFFOLD.** The full UI (all 8 screens), navigation, theme, Room
> schema, package architecture, and public APIs are in place and compile. The *engine
> internals* — XML parsing, fingerprint hashing/dedup, and the batched Health Connect
> write — are stubbed with clearly-marked `// TODO` / `TODO("...")`. See
> [Scaffold status](#scaffold-status).

The interactive, fully-clickable design source of truth lives in the sibling
[`../web-prototype`](../web-prototype) — open its `index.html` to walk every screen and
state before touching the native code.

---

## The on-device pipeline

```
 ┌─────────────┐   ┌─────────┐   ┌──────────────────┐   ┌────────────────────┐
 │  export.zip │ → │  PARSE  │ → │ FINGERPRINT DEDUP │ → │  HEALTH CONNECT     │
 │ (Apple)     │   │  (XML)  │   │  (Room / SHA-256) │   │  WRITE (new only)   │
 └─────────────┘   └─────────┘   └──────────────────┘   └────────────────────┘
        │               │                  │                        │
   user-picked      unzip into        SHA-256 of            batched insert
   via SAF          cacheDir,         (type+start+end+      (groups of 500);
                    stream-parse,     source); skip any     fingerprints stored
                    delete XML        hash already in DB     ONLY after a batch
                    after parse                              write is confirmed
```

1. **Import** — the user picks the Apple Health `export.zip` through the system file
   picker (Storage Access Framework). Nothing leaves the device.
2. **Parse** — the ZIP is extracted into `cacheDir`, the `export.xml` is stream-parsed
   into `AppleHealthRecord`s, and **the extracted XML is deleted as soon as parsing
   finishes** (see [Privacy & security](#privacy--security)).
3. **Fingerprint dedup** — each record is reduced to a **SHA-256 fingerprint** of
   `type + startDate + endDate + sourceName`. `value`/calories are **deliberately
   excluded** from the hash, so edited values are treated as the same record (edits are
   ignored by design). Hashes already present in the local Room DB are skipped; the rest
   form the **delta**.
4. **Delta review** — the user sees a per-type breakdown (`new` vs `skipped`) and
   confirms the write.
5. **Health Connect write** — new records are mapped to Health Connect record types and
   inserted in **batches of 500** (the HC insert limit). A fingerprint is persisted
   **only after its batch write is confirmed**; a partial failure marks the failed batch
   and allows retry, so a crash mid-sync can never desync the fingerprint DB from what was
   actually written.
6. **History** — every run is recorded in a local sync log (filename, duration, counts,
   status).

---

## Supported data types (7 MVP)

| HealthBridge type    | Apple HealthKit identifier                   | Health Connect record           |
|----------------------|----------------------------------------------|---------------------------------|
| `WORKOUT`            | `HKWorkoutActivityType`*                     | `ExerciseSessionRecord`         |
| `STEPS`              | `HKQuantityTypeIdentifierStepCount`          | `StepsRecord`                   |
| `HEART_RATE`         | `HKQuantityTypeIdentifierHeartRate`          | `HeartRateRecord`               |
| `SLEEP`              | `HKCategoryTypeIdentifierSleepAnalysis`      | `SleepSessionRecord`            |
| `ACTIVE_ENERGY`      | `HKQuantityTypeIdentifierActiveEnergyBurned` | `ActiveCaloriesBurnedRecord`    |
| `RESTING_HEART_RATE` | `HKQuantityTypeIdentifierRestingHeartRate`   | `RestingHeartRateRecord`        |
| `VO2_MAX`            | `HKQuantityTypeIdentifierVO2Max`             | `Vo2MaxRecord`                  |

\* **Workout subtype mapping** (`AppleHealthRecord` → `ExerciseSessionRecord.exerciseType`):

| Apple activity                    | Health Connect exercise type           |
|-----------------------------------|----------------------------------------|
| `Running`                         | `RUNNING`                              |
| `Cycling`                         | `BIKING`                               |
| `Swimming`                        | `SWIMMING_OPEN_WATER`                  |
| `Walking`                         | `WALKING`                              |
| `Yoga`                            | `YOGA`                                 |
| `HighIntensityIntervalTraining`   | `HIGH_INTENSITY_INTERVAL_TRAINING`     |
| `StrengthTraining`                | `STRENGTH_TRAINING`                    |
| *anything else*                   | `OTHER_WORKOUT`                        |

See [`parser/WorkoutTypeMapper.kt`](app/src/main/java/com/healthbridge/parser/WorkoutTypeMapper.kt).

---

## Module / package map

Single-module Android app (`:app`), root package **`com.healthbridge`**, single
`MainActivity` hosting a Compose `NavHost`.

```
app/src/main/java/com/healthbridge/
├── MainActivity.kt                  # single activity → Compose NavHost host
│
├── ui/
│   ├── nav/                         # HealthBridgeApp.kt (NavHost), Screen.kt (sealed routes)
│   ├── theme/                       # Color.kt, Type.kt, Theme.kt, Dimens.kt — design tokens (PRD §6)
│   ├── components/                  # HbComponents.kt — shared Compose component library
│   ├── onboarding/                  # OnboardingScreen.kt
│   ├── home/                        # HomeScreen.kt
│   ├── importer/                    # ImportScreen.kt, ProcessingScreen.kt  (package is `importer`, NOT `import`†)
│   ├── delta/                       # DeltaReviewScreen.kt
│   ├── writing/                     # WritingScreen.kt
│   ├── history/                     # SyncHistoryScreen.kt
│   └── settings/                    # SettingsScreen.kt
│
├── parser/                          # Apple Health export → domain model
│   ├── AppleHealthRecord.kt         #   shared data model (records, deltas, sync session)
│   ├── AppleHealthParser.kt         #   streaming XML parser
│   └── WorkoutTypeMapper.kt         #   Apple activity → HC exercise type
│
├── fingerprint/                     # local dedup store
│   ├── FingerprintEngine.kt         #   SHA-256 fingerprint + dedup logic
│   └── FingerprintDatabase.kt       #   Room DB: fingerprints + sync_log, DAOs, @Database
│
├── healthconnect/                   # Health Connect integration
│   ├── HealthConnectManager.kt      #   client, availability, permissions
│   ├── HealthConnectMapper.kt       #   AppleHealthRecord → HC Record
│   └── HealthConnectWriter.kt       #   batched (500) insert + confirm-then-fingerprint
│
└── sync/                            # orchestration
    ├── SyncEngine.kt                #   ties parse → dedup → write together
    └── SyncLogger.kt               #   writes sync_log entries
```

† **Why `importer` and not `import`:** `import` is a reserved Kotlin keyword and cannot be
used as a package segment, so the import/processing screens live in
`com.healthbridge.ui.importer`.

### Shared data model

The contract every layer speaks is defined once in
[`parser/AppleHealthRecord.kt`](app/src/main/java/com/healthbridge/parser/AppleHealthRecord.kt):
`HealthDataType` (the 7-entry enum above), `AppleHealthRecord`, `WorkoutDetail`,
`RoutePoint`, `DeltaRow`, `DeltaResult`, and `SyncSession`. Do not redefine these
elsewhere.

### Local persistence (Room)

[`fingerprint/FingerprintDatabase.kt`](app/src/main/java/com/healthbridge/fingerprint/FingerprintDatabase.kt)
holds two tables:

- **`fingerprints`** — `FingerprintEntity(id, hash UNIQUE, dataType, recordDate, syncedAt)`.
  The `hash` uniqueness constraint is what makes dedup cheap and idempotent.
- **`sync_log`** — `SyncLogEntity(id, syncedAt, sourceFilename, durationMs, recordsWritten /*JSON*/, recordsSkipped, status)`.

DAOs: `FingerprintDao` (insert / exists / allHashes) and `SyncLogDao`
(insert / getAll / count / totalSize).

### UI component library

All screens build on the shared components in
[`ui/components/HbComponents.kt`](app/src/main/java/com/healthbridge/ui/components/HbComponents.kt)
— `HbScaffold`, `HbPrimaryButton`, `HbGhostButton`, `HbTextButton`, `HbCard`,
`HbDataRow`, `HbMetric`, `HbChip`, `HbSectionLabel`. Screens must use these rather than
redefining primitives, which keeps the design tokens (deep-navy surfaces, single teal
accent `#00D4B8`, monospace for data values, no shadows — elevation via surface color
only) consistent. The same visual system is demonstrated live in
[`../web-prototype`](../web-prototype).

---

## Build & run

### Requirements

- **Android Studio** Hedgehog (2023.1.1) or newer
- **JDK 17** (the project compiles against `JavaVersion.VERSION_17`)
- **Android SDK 34** (compile/target), **min SDK 26**
- Kotlin **1.9.22**, Compose BOM `2024.02.00`, Compose compiler `1.5.10`
- A device or emulator with **Google Health Connect** installed
  (`com.google.android.apps.healthdata`). On Android 14+ Health Connect is part of the
  platform; on older devices install it from the Play Store.

### Open in Android Studio

1. `File → Open…` and select this folder: **`healthbridge-android/`**
   (the one containing `settings.gradle.kts`). Open the project root, not `app/`.
2. Let Gradle sync. The Android Gradle Plugin is `8.2.2`; Studio will use the bundled
   JDK 17 by default.
3. Select the **`app`** run configuration and a target device, then **Run** (`^R`).

### Command line

```bash
cd healthbridge-android

# Debug build
./gradlew assembleDebug

# Install on a connected device/emulator
./gradlew installDebug

# Unit + lint
./gradlew test lint
```

> No Gradle wrapper JAR is committed in this scaffold — generate it once with a local
> Gradle (`gradle wrapper --gradle-version 8.2`) or let Android Studio create it on first
> sync.

---

## Health Connect: permissions & privacy policy

HealthBridge requests **12 Health Connect permissions** (declared in
[`AndroidManifest.xml`](app/src/main/AndroidManifest.xml)): **7 WRITE** permissions (one
per supported type) plus **5 READ** permissions used to confirm what already exists. The
permission set is also published in
[`res/xml/health_permissions.xml`](app/src/main/res/xml/health_permissions.xml).

Health Connect **requires** an app that requests health permissions to expose a
**privacy-policy / rationale** screen. HealthBridge satisfies this:

- An `intent-filter` for `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`
  (Android 14+ in-app rationale path) on `MainActivity`.
- A `ViewPermissionUsageActivity` **activity-alias** handling
  `android.intent.action.VIEW_PERMISSION_USAGE` with the
  `HEALTH_PERMISSIONS` category (Android 13 and below).

Both paths resolve to `MainActivity`, which routes to the onboarding / privacy
explanation screen. **Health Connect will refuse to grant permissions if this rationale
target is missing**, so do not remove it.

---

## Privacy & security

- **No `android.permission.INTERNET`.** The manifest intentionally omits it — there is no
  code path that can reach the network. HealthBridge is offline by construction.
- **No backend, no accounts, no analytics, no cloud.** Your health data never leaves the
  device.
- **Extracted XML is ephemeral.** The export is unzipped into `cacheDir` and the extracted
  `export.xml` is **deleted immediately after parsing**.
- **Health Connect is the only data sink**, and HealthBridge only ever **writes** to it.

---

## Scope: MVP vs roadmap

### MVP (this scaffold)

- The **7 data types** above, parsed and written.
- **Fingerprint dedup** — write the delta only, idempotent across re-imports.
- **Batched writes** (500/batch) with confirm-then-fingerprint and per-batch retry.
- **8 screens**: Onboarding · Home · Import · Processing · Delta Review · Writing ·
  Sync History · Settings.
- Fully offline; local Room persistence for fingerprints + sync log.

### v1.1 — GPS routes

- **Workout GPS routes.** The data model already reserves the shape:
  `WorkoutDetail.routePoints: List<RoutePoint>` and `RoutePoint(lat, lon, altitude, time)`.
  Route parsing from the Apple export and mapping to Health Connect's
  `ExerciseRoute` is **deferred to v1.1** (`routePoints` is `emptyList()` in MVP).

### v2.0 — roadmap

- Additional Apple Health data types beyond the MVP 7 (e.g. blood oxygen, body
  measurements, nutrition).
- Incremental "watch a folder / scheduled re-sync" automation.
- Conflict/merge UX for edited values (today edits are ignored by the fingerprint design).
- Export/restore of the fingerprint DB across devices.

---

## Scaffold status

| Layer                         | Status                                                        |
|-------------------------------|--------------------------------------------------------------|
| Theme / design tokens         | ✅ Complete (`ui/theme`)                                      |
| Component library             | ✅ Complete (`ui/components/HbComponents.kt`)                 |
| Navigation + all 8 screens    | ✅ UI in place, mock/sample data, state hoisted              |
| Shared data model             | ✅ Complete (`parser/AppleHealthRecord.kt`)                  |
| Room schema + DAOs            | ✅ Declared (`fingerprint/FingerprintDatabase.kt`)           |
| Manifest / permissions        | ✅ Complete (12 HC permissions, rationale targets, no net)   |
| XML parsing                   | 🟡 `// TODO` — stubbed in `parser/AppleHealthParser.kt`      |
| Fingerprint hashing + dedup   | 🟡 `// TODO` — stubbed in `fingerprint/FingerprintEngine.kt` |
| Health Connect write (batched)| 🟡 `// TODO` — stubbed in `healthconnect/HealthConnectWriter.kt` |
| Sync orchestration            | 🟡 `// TODO` — stubbed in `sync/SyncEngine.kt`               |

Search the source for `TODO` to find every spot where real engine wiring belongs:

```bash
grep -rn "TODO" app/src/main/java/com/healthbridge
```

---

## See also

- [`../web-prototype`](../web-prototype) — the interactive, clickable design and
  interaction source of truth (all 8 screens, the live dedup demo, deep-links for QA).
- [`../healthbridge-prd.md`](../healthbridge-prd.md) — the full product requirements
  document this scaffold implements.
