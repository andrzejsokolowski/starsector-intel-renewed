package intelrenewed

/**
 * Session-only state of the intel screen additions: the search text, the momentary "show hidden"
 * reveal, and whether the Customize window is open. None of this is saved anywhere; it simply
 * persists while the game runs, so closing and reopening the intel tab keeps the search text.
 */
object ViewState {
    var searchText = ""
    var showHidden = false
    var customizeOpen = false
}
