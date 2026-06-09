# HealthBridge — Interactive Prototype

A high-fidelity, fully clickable prototype of all **8 screens** from the HealthBridge PRD.
Zero dependencies, zero build step, zero network. Open it and use it.

```bash
# from this folder
open index.html          # macOS
# or just double-click index.html, or drag it into any browser
```

It renders as a phone on desktop and full-bleed on an actual device.

---

## What this is

HealthBridge mirrors an Apple Health export to Google Health Connect — on-device, no
account, no cloud. The real app is native Android (Kotlin/Compose); see
[`../healthbridge-android/`](../healthbridge-android/) for the scaffolded codebase.

This prototype is the **visual + interaction source of truth** for that build. It
implements the real product behaviour, not a slideshow:

- A live **deduplication engine** ([data.js](data.js)) keeps a per-type `syncedCount`
  ("fingerprint DB") and computes `delta = fileTotal − alreadySynced` on every import.
  The *same* code therefore produces a full first sync, a partial delta, or a
  "nothing new" result — exactly like the real fingerprint database.
- **Settings toggles** actually exclude a data type from the delta (US-04).
- The simulated file picker includes an **invalid ZIP** so the validation path (US-06)
  is reachable.

## The core flow

```
Onboarding → Home → Import → Processing → Delta Review → Writing → Home
                                    │                        │
                               (3 stages)              History · Settings
```

Try this to feel the dedup value proposition:
1. From Home, **Import Apple Health Export** → pick `apple_health_export.zip` →
   **Start processing** → watch the 3-stage processing → **Write to Health Connect**.
   That's your first full sync (~125k records).
2. Import again and pick the **same** file → Delta Review shows **"Nothing new to sync."**
3. Import and pick `export_2026-06-10.zip` (a newer export) → a **partial delta** of
   383 new records, the rest skipped.
4. `vacation_photos.zip` → inline **validation error**.

## Demo deep-links (for QA / screenshots)

Append query params to jump straight to any state:

| URL | Shows |
|---|---|
| `index.html` | Onboarding (first run) |
| `index.html?screen=home&state=synced` | Home, post-sync |
| `index.html?screen=delta&file=newer&state=synced` | Delta Review — partial delta |
| `index.html?screen=delta&file=main&state=synced` | Delta Review — "nothing new" |
| `index.html?screen=import&file=bad` | Import — validation error |
| `index.html?screen=processing&file=main` | Processing (animated) |
| `index.html?screen=settings&state=synced` | Settings |

- `state` — `fresh` (onboarded, no sync) or `synced` (one full sync already done)
- `file` — `main`, `newer`, or `bad`

## Design language

Faithful to the PRD's design tokens (§6): deep-navy→near-black surfaces, a single
electric-teal accent (`#00D4B8`), monospace for all data values and sans (Inter) for
labels, 12/8/4 dp corner radii, a 4-dp spacing grid, and **no shadows** — elevation is
expressed purely through surface-color steps. Motion is shared-axis screen transitions
plus functional micro-interactions (ripples, animated counters, a drawn success check).

## Files

| File | Role |
|---|---|
| [index.html](index.html) | Shell: device frame, status bar, font imports |
| [styles.css](styles.css) | The whole design system (tokens → components → motion) |
| [data.js](data.js) | Mock Apple Health export + dedup/state engine |
| [icons.js](icons.js) | Inline-SVG icon set |
| [app.js](app.js) | Router, shared-axis transitions, sheets, toasts, counters |
| [screens.js](screens.js) | All 8 screens |
| [selftest.html](selftest.html) | Drives the full flow headless to assert no runtime errors |

## Self-test

`selftest.html` scripts the entire journey through the real router and reports
pass/fail via `document.title`. Run headless:

```bash
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
"$CHROME" --headless=new --disable-gpu --virtual-time-budget=45000 \
  --dump-dom "file://$PWD/selftest.html" | grep -oE 'SELFTEST [A-Z]+ [^<]*'
# → SELFTEST PASS steps=17 errors=0
```
