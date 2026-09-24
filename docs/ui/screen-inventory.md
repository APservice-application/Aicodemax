# Screen Inventory (§102)

Every screen answers the 18 questions of §103 in its app doc (see
`docs/ui/<app>.md` + state matrices below). Shell/global: see
`global-shell.md`.

## CHAT (docs/ui/chat.md)
SCR-CHAT-001 Main · 002 Drawer · 003 Attachment sheet · 004 Image viewer ·
005 File preview · 006 Voice · 007 Model selector · 008 Approval ·
009 Context chips · 010 Result card

## BROWSER (docs/ui/browser.md)
SCR-BROWSER-001 Main · 002 Tabs · 003 History · 004 Bookmarks ·
005 Downloads · 006 Browser menu · 007 AI panel

## VIDEO (docs/ui/video-editor.md)
SCR-VIDEO-001 Projects · 002 Editor · 003 Media · 004 Inspector ·
005 Text · 006 Audio · 007 Effects · 008 Export · 009 Render queue

## IMAGE (docs/ui/image-editor.md)
SCR-IMAGE-001 Main · 002 Layers · 003 Adjust · 004 Text/Brush/Mask ·
005 AI · 006 Export

## AUDIO (docs/ui/audio-editor.md)
SCR-AUDIO-001 Main · 002 Edit · 003 Record · 004 Voice/Music ·
005 Export

## FILES (docs/ui/file-manager.md)
SCR-FILES-001 Main · 002 Selection · 003 Search · 004 Details ·
005 Operations

## DEV (docs/ui/development.md)
SCR-DEV-001 Workspace · 002 Editor · 003 Problems · 004 Terminal ·
005 Run

## GIT (docs/ui/git.md)
SCR-GIT-001 Main · 002 Changes · 003 Commits · 004 Branches ·
005 History · 006 Sync

## SUBTITLE (docs/ui/subtitle.md)
SCR-SUB-001 Main · 002 Make · 003 Edit/Shift · 004 Translate ·
005 Burn · 006 Preview

## RECORDER (docs/ui/recorder.md)
SCR-REC-001 Main · 002 Screen · 003 Audio · 004 Camera · 005 Outputs

## GENERATOR (docs/ui/media-generator.md)
SCR-GEN-001 Main · 002 Create · 003 Script→Video · 004 AI plan ·
005 Audio · 006 Photo · 007 Outputs

## SYSTEM (settings/tasks/audit/models/memory/skills)
- SCR-SYS-001 Models (installed/provider/runtime + Load/Unload/Settings,
  §54) · 002 Memory (§55) · 003 Skills · 004 Tasks (running/queued/
  completed/failed/paused/waiting, §63) · 005 Audit · 006 Settings
  (Appearance/AI/Models/Browser/Editor/Media/Storage/Privacy/
  Permissions/Automation/Advanced/About, §56) · 007 Global search (§61)

## State matrix (applies to every screen, §67)

INITIAL → LOADING (skeleton/progress + status verb) → READY/EMPTY →
EDITING/PROCESSING → SUCCESS/ERROR (what+why+action, §59) ;
OFFLINE (local-ready messaging, §60) ; PERMISSION_REQUIRED (explain +
settings action) ; CONFIRMATION_REQUIRED (destructive/unsaved, §86).
Back/rotate/keyboard/AI-entry/saved-state/return per §103 in app docs.
