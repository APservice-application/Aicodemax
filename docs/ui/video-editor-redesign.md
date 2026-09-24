# Video workspace UI — owner PDF screens 1–8 (2026-09-24)

Source: `/home/user/uploads/Aicodemax_VideoEditor_UI_Spec.pdf` (10 pages) and `CapCut_Full_Feature_Inventory.txt` (comparison, **not** a statement that all CapCut features are implemented). This document covers the Android Compose UI, not a new video engine. See MASTER_ARCHITECTURE.md for system truth.

## Navigation

- Video launcher → full-page **project home**, not a tab row (PDF screen 1). Cards open a project; card menu duplicates, renames and moves a project to trash. Overflow sorts, starts an import, or changes default aspect. Back goes to Chat.
- Create (screen 2) → aspect 9:16 / 16:9 / 1:1, Android Photo Picker (video + image multi-select), camera capture (app-owned FileProvider URI), ordered selected media thumbnails, then import into the *real* project asset bin and append clips to the real timeline. No runtime storage permission needed. Never proceed on zero assets.
- Editor (screens 3–4) → project name/save indicator/export; aspect-preserving source preview; fixed-center playhead over a scrollable multitrack timeline with actual asset thumbnails; tap selection, drag yellow edges to trim, long-press drag to move, two-finger pinch / buttons to zoom, + import, undo/redo, contextual horizontally scrolling tool bar. Back returns home; playback position is persisted per project in SharedPreferences.
- Tools (screens 5–7) → dark bottom sheets with drag-to-dismiss/finish: transform, audio, color dials plus trim, speed/ramp, keyframes, transition, FX, mask/chroma, motion/tracking, text, AI actions. These call `MediaProjectPort` / existing gateway actions. The original `TimelineScreen` remains accessible under “เครื่องมือขั้นสูง” for LUT, proxy, multicam, slideshow, etc. No loss of old controls.
- Export (screen 8) → actual final frame after rendering (or clearly labeled reference thumbnail before), real resolution presets, file size estimate, `RenderPort.enqueue` then `run` on app scope, progress/status polling, QC, explicit approve, MediaStore export, Android share sheet, queue link. Rendering continues if user visits queue screen but **do not kill the app** during rendering.

## Engine limits, explicitly reflected in UI

1. There is **no composited realtime preview** for color/FX, text, speed ramps or mixed audio. The preview plays the source video and scrubs genuine asset frames; a visible note tells users rendered output is the authority. Do not claim effects preview live until the underlying engine provides it.
2. Existing render presets: `original` (max 1080p), `720p`, `480p`; FPS cannot be selected independently. UI discloses this instead of providing nonfunctional 30/60fps buttons. Existing Clip volume is 0–100%, not the PDF's proposed 0–200%.
3. The CapCut inventory includes many *proposed* backend capabilities (e.g. AI background removal, optical-flow slow motion, cloud collaboration). Do not render fake feature buttons for them. Changes to actual backend are for a separate owner-authorized checkpoint.
4. The engine's `addClip` currently recreates a `Timeline` without canvas/background. This UI restores those values after imports and detach-audio, rather than allowing a silent change in canvas. The engine bug should be corrected by its owner in a separate PR.
5. `moveClip` repositions a clip but does not automatically ripple every subsequent clip; videos may overlap when moved. Do not promise automatic magnetic reorder. Split occurs at playhead, not at an invented fixed midpoint.
6. On-device testing remains the owner’s responsibility. Agent verifies CI / APK assembly, does not claim device playback or quality without a device test.

## Acceptance / test plan

- Start from Tools → Video; see project list, not a hardcoded editor/empty wall. Create using a local video and image; verify 9:16 canvas, media ordering, first-frame preview, timeline thumbnails and audio/text lanes.
- Scroll timeline/zoom, select clip, split inside clip; undo/redo; trim/move; duplicate/delete/freeze; edit Transform/Audio/Color and confirm the real project state changes. Open Advanced to reach remaining original tools.
- Export 720p; watch progress; verify QC then approve/save/share. Check outputs in Downloads/Aicodemax. Do not select 60fps: the current renderer cannot enforce it.
- Check back navigation, camera URI permissions, failure/empty states, tall portrait and rotated full-screen preview, TalkBack labels. Device-only checks need owner feedback.
