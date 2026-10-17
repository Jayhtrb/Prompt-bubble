package com.justin.promptbubble

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One thing you collected: a block of text, or a screenshot (stored as a MediaStore URI). */
data class TrayItem(
    val id: Long,
    val type: String,
    val text: String,
    val uri: String?,
    val source: String,
    val time: Long,
) {
    val isImage get() = type == TYPE_IMAGE

    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
    }
}

/** Small JSON-file store for the tray. Main-thread only. */
object TrayStore {
    private var cache: MutableList<TrayItem>? = null
    private val listeners = linkedSetOf<() -> Unit>()

    private fun file(ctx: Context) = File(ctx.filesDir, "tray.json")

    private fun load(ctx: Context): MutableList<TrayItem> {
        cache?.let { return it }
        val list = mutableListOf<TrayItem>()
        val f = file(ctx)
        if (f.exists()) runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += TrayItem(
                    id = o.getLong("id"),
                    type = o.getString("type"),
                    text = o.optString("text"),
                    uri = o.optString("uri").ifEmpty { null },
                    source = o.optString("source"),
                    time = o.getLong("time"),
                )
            }
        }
        cache = list
        return list
    }

    private fun save(ctx: Context) {
        val arr = JSONArray()
        load(ctx).forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("type", it.type); put("text", it.text)
                put("uri", it.uri ?: ""); put("source", it.source); put("time", it.time)
            })
        }
        file(ctx).writeText(arr.toString())
        listeners.toList().forEach { it() }
    }

    private fun nextId(ctx: Context) = (load(ctx).maxOfOrNull { it.id } ?: 0L) + 1

    fun all(ctx: Context): List<TrayItem> = load(ctx).toList()
    fun count(ctx: Context) = load(ctx).size

    fun addText(ctx: Context, source: String, text: String) {
        load(ctx) += TrayItem(nextId(ctx), TrayItem.TYPE_TEXT, text, null, source, System.currentTimeMillis())
        save(ctx)
    }

    fun addImage(ctx: Context, source: String, uri: String) {
        load(ctx) += TrayItem(nextId(ctx), TrayItem.TYPE_IMAGE, "", uri, source, System.currentTimeMillis())
        save(ctx)
    }

    fun updateText(ctx: Context, id: Long, text: String) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) { list[i] = list[i].copy(text = text); save(ctx) }
    }

    fun remove(ctx: Context, id: Long) {
        if (load(ctx).removeAll { it.id == id }) save(ctx)
    }

    fun clear(ctx: Context) {
        load(ctx).clear(); save(ctx)
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** Joins everything into one prompt, with your instruction on top. */
    fun buildPrompt(ctx: Context, instruction: String): String = buildString {
        val ins = instruction.trim()
        if (ins.isNotEmpty()) append(ins).append("\n\n")
        var shot = 0
        load(ctx).forEach {
            if (it.isImage) {
                shot++
                append("[Screenshot $shot from ${it.source} — attached]\n\n")
            } else {
                append("--- From ${it.source} ---\n").append(it.text.trim()).append("\n\n")
            }
        }
    }.trim()
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    fun bubbleOn(c: Context) = sp(c).getBoolean("bubble_on", true)
    fun setBubbleOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("bubble_on", v).apply()
    fun instruction(c: Context) = sp(c).getString("instruction", "") ?: ""
    fun setInstruction(c: Context, v: String) = sp(c).edit().putString("instruction", v).apply()
}
