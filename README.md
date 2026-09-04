# Intel Renewed

A Starsector utility mod that declutters the intel screen in a heavily-modded game.

## What it does

- **Hide a kind of entry** — one click on "Trait Suggestion" hides every trait suggestion, in this
  game and every later one. Kinds are named by the title their entries show on screen and grouped
  by the mod that adds them.
- **Hide a category** — a category button can be dropped from the bar on its own ("No button": the
  entries stay and still show under their other categories), or hidden together with everything
  filed under it ("Hide all"). An entry disappears as soon as one of its categories is hidden with
  everything. New, Important, Accepted and Story are markers, not categories: they can lose their
  button but never hide entries.
- **Hide empty categories** — buttons with nothing in them disappear (New, Important and Major events
  always stay, as in the base game).
- **Hide a single entry** — for that one lore entry you are done with. Stored in that save only.
- **Search** — a search box on the intel screen filters the list by title and category.
- **Popups stay quiet** — a hidden entry no longer shows its bottom-left message popup. On the game
  version the mod was built against the sound is silenced too; on other versions the card is
  removed the moment it appears.

Nothing is deleted. Entries are hidden on screen only, and the Customize window on the intel screen
lists everything hidden so it can be turned back on. The mod never touches other mods' intel.

## Where things are

- A strip in the map's top-right corner: the search box, **Customize**, and **Hide entry** /
  **Hide kind** for the selected entry, plus a **Show hidden** toggle that reveals hidden entries
  for a moment.
- The **Customize** window: every category with Show / No button / Hide all, every kind of entry with
  a Hidden toggle, the hide-empty-categories switch, and Restore everything.

## Preferences

Hidden kinds and categories are stored **once per installation**, in
`saves/common/intel_renewed/preferences.json`. Single entries you hide are stored in that save.

## Settings

Configurable through LunaLib's settings menu (`Shift+F2` in the campaign): whether hidden entries
also lose their popups, the outline colour and opacity of the Customize window, and a switch to
wipe every saved preference.

## Requirements

LazyLib and LunaLib.

## Licensing

The mod's own code is released under the MIT licence. The `intelrenewed.uiframework` package is
Starficz's UIFramework (from Refit Filters), used under the LGPL-3.0-only licence; its source is in
this repository.
