# Intel Renewed

Declutters the campaign intel screen: hide kinds of entries, categories and single entries, mute
their popups, search the list. These instructions apply to this repository, including when it is
opened without the parent mod workspace. The workspace `AGENTS.md` one folder up governs releases,
packaging, Git identity and communication.

## Notion

Tasks, history and test lists: the **Intel Renewed board** in Andrzej's Notion, under
**Starsector mods** (`collection://c400652e-56af-4e2b-8c37-1781ec0b68fa`). Working rules and game
API notes, including "Game API: the intel screen": **Claude Information**
(`collection://7bf9b1d6-b9e5-4b62-87f1-757bbaa51919`), starting with "Start here: working on
Starsector mods through Notion". Read the board before working, give every task a ticket (ticket
ids start with IR) and keep it current. Intel Renewed is also bundled in Coalesced Little Mods:
bring every change over there too. The Notion tools are deferred: load them with ToolSearch first.

Update the board during the work, not only at release time. Before changing files, put the
message on its ticket: a new request gets a new ticket (In progress), and a problem Andrzej
reports on Needs Human Test work moves that ticket to Changes Requested with the notes quoted.
Before ending the turn, update the ticket's body (What was done, How, Commits, Test list) and
its Status, Version and End date. Read the board with `notion-query-data-sources` mode `view`
on `view://3fd20ca7-5d20-81ac-866b-000c55704268` or `notion-fetch` a ticket. SQL and rows mode share a
small quota that runs out. The workspace `AGENTS.md` has the full routine.

## Essentials

- id `intel_renewed`, package `intelrenewed`, Kotlin and Gradle, LazyLib and LunaLib.
- Build: `./gradlew jar` (runs `checkNoForbiddenApis`). Package: `python package.py` stages
  `IntelRenewed/` and writes `Intel-Renewed.zip`.
- Never touch other mods' intel objects (no `removeIntel`, no `setHidden`). Hide on screen only.
- The staging folder ignore rule is root anchored (`/IntelRenewed/`): keep it that way.

## Update metadata

Keep `mod_info.json`, `intel_renewed.version` and `data/config/version/version_files.csv`
consistent. Forum topic 35978.

- updateCheckURL and masterVersionFile:
  `https://raw.githubusercontent.com/andrzejsokolowski/starsector-intel-renewed/main/intel_renewed.version`
- directDownloadURL, asset `Intel-Renewed.zip`:
  `https://github.com/andrzejsokolowski/starsector-intel-renewed/releases/latest/download/Intel-Renewed.zip`
