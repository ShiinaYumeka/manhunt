package shina.manhunt.play

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.Team
import io.papermc.paper.scoreboard.numbers.NumberFormat
import shina.manhunt.Manhunt
import shina.manhunt.game.Phase
import shina.manhunt.game.Role
import java.util.UUID

class StatusBoard(private val plugin: Manhunt) {
    private val board: Scoreboard = Bukkit.getScoreboardManager().newScoreboard
    private val objective: Objective = board.registerNewObjective("runners", Criteria.DUMMY, Component.text("逃生", NamedTextColor.GREEN)).also {
        it.displaySlot = DisplaySlot.SIDEBAR
        it.numberFormat(NumberFormat.blank())
    }
    private val reserveObjective: Objective = board.registerNewObjective("mhreserve", Criteria.DUMMY, Component.text("备用血", NamedTextColor.AQUA)).also {
        it.displaySlot = DisplaySlot.BELOW_NAME
    }
    private val lineText = mutableMapOf<String, String>()
    private val runnerTeam: Team = coloredTeam("mh_runners", NamedTextColor.GREEN)
    private val hunterTeam: Team = coloredTeam("mh_hunters", NamedTextColor.RED)

    fun refresh() {
        val session = plugin.session
        syncTeams()
        if (session.phase == Phase.LOBBY) {
            objective.displaySlot = null
            reserveObjective.displaySlot = null
            for (player in Bukkit.getOnlinePlayers()) {
                if (player.scoreboard !== board) player.scoreboard = board
            }
            return
        }
        objective.displaySlot = DisplaySlot.SIDEBAR
        reserveObjective.displaySlot = DisplaySlot.BELOW_NAME
        val runnerNames = session.roles.filter { it.value == Role.RUNNER }.mapNotNull { Bukkit.getOfflinePlayer(it.key).name }.toSet()
        for (entry in board.entries.toList()) {
            if (entry.startsWith("mh")) continue
            if (entry !in runnerNames) board.resetScores(entry)
        }
        for ((id, role) in session.roles) {
            if (role != Role.RUNNER) continue
            val name = Bukkit.getOfflinePlayer(id).name ?: continue
            reserveObjective.getScore(name).score = (session.reserve[id] ?: 0).coerceAtLeast(0)
        }
        val curse = combatLine()
        val runnerSlots = if (curse == null) MAX_LINES else MAX_LINES - 1
        if (curse != null) {
            show(CURSE_ENTRY, curse.first, curse.second, MAX_LINES)
        } else {
            board.resetScores(CURSE_ENTRY)
            lineText.remove(CURSE_ENTRY)
        }
        val runners = session.roles.filter { it.value == Role.RUNNER }.keys.toList()
        val shown = runners.take(runnerSlots)
        shown.forEachIndexed { index, id ->
            val row = line(id)
            show(entry(index), row.text, row.plain, runnerSlots - index)
        }
        for (index in shown.size until MAX_LINES) {
            board.resetScores(entry(index))
            lineText.remove(entry(index))
        }
        for (player in Bukkit.getOnlinePlayers()) {
            if (player.scoreboard !== board) player.scoreboard = board
        }
    }

    private fun coloredTeam(name: String, color: NamedTextColor): Team = board.registerNewTeam(name).also {
        it.color(color)
        it.setAllowFriendlyFire(true)
    }

    private fun syncTeams() {
        val session = plugin.session
        for (entry in runnerTeam.entries.toList()) runnerTeam.removeEntry(entry)
        for (entry in hunterTeam.entries.toList()) hunterTeam.removeEntry(entry)
        for ((id, role) in session.roles) {
            val name = Bukkit.getOfflinePlayer(id).name ?: continue
            when (role) {
                Role.RUNNER -> runnerTeam.addEntry(name)
                Role.HUNTER -> hunterTeam.addEntry(name)
            }
        }
    }

    fun hide() {
        val main = Bukkit.getScoreboardManager().mainScoreboard
        for (player in Bukkit.getOnlinePlayers()) {
            if (player.scoreboard === board) player.scoreboard = main
        }
    }

    private fun show(entry: String, text: Component, plain: String, score: Int) {
        if (lineText[entry] != plain) {
            board.resetScores(entry)
            lineText[entry] = plain
        }
        val row = objective.getScore(entry)
        row.customName(text)
        row.score = score
    }

    private fun line(id: UUID): Row {
        val session = plugin.session
        val name = Bukkit.getOfflinePlayer(id).name ?: "未知"
        val reserve = session.reserve[id] ?: 0
        val state = when {
            id in session.eliminated -> "死亡" to NamedTextColor.RED
            id in session.downed -> "濒死" to NamedTextColor.GOLD
            else -> "存活" to NamedTextColor.GREEN
        }
        val text = Component.text(name, NamedTextColor.WHITE)
            .append(Component.space())
            .append(Component.text(state.first, state.second))
            .append(Component.text(" 备用$reserve", NamedTextColor.AQUA))
        return Row(text, "$name ${state.first} 备用$reserve")
    }

    private data class Row(val text: Component, val plain: String)

    private fun combatLine(): Pair<Component, String>? {
        val end = plugin.session.combatCountdownEnd ?: return null
        val left = ((end - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        val plain = "战斗诅咒 ${left}秒"
        return Component.text(plain, NamedTextColor.RED) to plain
    }

    private fun entry(index: Int) = "mh$index"

    private companion object {
        const val MAX_LINES = 15
        const val CURSE_ENTRY = "mhcurse"
    }
}
