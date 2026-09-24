# AI Chat (home)

Master spec §12–31, §65, §76, §91–98, §104–105. Reference UX: ChatGPT
(conversation-first, composer, attachments, streaming, message actions);
own design system, no copied assets (§2.1).

## Inventory (§102)

- SCR-CHAT-001 Main: 56dp top bar (☰ 48dp @x=4 · title/conversation ·
  New Chat · ⋮), message area (16dp padding, 12–20dp spacing), composer.
- SCR-CHAT-002 Drawer: see shell (search + TODAY/YESTERDAY + PROJECTS +
  launcher + Settings, §27).
- SCR-CHAT-003 Attachment sheet (+): Camera / Photos / Files / Project /
  Web page / Audio / Cancel; rows 48–56dp, icon 24dp, label 15–16sp (§21).
- SCR-CHAT-004 Image viewer: full-screen, Back/Zoom/Share/Save/More,
  pinch + double-tap zoom (§93).
- SCR-CHAT-005 File preview: icon + filename + type • size + × remove
  before send (§92); sent-file card with Open + Download/Save (§29).
- SCR-CHAT-006 Voice: ● Listening + waveform + Cancel/Stop → text into
  composer, editable before send (§26).
- SCR-CHAT-007 Model selector: compact "Local • Qwen …" → menu of local /
  cloud, availability, context, speed from runtime metadata (§98).
- SCR-CHAT-008 Approval card: [Cancel] [Review] [Approve] (§31).
- SCR-CHAT-009 Context chips: [Project: X] [N files] [image.jpg]; tap =
  view/remove (§91).
- SCR-CHAT-010 Result card (§65): ✓ completed + artifact + [Preview]
  [Open in X] [Open file] [Share]. Generated file/image/code variants
  per §95–97.

## Message rules

- User: END, max ~82%, accent surface, white text, 18dp radius,
  12dp H / 10dp V padding (§15).
- AI: START, max ~92–96%, transparent/subtle surface; may hold text,
  markdown, code, table, image, file, tool status, progress, citation,
  interactive result (§16).
- Response actions under completed AI message: Copy / Like / Retry /
  More(⋮ → Copy, Regenerate, Edit, Read aloud, Share, Report) (§17).
- Streaming: progressive message; ■ Stop replaces Send; composer
  returns after completion (§18).

## Composer (§19–20)

Bottom-anchored, margins 12/12/8dp, min-height ~52dp, grows to ~160–220dp
then internal scroll, 24dp radius, surface + 1dp border.
Layout: + (sheet) · text input · 🎙 (empty) / ↑ Send (has text).
Attachments: image thumb 72–96dp/12dp radius, tap = viewer, × = remove;
AI receives reference + mime + dimensions + size + id (§22). Files per
§23 (PDF/TXT/MD/DOC/XLS/CSV/JSON/ZIP/source/video/audio/images as
supported). Drag/drop highlight where supported (§24). Camera: capture →
Preview/Retake/Use (§25).

## Tool + approval UI

- Tool card (§30): compact running card (name + status + progress) → ✓
  summary → tap expands details.
- Approval (§31): explicit action sentence + [Cancel] [Review] [Approve].
- Cross-app flow (§64) and result return (§65) mandatory.

## §103 answers (all chat screens)

Empty: suggestions + New Chat. Loading: skeleton + phase label
(thinking/using-tool/retry). Error: what/why/action + Retry. Offline:
Local AI status + available ops (§60). Permission denied: explanation +
open-settings action. Back: close sheet/panel first, then drawer, then
stay (home). Rotate: state kept, composer kept. Keyboard: composer above
IME, list shrinks. AI invoke: contextual entry points. Saved state:
draft, attachments, scroll, conversation. Return: via drawer/history.
