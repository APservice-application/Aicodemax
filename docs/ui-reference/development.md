# UI Reference — Development

## Reference applications
Acode, Spck Code Editor, VS Code (layout concepts only).

## Observed
- Layout: activity bar / file tree · editor tabs · panel (problems/
  terminal/git).
- Navigation: tree → tab; panel switcher at bottom.
- Controls: search/replace, symbol go-to, run, git stage/commit.
- Interaction: unsaved dot, diagnostics squiggles, autocomplete.
- Gestures: limited; mostly tap + keyboard shortcuts on desktop.
- Responsive: mobile shows one region at a time with quick switcher.

## Adopt
Tree + tabbed editor + bottom panel (Problems/Terminal/Git); Run action
in top bar; unsaved + diagnostics indicators.

## Change
Mobile-first single-focus layout with switcher; terminal as tool panel
(no Termux install); AI actions (Explain/Fix/Refactor/Generate/Test)
inline; engine = existing files/editor/git/terminal tools.
