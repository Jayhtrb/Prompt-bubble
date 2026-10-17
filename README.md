# Prompt Bubble

A floating bubble that sits over every Android app. Tap it to:

- **Grab all text** – reads every visible piece of text on the current screen and drops it in your tray.
- **Grab text in area** – drag a box; only the text inside it is grabbed.
- **Snip screenshot** – drag a box with your finger; that area is captured, saved to `Pictures/PromptBubble`, added to the tray and copied to the clipboard.
- **Open tray** – everything you collected, with an optional instruction on top. *Copy prompt* puts it all on the clipboard as one prompt; *Send to AI…* shares the text plus any snips straight into Claude, ChatGPT, Gemini, etc.

The bubble shows how many items are in your tray. Drag it anywhere; it snaps to the screen edge.

Requires Android 11+. No extra libraries, no internet permission — nothing leaves the phone unless you share it.

## Build

**Option A – Android Studio:** Open the `PromptBubble` folder, let Gradle sync, plug in your phone (USB debugging on) and press Run.

**Option B – no Android Studio:** Push this folder to a GitHub repo. The included workflow (`.github/workflows/build.yml`) builds the APK on every push; download it from the run's *Artifacts* section and install it on your phone.

## First-time setup on the phone

1. Open Prompt Bubble → **Turn on in Accessibility settings** → *Installed apps* → **Prompt Bubble** → On.
2. **If the switch is greyed out** ("Restricted setting", Android 13+ for sideloaded apps): go to *Settings → Apps → Prompt Bubble → ⋮ (top right) → Allow restricted settings*, then try step 1 again.
3. The bubble appears. Done.

## Limits worth knowing

- Text grabbing reads what apps expose to accessibility. Most apps and web pages work; text drawn inside images, games, video, or some custom-drawn apps won't come through — use Snip for those.
- Password fields are always skipped.
- Banking/DRM apps that block screenshots will also block Snip ("This app blocks screenshots").

## Code map

| File | What it does |
|---|---|
| `BubbleService.kt` | Accessibility service: bubble, menu, text grabbing, screenshots |
| `SelectionOverlay.kt` | The dimmed drag-to-select layer |
| `TrayStore.kt` | Saves collected items (JSON in app storage) and builds the prompt |
| `MainActivity.kt` | Setup + tray screen (edit, remove, copy, share) |

## Ideas for next

- OCR on snips (ML Kit text recognition) so screenshots also become text.
- Send straight to the Claude API from the tray instead of via the share sheet.
- Quick Settings tile to toggle the bubble.
