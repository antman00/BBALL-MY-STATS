package com.knewman.hoopstats

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stat keys tracked per game. Shooting stats store makes ("_m") and attempts ("_a"). */
private val KEYS = listOf("fg2_m", "fg2_a", "fg3_m", "fg3_a", "ft_m", "ft_a", "reb", "ast", "stl", "blk", "to")

class MainActivity : Activity() {
    private val current = HashMap<String, Int>().apply { KEYS.forEach { put(it, 0) } }
    private val undoStack = ArrayDeque<List<String>>()
    private lateinit var boxScore: TextView
    private lateinit var historyView: LinearLayout

    private val prefs by lazy { getSharedPreferences("hoopstats", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("current")?.let { s ->
            val o = JSONObject(s); KEYS.forEach { current[it] = o.optInt(it) }
        }
        buildUi()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("current", JSONObject(current as Map<*, *>).toString())
    }

    // ---------- UI ----------
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.parseColor("#121212"))
        }
        root.addView(label("Hoop Stats", 26f, bold = true))

        boxScore = label("", 18f).apply { setPadding(0, 16, 0, 16) }
        root.addView(boxScore)

        val grid = GridLayout(this).apply { columnCount = 2 }
        fun add(text: String, color: String, vararg keys: String) {
            grid.addView(Button(this).apply {
                this.text = text
                textSize = 16f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor(color))
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply { width = 0; height = 150; setMargins(8, 8, 8, 8) }
                setOnClickListener { record(*keys) }
            })
        }
        val green = "#2E7D32"; val red = "#8E2424"; val blue = "#1E4E8C"
        add("2PT Make", green, "fg2_m", "fg2_a"); add("2PT Miss", red, "fg2_a")
        add("3PT Make", green, "fg3_m", "fg3_a"); add("3PT Miss", red, "fg3_a")
        add("FT Make", green, "ft_m", "ft_a"); add("FT Miss", red, "ft_a")
        add("Rebound", blue, "reb"); add("Assist", blue, "ast")
        add("Steal", blue, "stl"); add("Block", blue, "blk")
        add("Turnover", "#6A4C93", "to")
        grid.addView(Button(this).apply {
            text = "Undo"; textSize = 16f
            layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply { width = 0; height = 150; setMargins(8, 8, 8, 8) }
            setOnClickListener { undo() }
        })
        root.addView(grid)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "Save Game"
            layoutParams = LinearLayout.LayoutParams(0, 140, 1f)
            setOnClickListener { saveGame() }
        })
        actions.addView(Button(this).apply {
            text = "Reset"
            layoutParams = LinearLayout.LayoutParams(0, 140, 1f)
            setOnClickListener { confirm("Clear current game?") { resetCurrent() } }
        })
        root.addView(actions)

        root.addView(label("History", 20f, bold = true).apply { setPadding(0, 32, 0, 8) })
        historyView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(historyView)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212")); addView(root)
        })
    }

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    // ---------- Actions ----------
    private fun record(vararg keys: String) {
        keys.forEach { current[it] = current.getValue(it) + 1 }
        undoStack.addLast(keys.toList())
        refresh()
    }

    private fun undo() {
        val last = undoStack.removeLastOrNull() ?: return
        last.forEach { current[it] = maxOf(0, current.getValue(it) - 1) }
        refresh()
    }

    private fun resetCurrent() {
        KEYS.forEach { current[it] = 0 }; undoStack.clear(); refresh()
    }

    private fun saveGame() {
        val input = EditText(this).apply { hint = "Opponent / note (optional)" }
        AlertDialog.Builder(this)
            .setTitle("Save game")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val game = JSONObject(current as Map<*, *>)
                game.put("date", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
                game.put("note", input.text.toString().trim())
                val all = loadGames().put(game)
                prefs.edit().putString("games", all.toString()).apply()
                resetCurrent()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteGame(index: Int) {
        val all = loadGames()
        all.remove(index)
        prefs.edit().putString("games", all.toString()).apply()
        refresh()
    }

    private fun confirm(msg: String, action: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("Yes") { _, _ -> action() }
            .setNegativeButton("No", null).show()
    }

    // ---------- Display ----------
    private fun loadGames(): JSONArray = JSONArray(prefs.getString("games", "[]"))

    private fun points(g: (String) -> Int) = g("fg2_m") * 2 + g("fg3_m") * 3 + g("ft_m")

    private fun pct(m: Int, a: Int) = if (a == 0) "-" else "${(m * 100 / a)}%"

    private fun summary(g: (String) -> Int): String {
        val fgm = g("fg2_m") + g("fg3_m"); val fga = g("fg2_a") + g("fg3_a")
        return "PTS ${points(g)}   REB ${g("reb")}   AST ${g("ast")}\n" +
            "STL ${g("stl")}   BLK ${g("blk")}   TO ${g("to")}\n" +
            "FG $fgm/$fga (${pct(fgm, fga)})   3PT ${g("fg3_m")}/${g("fg3_a")} (${pct(g("fg3_m"), g("fg3_a"))})   " +
            "FT ${g("ft_m")}/${g("ft_a")} (${pct(g("ft_m"), g("ft_a"))})"
    }

    private fun refresh() {
        boxScore.text = "Current game\n" + summary { current.getValue(it) }

        historyView.removeAllViews()
        val games = loadGames()
        if (games.length() == 0) {
            historyView.addView(label("No saved games yet.", 14f)); return
        }

        // Season averages
        val n = games.length()
        val totals = KEYS.associateWith { k -> (0 until n).sumOf { games.getJSONObject(it).optInt(k) } }
        val avg = { k: String -> totals.getValue(k).toDouble() / n }
        val fgm = totals.getValue("fg2_m") + totals.getValue("fg3_m")
        val fga = totals.getValue("fg2_a") + totals.getValue("fg3_a")
        historyView.addView(label(
            "Averages over $n game${if (n == 1) "" else "s"}\n" +
                "PTS %.1f   REB %.1f   AST %.1f\nSTL %.1f   BLK %.1f   TO %.1f\n".format(
                    (2 * totals.getValue("fg2_m") + 3 * totals.getValue("fg3_m") + totals.getValue("ft_m")).toDouble() / n,
                    avg("reb"), avg("ast"), avg("stl"), avg("blk"), avg("to")
                ) +
                "FG ${pct(fgm, fga)}   3PT ${pct(totals.getValue("fg3_m"), totals.getValue("fg3_a"))}   " +
                "FT ${pct(totals.getValue("ft_m"), totals.getValue("ft_a"))}",
            15f, bold = true
        ).apply { setPadding(0, 0, 0, 24); setTextColor(Color.parseColor("#FFB300")) })

        for (i in n - 1 downTo 0) {
            val game = games.getJSONObject(i)
            val note = game.optString("note")
            val title = game.optString("date") + if (note.isNotEmpty()) "  vs $note" else ""
            historyView.addView(label("$title\n" + summary { game.optInt(it) }, 14f).apply {
                setPadding(16, 16, 16, 16)
                gravity = Gravity.START
                setBackgroundColor(Color.parseColor("#1F1F1F"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 12) }
                setOnLongClickListener { confirm("Delete this game?") { deleteGame(i) }; true }
            })
        }
    }
}
