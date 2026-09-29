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
    private val runnerTeam: Team = coloredTeam("mh_runners", NamedTextColor.GREEN)
    private val hunterTeam: Team = coloredTeam("mh_hunters", NamedTextColor.RED)

    fun refresh() {
        val session = plugin.session
        syncTeams()
        if (session.phase == Phase.LOBBY) {
            objective.displaySlot = null
            for (player in Bukkit.getOnlinePlayers()) {
                if (player.scoreboard !== board) player.scoreboard = board
            }
            return
        }
        objective.displaySlot = DisplaySlot.SIDEBAR
        val curse = combatLine()
        val runnerSlots = if (curse == null) MAX_LINES else MAX_LINES - 1
        if (curse != null) {
            val score = objective.getScore(CURSE_ENTRY)
            score.customName(curse)
            score.score = MAX_LINES
        } else {
            board.resetScores(CURSE_ENTRY)
        }
        val runners = session.roles.filter { it.value == Role.RUNNER }.keys.toList()
        val shown = runners.take(runnerSlots)
        shown.forEachIndexed { index, id ->
            val score = objective.getScore(entry(index))
            score.customName(line(id))
            score.score = runnerSlots - index
        }
        for (index in shown.size until MAX_LINES) board.resetScores(entry(index))
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

    private fun line(id: UUID): Component {
        val session = plugin.session
        val name = Bukkit.getOfflinePlayer(id).name ?: "未知"
        val reserve = session.reserve[id] ?: 0
        val state = when {
            id in session.eliminated -> Component.text("死亡", NamedTextColor.RED)
            id in session.downed -> Component.text("濒死", NamedTextColor.GOLD)
            else -> Component.text("存活", NamedTextColor.GREEN)
        }
        return Component.text(name, NamedTextColor.WHITE)
            .append(Component.space())
            .append(state)
            .append(Component.text(" 备用$reserve", NamedTextColor.AQUA))
    }

    private fun combatLine(): Component? {
        val end = plugin.session.combatCountdownEnd ?: return null
        val left = ((end - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        return Component.text("战斗诅咒 ${left}秒", NamedTextColor.RED)
    }

    private fun entry(index: Int) = "mh$index"

    private companion object {
        const val MAX_LINES = 15
        const val CURSE_ENTRY = "mhcurse"
    }
}
