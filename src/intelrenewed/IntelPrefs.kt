package intelrenewed

import com.fs.starfarer.api.Global
import org.json.JSONArray
import org.json.JSONObject
import org.lazywizard.lazylib.JSONUtils

/**
 * Per-**installation** storage of what the player chose to hide on the intel screen.
 *
 * Backed by one JSON file in Starsector's common data folder (`saves/common/`, see [COMMON_FILE]),
 * so a choice made in one playthrough holds in every other one. Loaded lazily on first access and
 * written back on every change.
 *
 * Three things live here, all keyed by plain strings that are never resolved against the game:
 *  - **hidden kinds** — the class name of an intel entry (every "Trait Suggestion" is one class), with
 *    the display name it had when hidden, so the Customize window can still list it in a game where
 *    none of that kind exists;
 *  - **categories** — the tag ids of the buttons at the bottom of the screen, each either shown, shown
 *    without its button, or hidden with everything filed under it;
 *  - **hide empty categories** — whether zero-count buttons are dropped from the bar.
 *
 * Nothing here ever touches the intel objects themselves. See [IntelRules] for how these choices are
 * turned into what the screen shows.
 */
object IntelPrefs {

    enum class CategoryState { SHOWN, BUTTON_HIDDEN, HIDDEN }

    /** Path under `saves/common/`. Starsector appends `.data` to the file on disk. */
    private const val COMMON_FILE = "intel_renewed/preferences.json"

    private const val FORMAT_VERSION = 1

    private const val KEY_VERSION = "version"
    private const val KEY_HIDDEN_KINDS = "hiddenKinds"            // object: className -> displayName
    private const val KEY_BUTTON_HIDDEN = "categoryButtonsHidden" // array of tag ids
    private const val KEY_CATEGORIES_HIDDEN = "categoriesHidden"  // array of tag ids
    private const val KEY_CATEGORY_NAMES = "categoryNames"        // object: tag id -> display name
    private const val KEY_HIDE_EMPTY = "hideEmptyCategories"

    private val log = Global.getLogger(IntelPrefs::class.java)

    private val hiddenKindMap = LinkedHashMap<String, String>()
    private val buttonHiddenSet = LinkedHashSet<String>()
    private val categoryHiddenSet = LinkedHashSet<String>()
    private val categoryNameMap = HashMap<String, String>()
    private var hideEmpty = true

    private var loaded = false

    /** Bumped on every change; the screen re-applies its filter when it sees a new value. */
    var revision = 0
        private set

    // --- Load / save ---------------------------------------------------------------------------

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true          // set first: a failed read must not retry on every frame
        runCatching { readFile() }.onFailure {
            log.error("Intel Renewed: could not read $COMMON_FILE, starting from empty preferences.", it)
        }
    }

    private fun readFile() {
        val json: JSONObject = JSONUtils.loadCommonJSON(COMMON_FILE)
        if (json.length() == 0) return

        json.optJSONObject(KEY_HIDDEN_KINDS)?.let { obj ->
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next() as? String ?: continue
                val name = if (obj.isNull(k)) "" else obj.optString(k, "")
                if (k.isNotBlank()) hiddenKindMap[k] = name
            }
        }
        readStrings(json.optJSONArray(KEY_BUTTON_HIDDEN), buttonHiddenSet)
        readStrings(json.optJSONArray(KEY_CATEGORIES_HIDDEN), categoryHiddenSet)
        json.optJSONObject(KEY_CATEGORY_NAMES)?.let { obj ->
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next() as? String ?: continue
                if (obj.isNull(k)) continue
                val name = obj.optString(k, "")
                if (k.isNotBlank() && name.isNotBlank()) categoryNameMap[k] = name
            }
        }
        if (json.has(KEY_HIDE_EMPTY) && !json.isNull(KEY_HIDE_EMPTY)) hideEmpty = json.optBoolean(KEY_HIDE_EMPTY, true)
    }

    private fun readStrings(array: JSONArray?, into: MutableSet<String>) {
        if (array == null) return
        for (i in 0 until array.length()) {
            if (array.isNull(i)) continue
            val id = array.optString(i, "").trim()
            if (id.isNotEmpty()) into.add(id)
        }
    }

    /** Writes the whole store back. Never throws: a failed write is logged and the in-memory state
     *  stays authoritative for the rest of the session. */
    private fun save() {
        revision++
        runCatching {
            val json = JSONUtils.CommonDataJSONObject(COMMON_FILE)
            json.put(KEY_VERSION, FORMAT_VERSION)
            val kinds = JSONObject()
            for ((k, v) in hiddenKindMap) kinds.put(k, v)
            json.put(KEY_HIDDEN_KINDS, kinds)
            json.put(KEY_BUTTON_HIDDEN, JSONArray(buttonHiddenSet))
            json.put(KEY_CATEGORIES_HIDDEN, JSONArray(categoryHiddenSet))
            val names = JSONObject()
            for ((k, v) in categoryNameMap) names.put(k, v)
            json.put(KEY_CATEGORY_NAMES, names)
            json.put(KEY_HIDE_EMPTY, hideEmpty)
            json.save()
        }.onFailure {
            log.error("Intel Renewed: could not write $COMMON_FILE; this session's changes are not saved.", it)
        }
    }

    // --- Kinds ---------------------------------------------------------------------------------

    /** Read-only view: hidden kind class name -> the display name it was hidden under. */
    fun hiddenKinds(): Map<String, String> { ensureLoaded(); return hiddenKindMap }

    fun isKindHidden(kindId: String): Boolean = hiddenKinds().containsKey(kindId)

    fun setKindHidden(kindId: String, displayName: String, hidden: Boolean) {
        ensureLoaded()
        if (hidden) {
            hiddenKindMap[kindId] = displayName
            save()
        } else if (hiddenKindMap.remove(kindId) != null) {
            save()
        }
    }

    // --- Categories ----------------------------------------------------------------------------

    fun categoryState(tagId: String): CategoryState {
        ensureLoaded()
        return when {
            categoryHiddenSet.contains(tagId) -> CategoryState.HIDDEN
            buttonHiddenSet.contains(tagId) -> CategoryState.BUTTON_HIDDEN
            else -> CategoryState.SHOWN
        }
    }

    fun setCategoryState(tagId: String, displayName: String, state: CategoryState) {
        ensureLoaded()
        buttonHiddenSet.remove(tagId)
        categoryHiddenSet.remove(tagId)
        when (state) {
            CategoryState.BUTTON_HIDDEN -> buttonHiddenSet.add(tagId)
            CategoryState.HIDDEN -> categoryHiddenSet.add(tagId)
            CategoryState.SHOWN -> {}
        }
        if (state == CategoryState.SHOWN) categoryNameMap.remove(tagId)
        else if (displayName.isNotBlank()) categoryNameMap[tagId] = displayName
        save()
    }

    /** Tag ids whose button is dropped from the bar (entries stay). */
    fun buttonHiddenCategories(): Set<String> { ensureLoaded(); return buttonHiddenSet }

    /** Tag ids hidden together with everything filed under them. */
    fun hiddenCategories(): Set<String> { ensureLoaded(); return categoryHiddenSet }

    /** Every category with a remembered state, with the display name it had at the time. */
    fun rememberedCategoryNames(): Map<String, String> { ensureLoaded(); return categoryNameMap }

    var hideEmptyCategories: Boolean
        get() { ensureLoaded(); return hideEmpty }
        set(value) { ensureLoaded(); if (hideEmpty != value) { hideEmpty = value; save() } }

    // --- Maintenance ---------------------------------------------------------------------------

    /** True when no kind or category is hidden. */
    fun nothingHidden(): Boolean {
        ensureLoaded()
        return hiddenKindMap.isEmpty() && buttonHiddenSet.isEmpty() && categoryHiddenSet.isEmpty()
    }

    /** Erases every stored choice and writes the now-empty file. */
    fun wipeAll() {
        ensureLoaded()
        hiddenKindMap.clear()
        buttonHiddenSet.clear()
        categoryHiddenSet.clear()
        categoryNameMap.clear()
        hideEmpty = true
        save()
        log.info("Intel Renewed: wiped all saved preferences.")
    }
}
