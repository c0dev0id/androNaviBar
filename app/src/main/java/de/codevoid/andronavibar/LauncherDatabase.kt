package de.codevoid.andronavibar

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class CollectionItem(
    val id: Long = 0,
    val buttonPosition: Int,
    val sortOrder: Int,
    val label: String,
    val uri: String,
    val openBrowser: Boolean = false
)

data class ButtonRow(
    val type: String? = null,
    val value: String? = null,
    val label: String? = null,
    val widgetId: Int? = null,
    val openBrowser: Boolean = false,
    val iconType: String? = null,
    val iconData: String? = null
)

class LauncherDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "launcher.db"
        private const val DB_VERSION = 2

        @Volatile
        private var instance: LauncherDatabase? = null

        fun getInstance(context: Context): LauncherDatabase =
            instance ?: synchronized(this) {
                instance ?: LauncherDatabase(context).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE settings (
                key   TEXT PRIMARY KEY,
                value TEXT
            )"""
        )
        db.execSQL(
            """CREATE TABLE buttons (
                position     INTEGER PRIMARY KEY,
                type         TEXT,
                value        TEXT,
                label        TEXT,
                active       INTEGER NOT NULL DEFAULT 1,
                widget_id    INTEGER,
                open_browser INTEGER NOT NULL DEFAULT 0,
                icon_type    TEXT,
                icon_data    TEXT
            )"""
        )
        db.execSQL(
            """CREATE TABLE collection_items (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                button_position INTEGER NOT NULL,
                sort_order      INTEGER NOT NULL DEFAULT 0,
                label           TEXT,
                uri             TEXT,
                open_browser    INTEGER NOT NULL DEFAULT 0
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS collection_items (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    button_position INTEGER NOT NULL,
                    sort_order      INTEGER NOT NULL DEFAULT 0,
                    label           TEXT,
                    uri             TEXT,
                    open_browser    INTEGER NOT NULL DEFAULT 0
                )"""
            )
        }
    }

    // ── Settings ────────────────────────────────────────────────────────────

    private fun getSetting(key: String): String? {
        return readableDatabase.rawQuery(
            "SELECT value FROM settings WHERE key = ?", arrayOf(key)
        ).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun setSetting(key: String, value: String?) {
        val db = writableDatabase
        if (value != null) {
            val cv = ContentValues(2)
            cv.put("key", key)
            cv.put("value", value)
            db.insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            db.delete("settings", "key = ?", arrayOf(key))
        }
    }

    fun getFocusedIndex(): Int = getSetting("focused_index")?.toIntOrNull() ?: 0

    fun setFocusedIndex(index: Int) = setSetting("focused_index", index.toString())

    fun getPendingWidgetId(): Int = getSetting("pending_widget_id")?.toIntOrNull() ?: -1

    fun setPendingWidgetId(id: Int?) =
        setSetting("pending_widget_id", id?.toString())

    fun getAppsFilterOn(): Boolean = getSetting("apps_filter_on") != "0"

    fun setAppsFilterOn(on: Boolean) = setSetting("apps_filter_on", if (on) "1" else "0")

    fun getHiddenPackages(): Set<String> {
        val raw = getSetting("apps_hidden_pkgs") ?: return emptySet()
        if (raw.isEmpty()) return emptySet()
        return raw.split(",").toSet()
    }

    fun setHiddenPackages(pkgs: Set<String>) =
        setSetting("apps_hidden_pkgs", if (pkgs.isEmpty()) null else pkgs.joinToString(","))

    // ── Buttons ─────────────────────────────────────────────────────────────

    fun getButtonCount(): Int {
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM buttons", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun loadButton(position: Int): ButtonRow? {
        return readableDatabase.rawQuery(
            "SELECT type, value, label, widget_id, open_browser, icon_type, icon_data FROM buttons WHERE position = ?",
            arrayOf(position.toString())
        ).use { c ->
            if (!c.moveToFirst()) return@use null
            ButtonRow(
                type = c.getString(0),
                value = c.getString(1),
                label = c.getString(2),
                widgetId = if (c.isNull(3)) null else c.getInt(3),
                openBrowser = c.getInt(4) != 0,
                iconType = c.getString(5),
                iconData = c.getString(6)
            )
        }
    }

    fun saveButton(position: Int, row: ButtonRow) {
        val cv = ContentValues(8).apply {
            put("position", position)
            put("type", row.type)
            put("value", row.value)
            put("label", row.label)
            if (row.widgetId != null) put("widget_id", row.widgetId) else putNull("widget_id")
            put("open_browser", if (row.openBrowser) 1 else 0)
            put("icon_type", row.iconType)
            put("icon_data", row.iconData)
        }
        writableDatabase.insertWithOnConflict("buttons", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearButton(position: Int) {
        saveButton(position, ButtonRow())
    }

    fun getButtonLabel(position: Int): String {
        return readableDatabase.rawQuery(
            "SELECT label FROM buttons WHERE position = ?",
            arrayOf(position.toString())
        ).use { c ->
            if (c.moveToFirst()) c.getString(0) ?: "" else ""
        }
    }

    fun addButton(): Int {
        val pos = getButtonCount()
        saveButton(pos, ButtonRow())
        return pos
    }

    fun removeButton(position: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("buttons", "position = ?", arrayOf(position.toString()))
            db.execSQL("UPDATE buttons SET position = position - 1 WHERE position > ?",
                arrayOf(position.toString()))
            // Cascade: delete items for removed button, re-index items for shifted buttons
            db.delete("collection_items", "button_position = ?", arrayOf(position.toString()))
            db.execSQL("UPDATE collection_items SET button_position = button_position - 1 WHERE button_position > ?",
                arrayOf(position.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun moveButton(from: Int, to: Int) {
        if (from == to) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Temporarily park `from` at -1
            db.execSQL("UPDATE buttons SET position = -1 WHERE position = ?",
                arrayOf(from.toString()))
            db.execSQL("UPDATE collection_items SET button_position = -1 WHERE button_position = ?",
                arrayOf(from.toString()))
            if (from < to) {
                db.execSQL(
                    "UPDATE buttons SET position = position - 1 WHERE position > ? AND position <= ?",
                    arrayOf(from.toString(), to.toString()))
                db.execSQL(
                    "UPDATE collection_items SET button_position = button_position - 1 WHERE button_position > ? AND button_position <= ?",
                    arrayOf(from.toString(), to.toString()))
            } else {
                db.execSQL(
                    "UPDATE buttons SET position = position + 1 WHERE position >= ? AND position < ?",
                    arrayOf(to.toString(), from.toString()))
                db.execSQL(
                    "UPDATE collection_items SET button_position = button_position + 1 WHERE button_position >= ? AND button_position < ?",
                    arrayOf(to.toString(), from.toString()))
            }
            db.execSQL("UPDATE buttons SET position = ? WHERE position = -1",
                arrayOf(to.toString()))
            db.execSQL("UPDATE collection_items SET button_position = ? WHERE button_position = -1",
                arrayOf(to.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ── Collection items ─────────────────────────────────────────────────────

    fun getCollectionItems(buttonPosition: Int): List<CollectionItem> {
        return readableDatabase.rawQuery(
            "SELECT id, sort_order, label, uri, open_browser FROM collection_items WHERE button_position = ? ORDER BY sort_order",
            arrayOf(buttonPosition.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(CollectionItem(
                        id             = c.getLong(0),
                        buttonPosition = buttonPosition,
                        sortOrder      = c.getInt(1),
                        label          = c.getString(2) ?: "",
                        uri            = c.getString(3) ?: "",
                        openBrowser    = c.getInt(4) != 0
                    ))
                }
            }
        }
    }

    fun addCollectionItem(item: CollectionItem): Long {
        val cv = ContentValues(5).apply {
            put("button_position", item.buttonPosition)
            put("sort_order", item.sortOrder)
            put("label", item.label)
            put("uri", item.uri)
            put("open_browser", if (item.openBrowser) 1 else 0)
        }
        return writableDatabase.insert("collection_items", null, cv)
    }

    fun updateCollectionItem(item: CollectionItem) {
        val cv = ContentValues(4).apply {
            put("label", item.label)
            put("uri", item.uri)
            put("open_browser", if (item.openBrowser) 1 else 0)
            put("sort_order", item.sortOrder)
        }
        writableDatabase.update("collection_items", cv, "id = ?", arrayOf(item.id.toString()))
    }

    fun deleteCollectionItem(id: Long) {
        writableDatabase.delete("collection_items", "id = ?", arrayOf(id.toString()))
    }

    // ── Migration from SharedPreferences ────────────────────────────────────

    fun migrateFromPrefsIfNeeded(context: Context) {
        if (getSetting("_migrated") == "1") return

        val prefs = context.getSharedPreferences(LauncherApplication.PREFS_NAME, Context.MODE_PRIVATE)
        // If prefs are empty and DB already has buttons, skip (fresh install after migration)
        if (!prefs.contains("button_count") && getButtonCount() > 0) {
            setSetting("_migrated", "1")
            return
        }
        // If prefs are empty and DB is empty, nothing to migrate
        if (!prefs.contains("button_count") && getButtonCount() == 0) {
            setSetting("_migrated", "1")
            return
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            // Migrate settings
            val focusedIndex = prefs.getInt("focused_index", 0)
            setSetting("focused_index", focusedIndex.toString())

            val pendingWidgetId = prefs.getInt("pending_widget_id", -1)
            if (pendingWidgetId != -1) {
                setSetting("pending_widget_id", pendingWidgetId.toString())
            }

            val filterOn = prefs.getBoolean("apps_filter_on", true)
            setSetting("apps_filter_on", if (filterOn) "1" else "0")

            val hidden = prefs.getStringSet("apps_hidden_pkgs", emptySet()) ?: emptySet()
            if (hidden.isNotEmpty()) {
                setSetting("apps_hidden_pkgs", hidden.joinToString(","))
            }

            // Migrate buttons
            val count = prefs.getInt("button_count", 6)
            for (i in 0 until count) {
                val cv = ContentValues(9).apply {
                    put("position", i)
                    put("type", prefs.getString("btn_${i}_type", null))
                    put("value", prefs.getString("btn_${i}_value", null))
                    put("label", prefs.getString("btn_${i}_label", null))
                    put("active", if (prefs.getBoolean("btn_${i}_active", true)) 1 else 0)
                    val wid = prefs.getString("btn_${i}_widget_id", null)?.toIntOrNull()
                    if (wid != null) put("widget_id", wid) else putNull("widget_id")
                    put("open_browser", if (prefs.getString("btn_${i}_open_browser", null) == "true") 1 else 0)
                    put("icon_type", prefs.getString("btn_${i}_icon_type", null))
                    put("icon_data", prefs.getString("btn_${i}_icon_data", null))
                }
                db.insertWithOnConflict("buttons", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }

            setSetting("_migrated", "1")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // Clear old SharedPreferences
        prefs.edit().clear().apply()
    }
}
