# THIRD-PARTY SOFTWARE

## Termux (terminal emulator application for Android)
- Source: https://github.com/termux/termux-app
- License: GNU General Public License v3.0 only (GPLv3)
- Used as: embedded terminal stack via pinned git submodule + own integration
  module (see `docs/TERMINAL_PLAN.md`, AMENDMENT-001/002)
- Copyright: Termux contributors (see upstream repository for full notices)

## Eclipse JGit (pure-Java git implementation)
- Source: https://www.eclipse.org/jgit/
- License: Eclipse Distribution License v1.0 (BSD-3-Clause style)
- Used as: Maven dependency `org.eclipse.jgit` (version pinned in `gradle/libs.versions.toml`),
  wrapped by our `GitPort` interface (`tools/git`) — see `JGitGitPort.kt`
- Copyright: Eclipse Foundation and JGit contributors

## Android-Terminal-Emulator (terminal lineage via Termux)
- Source: https://github.com/jackpal/Android-Terminal-Emulator
- License: Apache License 2.0
- Copyright: Jack Palevich and contributors

## GNU GPL v3 license text
- Full text in `LICENSE` at repo root, or https://www.gnu.org/licenses/gpl-3.0.txt
