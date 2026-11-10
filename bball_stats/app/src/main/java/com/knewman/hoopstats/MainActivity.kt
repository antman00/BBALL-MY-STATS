package com.knewman.hoopstats

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale

private val BG = Color.parseColor("#0E1116")
private val CARD = Color.parseColor("#1A1F29")
private val TILE = Color.parseColor("#232A36")
private val ACCENT = Color.parseColor("#FF7A1A")
private val GREEN = Color.parseColor("#22A55B")
private val RED = Color.parseColor("#D9473F")
private val BLUE = Color.parseColor("#3B6FD8")
private val PURPLE = Color.parseColor("#7B5CD6")
private val SLATE = Color.parseColor("#3A4252")
private val MUTED = Color.parseColor("#8B93A1")
private const val WHITE = Color.WHITE
private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private val tabButtons = mutableListOf<TextView>()
    private var tab = 0

    // Tracking session
    private var activePlayerId: String? = null
    private var activeGameId: String? = null
    private var opponent = ""
    private val current = blankStats()
    private val undoStack = ArrayDeque<List<String>>()
    private var ptsView: TextView? = null
    private var lineView: TextView? = null

    private var analysisPlayerId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(getSharedPreferences("hoopstats", MODE_PRIVATE))
        restoreDraft()
        buildShell()
        showTab(if (activePlayerId != null || store.players.isNotEmpty()) 0 else 1)
    }

    // ---------- Shell ----------
    private fun buildShell() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }

        root.addView(text("Hoop Stats", 24f, ACCENT, bold = true).apply {
            setPadding(dp(20), dp(16), dp(20), dp(8))
        })

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        scroll = ScrollView(this).apply {
            addView(content)
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        root.addView(scroll)

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(CARD)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        listOf("Track", "Players", "Analysis").forEachIndexed { i, name ->
            val b = text(name, 15f, MUTED, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                setOnClickListener { showTab(i) }
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
            }
            tabButtons.add(b); nav.addView(b)
        }
        root.addView(nav)
        setContentView(root)
    }

    private fun showTab(i: Int) {
        tab = i
        tabButtons.forEachIndexed { idx, b ->
            val selected = idx == i
            b.background = if (selected) ripple(ACCENT, 14) else ripple(CARD, 14)
            b.setTextColor(if (selected) WHITE else MUTED)
        }
        content.removeAllViews()
        ptsView = null; lineView = null
        when (i) {
            0 -> renderTrack()
            1 -> renderPlayers()
            else -> renderAnalysis()
        }
        scroll.scrollTo(0, 0)
    }

    // ---------- Track tab ----------
    private fun renderTrack() {
        if (store.players.isEmpty()) {
            content.addView(card().apply {
                addView(text("No players yet", 18f, bold = true))
                addView(text("Add a player to start tracking games.", 14f, MUTED).spaced())
                addView(button("Add Player", ACCENT) { showTab(1); editPlayer(null) })
            })
            return
        }
        val player = store.player(activePlayerId)
        if (player == null) {
            activePlayerId = null; renderSetup()
        } else {
            renderSession(player)
        }
    }

    private fun renderSetup() {
        val c = card()
        c.addView(text("Start or load a game", 18f, bold = true).spaced())

        c.addView(fieldLabel("Player"))
        val playerSpinner = spinner(store.players.map { p ->
            p.label() + p.details().let { if (it.isNotBlank()) "  ($it)" else "" }
        })
        c.addView(playerSpinner)

        c.addView(fieldLabel("Game"))
        val gameSpinner = Spinner(this)
        c.addView(gameSpinner)

        val oppInput = input("Opponent (optional)")
        c.addView(oppInput)

        var gameOptions: List<Game?> = listOf(null)
        fun loadGameOptions(pos: Int) {
            val saved = store.gamesFor(store.players[pos].id).reversed()
            gameOptions = listOf<Game?>(null) + saved
            gameSpinner.adapter = adapter(gameOptions.map { g ->
                if (g == null) "+ New game" else "${g.title()}  ·  ${points(g.stats)} pts"
            })
        }
        playerSpinner.onItemSelectedListener = onSelect { pos -> loadGameOptions(pos) }
        gameSpinner.onItemSelectedListener = onSelect { pos ->
            oppInput.visibility = if (gameOptions.getOrNull(pos) == null) View.VISIBLE else View.GONE
        }
        loadGameOptions(0)

        c.addView(button("Start Tracking", ACCENT) {
            val p = store.players[playerSpinner.selectedItemPosition]
            val g = gameOptions.getOrNull(gameSpinner.selectedItemPosition)
            startSession(p, g, oppInput.text.toString().trim())
        }.topMargin(16))
        content.addView(c)
    }

    private fun startSession(p: Player, g: Game?, opp: String) {
        activePlayerId = p.id
        activeGameId = g?.id
        opponent = g?.opponent ?: opp
        STAT_KEYS.forEach { current[it] = g?.stats?.v(it) ?: 0 }
        undoStack.clear()
        saveDraft()
        showTab(0)
    }

    private fun renderSession(player: Player) {
        content.addView(card().apply {
            addView(text(if (activeGameId == null) "NEW GAME" else "EDITING SAVED GAME", 12f, ACCENT, bold = true))
            addView(text(player.label(), 22f, bold = true))
            val sub = listOf(player.details(), if (opponent.isNotBlank()) "vs $opponent" else "")
                .filter { it.isNotBlank() }.joinToString("  ·  ")
            if (sub.isNotBlank()) addView(text(sub, 14f, MUTED))
        })

        content.addView(card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            ptsView = text("0", 48f, ACCENT, bold = true).apply { gravity = Gravity.CENTER }
            addView(ptsView)
            addView(text("POINTS", 12f, MUTED, bold = true).apply { gravity = Gravity.CENTER })
            lineView = text("", 14f).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
            addView(lineView)
        })

        fun statRow(l1: String, c1: Int, k1: List<String>, l2: String, c2: Int, k2: List<String>?) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(statButton(l1, c1) { record(k1) }.weighted())
            row.addView(
                if (k2 == null) statButton(l2, c2) { undo() }.weighted()
                else statButton(l2, c2) { record(k2) }.weighted()
            )
            content.addView(row)
        }
        statRow("2PT Make", GREEN, listOf("fg2_m", "fg2_a"), "2PT Miss", RED, listOf("fg2_a"))
        statRow("3PT Make", GREEN, listOf("fg3_m", "fg3_a"), "3PT Miss", RED, listOf("fg3_a"))
        statRow("FT Make", GREEN, listOf("ft_m", "ft_a"), "FT Miss", RED, listOf("ft_a"))
        statRow("Rebound", BLUE, listOf("reb"), "Assist", BLUE, listOf("ast"))
        statRow("Steal", BLUE, listOf("stl"), "Block", BLUE, listOf("blk"))
        statRow("Turnover", PURPLE, listOf("to"), "Undo", SLATE, null)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0)
        }
        actions.addView(button("Save Game", ACCENT) { saveSession() }.weighted())
        actions.addView(button("Close", SLATE) {
            confirm("Close this game? Unsaved changes will be lost.") { endSession() }
        }.weighted())
        content.addView(actions)
        refreshScore()
    }

    private fun record(keys: List<String>) {
        keys.forEach { current[it] = current.v(it) + 1 }
        undoStack.addLast(keys)
        content.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        saveDraft(); refreshScore()
    }

    private fun undo() {
        val last = undoStack.removeLastOrNull() ?: return
        last.forEach { current[it] = maxOf(0, current.v(it) - 1) }
        saveDraft(); refreshScore()
    }

    private fun refreshScore() {
        ptsView?.text = points(current).toString()
        lineView?.text = statLine(current)
    }

    private fun saveSession() {
        val pid = activePlayerId ?: return
        val existing = store.games.find { it.id == activeGameId }
        if (existing != null) {
            STAT_KEYS.forEach { existing.stats[it] = current.v(it) }
            existing.opponent = opponent
        } else {
            store.games.add(Game(newId(), pid, nowStamp(), opponent, HashMap(current)))
        }
        store.save()
        toast("Game saved")
        endSession()
    }

    private fun endSession() {
        activePlayerId = null; activeGameId = null; opponent = ""
        STAT_KEYS.forEach { current[it] = 0 }
        undoStack.clear()
        store.saveDraft(null)
        showTab(0)
    }

    private fun saveDraft() {
        val pid = activePlayerId ?: return
        val o = JSONObject().put("playerId", pid).put("gameId", activeGameId ?: "").put("opponent", opponent)
        STAT_KEYS.forEach { o.put(it, current.v(it)) }
        store.saveDraft(o)
    }

    private fun restoreDraft() {
        val d = store.loadDraft() ?: return
        val pid = d.optString("playerId")
        if (store.player(pid) == null) { store.saveDraft(null); return }
        activePlayerId = pid
        activeGameId = d.optString("gameId").ifEmpty { null }
        opponent = d.optString("opponent")
        STAT_KEYS.forEach { current[it] = d.optInt(it) }
    }

    // ---------- Players tab ----------
    private fun renderPlayers() {
        content.addView(button("+ Add Player", ACCENT) { editPlayer(null) }.bottomMargin(8))
        if (store.players.isEmpty()) {
            content.addView(text("No players yet.", 14f, MUTED).spaced()); return
        }
        content.addView(text("Tap to edit · long-press to delete", 12f, MUTED).spaced())

        store.players.sortedBy { it.name.lowercase() }.forEach { p ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ripple(CARD, 18)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, 0, 0, dp(10)) }
                isClickable = true
                setOnClickListener { editPlayer(p) }
                setOnLongClickListener {
                    confirm("Delete ${p.name} and all of their games?") { deletePlayer(p) }; true
                }
            }
            row.addView(text(p.number.ifBlank { "–" }, 18f, WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT) }
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(0, 0, dp(14), 0) }
            })
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(p.name, 17f, bold = true))
            val count = store.games.count { it.playerId == p.id }
            info.addView(text(
                listOf(p.details(), "$count game${if (count == 1) "" else "s"}")
                    .filter { it.isNotBlank() }.joinToString(" · "), 13f, MUTED
            ))
            row.addView(info)
            content.addView(row)
        }
    }

    private fun editPlayer(p: Player?) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
        }
        val name = input("Name").apply { setText(p?.name ?: "") }
        val number = input("Jersey number").apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText(p?.number ?: "")
        }
        val team = input("Team").apply { setText(p?.team ?: "") }
        val posOptions = listOf("—") + POSITIONS
        val pos = spinner(posOptions).apply {
            setSelection(maxOf(0, posOptions.indexOf(p?.position ?: "")))
        }
        form.addView(name); form.addView(number); form.addView(team)
        form.addView(fieldLabel("Position")); form.addView(pos)

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (p == null) "Add player" else "Edit player")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) { toast("Name is required"); return@setPositiveButton }
                val position = posOptions[pos.selectedItemPosition].let { if (it == "—") "" else it }
                if (p == null) {
                    store.players.add(Player(newId(), n, position, team.text.toString().trim(), number.text.toString().trim()))
                } else {
                    p.name = n; p.position = position
                    p.team = team.text.toString().trim(); p.number = number.text.toString().trim()
                }
                store.save()
                showTab(tab)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deletePlayer(p: Player) {
        store.players.remove(p)
        store.games.removeAll { it.playerId == p.id }
        store.save()
        if (activePlayerId == p.id) {
            activePlayerId = null; activeGameId = null; store.saveDraft(null)
        }
        if (analysisPlayerId == p.id) analysisPlayerId = null
        showTab(1)
    }

    // ---------- Analysis tab ----------
    private fun renderAnalysis() {
        if (store.players.isEmpty()) {
            content.addView(text("Add a player and save some games to see analysis.", 14f, MUTED)); return
        }
        val idx = store.players.indexOfFirst { it.id == analysisPlayerId }.let { if (it < 0) 0 else it }
        analysisPlayerId = store.players[idx].id

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sp = spinner(store.players.map { it.label() }).apply { setSelection(idx) }
        sp.onItemSelectedListener = onSelect { pos ->
            val p = store.players[pos]
            if (p.id != analysisPlayerId) {
                analysisPlayerId = p.id; renderAnalysisBody(body, p)
            }
        }
        content.addView(sp.bottomMargin(12))
        content.addView(body)
        renderAnalysisBody(body, store.players[idx])
    }

    private fun renderAnalysisBody(body: LinearLayout, player: Player) {
        body.removeAllViews()
        val games = store.gamesFor(player.id)
        if (games.isEmpty()) {
            body.addView(card().apply {
                addView(text("No games yet for ${player.name}", 16f, bold = true))
                addView(text("Track a game on the Track tab to see analysis here.", 14f, MUTED))
            })
            return
        }
        val n = games.size
        val tot = STAT_KEYS.associateWith { k -> games.sumOf { it.stats.v(k) } }
        val per = { k: String -> tot.v(k).toDouble() / n }
        val pts = games.sumOf { points(it.stats) }
        val fgm = tot.v("fg2_m") + tot.v("fg3_m")
        val fga = tot.v("fg2_a") + tot.v("fg3_a")
        val tsDen = 2 * (fga + 0.44 * tot.v("ft_a"))

        body.addView(card().apply {
            addView(text("Averages · $n game${if (n == 1) "" else "s"}", 16f, bold = true).spaced())
            addView(tileRow("PPG" to f1(pts.toDouble() / n), "RPG" to f1(per("reb")), "APG" to f1(per("ast"))))
            addView(tileRow("SPG" to f1(per("stl")), "BPG" to f1(per("blk")), "TOV" to f1(per("to"))))
        })

        body.addView(card().apply {
            addView(text("Shooting", 16f, bold = true).spaced())
            addView(tileRow(
                "FG%" to pct(fgm, fga),
                "3P%" to pct(tot.v("fg3_m"), tot.v("fg3_a")),
                "FT%" to pct(tot.v("ft_m"), tot.v("ft_a"))
            ))
            addView(tileRow(
                "eFG%" to if (fga == 0) "—" else "${(100 * (fgm + 0.5 * tot.v("fg3_m")) / fga).toInt()}%",
                "TS%" to if (tsDen == 0.0) "—" else "${(100 * pts / tsDen).toInt()}%",
                "AST/TO" to if (tot.v("to") == 0) "—" else f1(tot.v("ast").toDouble() / tot.v("to"))
            ))
            addView(text(
                "Totals: FG $fgm/$fga · 3PT ${tot.v("fg3_m")}/${tot.v("fg3_a")} · FT ${tot.v("ft_m")}/${tot.v("ft_a")}",
                12f, MUTED
            ).apply { setPadding(0, dp(8), 0, 0) })
        })

        body.addView(card().apply {
            addView(text("Career highs", 16f, bold = true).spaced())
            val bestPts = games.maxBy { points(it.stats) }
            val bestReb = games.maxBy { it.stats.v("reb") }
            val bestAst = games.maxBy { it.stats.v("ast") }
            addView(tileRow(
                "PTS" to points(bestPts.stats).toString(),
                "REB" to bestReb.stats.v("reb").toString(),
                "AST" to bestAst.stats.v("ast").toString()
            ))
            addView(text("Best scoring game: ${bestPts.title()}", 12f, MUTED).apply { setPadding(0, dp(8), 0, 0) })
        })

        if (n >= 2) {
            val recent = games.takeLast(5)
            val m = recent.size
            body.addView(card().apply {
                addView(text("Last $m vs season", 16f, bold = true).spaced())
                listOf(
                    Triple("Points", recent.sumOf { points(it.stats) }.toDouble() / m, pts.toDouble() / n),
                    Triple("Rebounds", recent.sumOf { it.stats.v("reb") }.toDouble() / m, per("reb")),
                    Triple("Assists", recent.sumOf { it.stats.v("ast") }.toDouble() / m, per("ast")),
                    Triple("Turnovers", recent.sumOf { it.stats.v("to") }.toDouble() / m, per("to"))
                ).forEach { (label, last, season) ->
                    val diff = last - season
                    val better = if (label == "Turnovers") diff <= 0 else diff >= 0
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4))
                    }
                    row.addView(text(label, 14f).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
                    row.addView(text("${f1(last)}  vs  ${f1(season)}", 14f, MUTED).apply { setPadding(0, 0, dp(12), 0) })
                    row.addView(text((if (diff >= 0) "▲ " else "▼ ") + f1(kotlin.math.abs(diff)), 14f,
                        if (better) GREEN else RED, bold = true))
                    addView(row)
                }
            })
        }

        val chartGames = games.takeLast(10)
        body.addView(card().apply {
            addView(text("Points by game (last ${chartGames.size})", 16f, bold = true).spaced())
            addView(BarChart(
                this@MainActivity,
                chartGames.map { points(it.stats) },
                chartGames.map { it.date.take(10).drop(5) },
                ACCENT, MUTED, MUTED,
                pts.toDouble() / n
            ).apply { layoutParams = LinearLayout.LayoutParams(MATCH, dp(180)) })
            addView(text("Dashed line = season average", 11f, MUTED))
        })

        body.addView(text("Game log · tap to open in tracker, long-press to delete", 12f, MUTED).spaced())
        games.reversed().forEach { g ->
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = ripple(CARD, 16)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, 0, 0, dp(8)) }
                isClickable = true
                addView(text(g.title(), 14f, bold = true))
                addView(text(statLine(g.stats), 13f, MUTED))
                setOnClickListener {
                    if (activePlayerId != null) {
                        confirm("You have a game in progress. Replace it with this one?") { startSession(player, g, "") }
                    } else startSession(player, g, "")
                }
                setOnLongClickListener {
                    confirm("Delete this game?") {
                        store.games.remove(g); store.save()
                        if (activeGameId == g.id) activeGameId = null
                        renderAnalysisBody(body, player)
                    }
                    true
                }
            })
        }
    }

    // ---------- Formatting ----------
    private fun statLine(s: Map<String, Int>): String {
        val fgm = s.v("fg2_m") + s.v("fg3_m"); val fga = s.v("fg2_a") + s.v("fg3_a")
        return "PTS ${points(s)}  ·  REB ${s.v("reb")}  ·  AST ${s.v("ast")}\n" +
            "STL ${s.v("stl")}  ·  BLK ${s.v("blk")}  ·  TO ${s.v("to")}\n" +
            "FG $fgm/$fga (${pct(fgm, fga)})  ·  3PT ${s.v("fg3_m")}/${s.v("fg3_a")}  ·  FT ${s.v("ft_m")}/${s.v("ft_a")}"
    }

    private fun pct(m: Int, a: Int) = if (a == 0) "—" else "${m * 100 / a}%"
    private fun f1(d: Double) = String.format(Locale.US, "%.1f", d)

    // ---------- View helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat(); setColor(color)
    }

    private fun ripple(color: Int, radius: Int = 16) =
        RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), rounded(color, radius), null)

    private fun text(s: String, size: Float, color: Int = WHITE, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun fieldLabel(s: String) = text(s.uppercase(), 11f, MUTED, bold = true).apply {
        setPadding(0, dp(12), 0, dp(2))
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = text(label, 15f, WHITE, bold = true).apply {
        gravity = Gravity.CENTER
        background = ripple(color, 14)
        setPadding(dp(12), dp(14), dp(12), dp(14))
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun statButton(label: String, color: Int, onClick: () -> Unit) = button(label, color, onClick).apply {
        textSize = 17f
        minHeight = dp(64)
        elevation = dp(2).toFloat()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, 18)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(0, 0, 0, dp(12)) }
    }

    private fun tileRow(vararg tiles: Pair<String, String>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        tiles.forEach { (label, value) ->
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(TILE, 12)
                setPadding(0, dp(10), 0, dp(10))
                addView(text(value, 22f, WHITE, bold = true).apply { gravity = Gravity.CENTER })
                addView(text(label, 11f, MUTED, bold = true).apply { gravity = Gravity.CENTER })
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            })
        }
    }

    private fun input(hint: String) = EditText(this).apply {
        this.hint = hint
        setTextColor(WHITE); setHintTextColor(MUTED)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        setSingleLine()
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun spinner(items: List<String>) = Spinner(this).apply { adapter = adapter(items) }

    private fun onSelect(handler: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = handler(position)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private fun <T : View> T.weighted(): T = apply {
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
    }

    private fun <T : View> T.topMargin(m: Int): T = apply {
        (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(MATCH, WRAP))
            .also { it.topMargin = dp(m); layoutParams = it }
    }

    private fun <T : View> T.bottomMargin(m: Int): T = apply {
        (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(MATCH, WRAP))
            .also { it.bottomMargin = dp(m); layoutParams = it }
    }

    private fun TextView.spaced(): TextView = apply { setPadding(0, 0, 0, dp(8)) }

    private fun confirm(msg: String, action: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setMessage(msg)
            .setPositiveButton("Yes") { _, _ -> action() }
            .setNegativeButton("No", null).show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
