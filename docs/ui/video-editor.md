# Video Editor Workspace

Master spec §39–45, §75. Existing Timeline engine is preserved and
re-surfaced (§81) — do not discard advanced capabilities.

## Inventory (§102)

- SCR-VIDEO-001 Projects: list + Create + Import media (empty per §57).
- SCR-VIDEO-002 Editor: top bar (Back · project · Undo Redo Export ⋮) ·
  PREVIEW · panel tabs (Media/Tracks/Inspector) · TIMELINE (V2/V1/A1 +
  playhead) · toolbar (§39).
- SCR-VIDEO-003 Media: project assets grid + import + probe info.
- SCR-VIDEO-004 Inspector: selected-clip properties (trim/split/
  duplicate/speed/transform/audio/color/FX/mask/motion, §44).
- SCR-VIDEO-005 Text: overlays list + styles + add/edit.
- SCR-VIDEO-006 Audio: clip audio + silence/levels.
- SCR-VIDEO-007 Effects: color/FX/LUT/motion/scopes.
- SCR-VIDEO-008 Export: preset + range + [Export] (always easy to find).
- SCR-VIDEO-009 Render queue: jobs + progress + QC + approve + output.

## Component specs

- Preview: aspect-preserved (16:9/9:16/1:1/4:5), black background,
  Play/Pause + time + duration + scrubber (§41).
- Timeline: horizontal scroll + zoom + track scroll + playhead (accent
  vertical line); selected clip = accent border; drag/trim/split/
  duplicate/delete (§42).
- Toolbar: scrolling categories Edit/Audio/Text/Effects/Color/Mask/
  Motion — never 20 tools at once; tap switches tray (§43).
- AI (✨): Auto edit, Remove silence, Find highlights, Generate
  subtitles, Improve audio, Create short/thumbnail, Follow script —
  modifies the real project through the engine (§45).
- Portrait compresses panels; landscape expands timeline (§39).
- Back chain: inspector → panel → exit project → launcher/chat (§85).
- Unsaved: [Cancel] [Discard] [Save] (§86).
