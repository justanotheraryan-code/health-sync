/* ============================================================================
   HealthBridge — Mock data + dedup state engine
   The engine mirrors the real pipeline's behaviour (PRD §3): a fingerprint DB
   tracks what's already been written, and each import computes a DELTA against
   it. State is intentionally mutable + in-memory so the demo can be replayed.
   ========================================================================== */
(function (HB) {

  /* ---- Supported data types (PRD success criteria #4) -----------------------
     total  = how many records of this type live in the primary export
     extra  = additional records present in the "newer" export (export_2.zip),
              which produces the partial-delta experience from the PRD.        */
  const TYPES = [
    { id: 'workout',  label: 'Workouts',           icon: 'workout',  apple: 'HKWorkoutActivityType*',                    hc: 'ExerciseSessionRecord',        unit: 'sessions', total: 859,   extra: 12 },
    { id: 'steps',    label: 'Steps',              icon: 'steps',    apple: 'HKQuantityTypeIdentifierStepCount',         hc: 'StepsRecord',                  unit: 'records',  total: 12434, extra: 34 },
    { id: 'heart',    label: 'Heart Rate',         icon: 'heart',    apple: 'HKQuantityTypeIdentifierHeartRate',         hc: 'HeartRateRecord',              unit: 'samples',  total: 89437, extra: 203 },
    { id: 'sleep',    label: 'Sleep',              icon: 'sleep',    apple: 'HKCategoryTypeIdentifierSleepAnalysis',     hc: 'SleepSessionRecord',           unit: 'sessions', total: 1820,  extra: 7 },
    { id: 'energy',   label: 'Active Energy',      icon: 'energy',   apple: 'HKQuantityTypeIdentifierActiveEnergyBurned',hc: 'ActiveCaloriesBurnedRecord',   unit: 'records',  total: 18650, extra: 120 },
    { id: 'resting',  label: 'Resting Heart Rate', icon: 'resting',  apple: 'HKQuantityTypeIdentifierRestingHeartRate',  hc: 'RestingHeartRateRecord',       unit: 'records',  total: 1740,  extra: 5 },
    { id: 'vo2',      label: 'VO₂ Max',            icon: 'vo2',      apple: 'HKQuantityTypeIdentifierVO2Max',            hc: 'Vo2MaxRecord',                 unit: 'records',  total: 168,   extra: 2 },
  ];
  const TYPE_BY_ID = Object.fromEntries(TYPES.map(t => [t.id, t]));

  /* ---- Representative items shown when a delta row is expanded -------------- */
  const WORKOUT_ITEMS = [
    { name: 'Running',          exType: 'RUNNING',                           date: 'Jun 10', dur: '42:18',   kcal: 512 },
    { name: 'Cycling',          exType: 'BIKING',                            date: 'Jun 09', dur: '1:08:42', kcal: 743 },
    { name: 'Strength Training',exType: 'STRENGTH_TRAINING',                 date: 'Jun 08', dur: '38:05',   kcal: 287 },
    { name: 'HIIT',             exType: 'HIGH_INTENSITY_INTERVAL_TRAINING',  date: 'Jun 07', dur: '24:30',   kcal: 318 },
    { name: 'Open Water Swim',  exType: 'SWIMMING_OPEN_WATER',               date: 'Jun 06', dur: '31:12',   kcal: 402 },
    { name: 'Yoga',             exType: 'YOGA',                              date: 'Jun 05', dur: '55:00',   kcal: 142 },
    { name: 'Walking',          exType: 'WALKING',                           date: 'Jun 04', dur: '1:12:33', kcal: 268 },
    { name: 'Running',          exType: 'RUNNING',                           date: 'Jun 03', dur: '36:54',   kcal: 451 },
  ];
  // generic sample builders for the non-workout types
  const SAMPLES = {
    steps:   [['Jun 10','11,204 steps'],['Jun 09','8,932 steps'],['Jun 08','13,610 steps'],['Jun 07','6,418 steps'],['Jun 06','15,002 steps'],['Jun 05','9,771 steps']],
    heart:   [['Jun 10 · 09:41','72 bpm avg · 1,204 samples'],['Jun 10 · 06:15','58 bpm avg · 890 samples'],['Jun 09 · 18:02','141 bpm avg · 2,051 samples'],['Jun 09 · 08:30','69 bpm avg · 1,110 samples']],
    sleep:   [['Jun 09 → Jun 10','7h 24m · Core + REM + Deep'],['Jun 08 → Jun 09','6h 51m'],['Jun 07 → Jun 08','8h 02m'],['Jun 06 → Jun 07','7h 12m']],
    energy:  [['Jun 10','642 kcal'],['Jun 09','958 kcal'],['Jun 08','531 kcal'],['Jun 07','489 kcal'],['Jun 06','1,104 kcal']],
    resting: [['Jun 10','54 bpm'],['Jun 09','55 bpm'],['Jun 08','53 bpm'],['Jun 07','56 bpm']],
    vo2:     [['Jun 07','48.2 mL/kg·min'],['May 31','47.9 mL/kg·min']],
  };
  const DATE_RANGE = { // date range of the *new* records, by type (for the delta rows)
    workout: 'Jun 3 – Jun 10', steps: 'Jun 5 – Jun 10', heart: 'Jun 8 – Jun 10',
    sleep: 'Jun 6 – Jun 10', energy: 'Jun 6 – Jun 10', resting: 'Jun 7 – Jun 10', vo2: 'May 31 – Jun 7',
  };

  /* ---- Simulated SAF file picker (Screen 3) -------------------------------- */
  const FILES = [
    { id: 'main',  name: 'apple_health_export.zip',     size: '214.6 MB', modified: '10 Jun 2026, 08:12', valid: true,  totalsKey: 'total' },
    { id: 'newer', name: 'export_2026-06-10.zip',       size: '215.1 MB', modified: '10 Jun 2026, 22:47', valid: true,  totalsKey: 'newer' },
    { id: 'bad',   name: 'vacation_photos.zip',          size: '48.2 MB',  modified: '02 Jun 2026, 14:03', valid: false, totalsKey: null },
  ];

  // total count of a type within a given file
  function fileTotal(file, type) {
    if (!file.valid) return 0;
    return file.totalsKey === 'newer' ? type.total + type.extra : type.total;
  }

  /* ---- Runtime state ------------------------------------------------------- */
  const state = {
    onboarded: false,
    permissions: Object.fromEntries(TYPES.map(t => [t.id, false])), // granted in onboarding slide 3
    enabled:     Object.fromEntries(TYPES.map(t => [t.id, true])),  // Settings toggles (default all on)
    syncedCount: Object.fromEntries(TYPES.map(t => [t.id, 0])),     // the "fingerprint DB"
    history: [],                                                    // sync_log rows, newest first
    lastSync: null,                                                 // {dateLabel, timeLabel, newTotal, filename}
  };

  /* ---- Engine -------------------------------------------------------------- */
  const engine = {
    grandScanTotal: TYPES.reduce((s, t) => s + t.total, 0), // ~125,108

    knownCount() { return TYPES.reduce((s, t) => s + state.syncedCount[t.id], 0); },

    /* Compute the delta for a file against the current fingerprint DB.
       Returns { rows:[{type, newCount, skipped, total, items, range, enabled}],
                 newTotal, skippedTotal, scannedTotal } */
    computeDelta(fileId) {
      const file = FILES.find(f => f.id === fileId);
      let newTotal = 0, skippedTotal = 0, scannedTotal = 0;
      const rows = TYPES.map(type => {
        const enabled = state.enabled[type.id];
        const total = fileTotal(file, type);
        const synced = Math.min(state.syncedCount[type.id], total);
        // Disabled types are never parsed/fingerprinted/written (US-04)
        const newCount = enabled ? Math.max(total - synced, 0) : 0;
        const skipped = enabled ? synced : 0;
        if (enabled) { scannedTotal += total; newTotal += newCount; skippedTotal += skipped; }
        return {
          type, enabled, total, newCount, skipped,
          range: DATE_RANGE[type.id],
          items: engine.itemsFor(type.id, newCount),
        };
      });
      return { rows, newTotal, skippedTotal, scannedTotal, file };
    },

    // The individual rows shown when a delta row is expanded (read-only preview)
    itemsFor(typeId, newCount) {
      if (newCount <= 0) return [];
      if (typeId === 'workout') {
        return WORKOUT_ITEMS.slice(0, Math.min(WORKOUT_ITEMS.length, newCount))
          .map(w => ({ title: w.name, meta: `${w.date} · ${w.dur} · ${w.exType}`, val: `${w.kcal} kcal` }));
      }
      const s = SAMPLES[typeId] || [];
      return s.slice(0, Math.min(s.length, newCount)).map(([a, b]) => ({ title: a, meta: '', val: b }));
    },

    /* Persist a successful write: every record in this file is now "known",
       append a sync_log row, and update lastSync. Mirrors SyncLogger (PRD §3). */
    commitSync(fileId, delta, durationMs) {
      const file = FILES.find(f => f.id === fileId);
      const writtenByType = {};
      delta.rows.forEach(r => {
        if (!r.enabled) return;
        state.syncedCount[r.type.id] = fileTotal(file, r.type); // everything in file now fingerprinted
        if (r.newCount > 0) writtenByType[r.type.label] = r.newCount;
      });
      const entry = {
        dateLabel: '10 June 2026',
        timeLabel: '22:47',
        filename: file.name,
        newTotal: delta.newTotal,
        skippedTotal: delta.skippedTotal,
        durationMs,
        writtenByType,
        status: 'success',
      };
      state.history.unshift(entry);
      state.lastSync = { dateLabel: entry.dateLabel, timeLabel: entry.timeLabel, newTotal: delta.newTotal, filename: file.name };
      return entry;
    },

    // Settings → "Reset sync history" (clears fingerprints; HC data untouched)
    resetFingerprints() {
      TYPES.forEach(t => { state.syncedCount[t.id] = 0; });
    },

    dbStats() {
      const count = engine.knownCount();
      // ~60 bytes per fingerprint row (hash + type + date + ts)
      const kb = count * 0.06;
      const size = kb > 1024 ? (kb / 1024).toFixed(1) + ' MB' : Math.round(kb) + ' KB';
      return { count, size };
    },

    grantAllPermissions() { TYPES.forEach(t => { state.permissions[t.id] = true; }); },
  };

  HB.data = { TYPES, TYPE_BY_ID, FILES, WORKOUT_ITEMS };
  HB.state = state;
  HB.engine = engine;

  // formatting helpers shared by screens
  HB.fmt = {
    int: n => n.toLocaleString('en-US'),
    ms: ms => (ms / 1000).toFixed(1) + 's',
  };

})(window.HB = window.HB || {});
