package com.knewman.hoopstats

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Stat keys tracked per game. Shooting stats store makes ("_m") and attempts ("_a"). */
val STAT_KEYS = listOf("fg2_m", "fg2_a", "fg3_m", "fg3_a", "ft_m", "ft_a", "reb", "ast", "stl", "blk", "to")
val POSITIONS = listOf("PG", "SG", "SF", "PF", "C", "G", "F")

fun blankStats() = HashMap<String, Int>().apply { STAT_KEYS.forEach { put(it, 0) } }
fun Map<String, Int>.v(k: String) = this[k] ?: 0
fun points(s: Map<String, Int>) = 2 * s.v("fg2_m") + 3 * s.v("fg3_m") + s.v("ft_m")
fun newId() = UUID.randomUUID().toString()
fun nowStamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

class Player(val id: String, var name: String, var position: String, var team: String, var number: String) {
    fun label() = (if (number.isNotBlank()) "#$number " else "") + name
    fun details() = listOf(position, team).filter { it.isNotBlank() }.joinToString(" · ")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("position", position).put("team", team).put("number", number)

    companion object {
        fun from(o: JSONObject) = Player(
            o.getString("id"), o.optString("name"), o.optString("position"),
            o.optString("team"), o.optString("number")
        )
    }
}

class Game(val id: String, var playerId: String, var date: String, var opponent: String, val stats: HashMap<String, Int>) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("id", id).put("playerId", playerId).put("date", date).put("opponent", opponent)
        STAT_KEYS.forEach { o.put(it, stats.v(it)) }
        return o
    }

    fun title() = date + if (opponent.isNotBlank()) "  vs $opponent" else ""

    companion object {
        fun from(o: JSONObject) = Game(
            o.optString("id").ifEmpty { newId() },
            o.optString("playerId"),
            o.optString("date"),
            o.optString("opponent").ifEmpty { o.optString("note") },
            HashMap<String, Int>().apply { STAT_KEYS.forEach { put(it, o.optInt(it)) } }
        )
    }
}

class Store(private val prefs: SharedPreferences) {
    val players = mutableListOf<Player>()
    val games = mutableListOf<Game>()

    init {
        val p = JSONArray(prefs.getString("players", "[]"))
        for (i in 0 until p.length()) players.add(Player.from(p.getJSONObject(i)))
        val g = JSONArray(prefs.getString("games", "[]"))
        for (i in 0 until g.length()) games.add(Game.from(g.getJSONObject(i)))

        // v1 games had no player; give them to a default player so nothing is lost.
        val orphans = games.filter { it.playerId.isBlank() || player(it.playerId) == null }
        if (orphans.isNotEmpty()) {
            val me = Player(newId(), "Me", "", "", "")
            players.add(me)
            orphans.forEach { it.playerId = me.id }
            save()
        }
    }

    fun player(id: String?) = players.find { it.id == id }
    fun gamesFor(playerId: String) = games.filter { it.playerId == playerId }.sortedBy { it.date }

    fun save() {
        prefs.edit()
            .putString("players", JSONArray(players.map { it.toJson() }).toString())
            .putString("games", JSONArray(games.map { it.toJson() }).toString())
            .apply()
    }

    fun saveDraft(draft: JSONObject?) {
        prefs.edit().apply { if (draft == null) remove("draft") else putString("draft", draft.toString()) }.apply()
    }

    fun loadDraft(): JSONObject? = prefs.getString("draft", null)?.let { JSONObject(it) }
}
