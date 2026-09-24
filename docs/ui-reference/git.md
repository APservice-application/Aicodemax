# UI Reference — Git

## Reference applications
MGit, Pocket Git, GitHub Mobile (layout concepts only).

## Observed
- Layout: repo header (branch/sync) · tabs (changes/commits/branches).
- Navigation: repo list → repo → change → diff.
- Controls: stage/unstage, commit, push/pull/fetch, branch switch.
- Interaction: diff hunks with stage buttons, commit form validation.
- Responsive: diff side-by-side on wide, unified on narrow.

## Adopt
Repo header + Changes/Commits/Branches/History tabs; unified diff with
removed/added/context; explicit sync actions.

## Change
AI commit-message assist + "commit this change-set" command; all ops via
git.* tools; destructive actions always confirmed.
