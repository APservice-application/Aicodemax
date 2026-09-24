# Browser Workspace

Master spec §34–38, §75, §105. Own chrome — never Chat chrome (§34).

## Inventory

- SCR-BROWSER-001 Main: tab strip/controls · address bar (← → URL ⋮) ·
  WEB PAGE (dominant) · optional bottom controls (§34).
- SCR-BROWSER-002 Tab overview: grid/list previews + × + + new tab;
  tap switches (§35).
- SCR-BROWSER-003 History: list + clear; tap navigates.
- SCR-BROWSER-004 Bookmarks: list + delete; tap navigates.
- SCR-BROWSER-005 Downloads: list with status; tap opens file.
- SCR-BROWSER-006 ⋮ menu: New tab, New private tab, Bookmarks, History,
  Downloads, Find in page, Desktop site, Share, Save page, Add to
  project, Ask AI, Settings (§37).
- SCR-BROWSER-007 AI panel: bottom sheet (Summarize / Extract / Find /
  Explain selection / Save to project), browser stays visible (§38).

## Component specs

- Address bar: 48–52dp height, 24dp shape, security icon + URL +
  reload/stop; tap selects URL + keyboard; Enter navigates (§36).
- Back = web history first (§85). Tab state survives app switching (§89).
- Empty (§57): new-tab art + "Search or enter address".
- Error (§59): page-load failure with reason + [Reload] [Details].
- Offline (§60): cached pages usable; network actions explain offline.
- AI uses browser tools; Ask AI never replaces the browser (§38).

## Engine preserved (§81)

Existing tabs/history/bookmarks/WebView stack re-skinned into this
workspace; per-tab history kept (fix reset-on-switch if present).
