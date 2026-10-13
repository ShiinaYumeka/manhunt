package shina.manhunt.game

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import shina.manhunt.Manhunt

class ManhuntCommand(private val plugin: Manhunt) : BasicCommand {
    override fun execute(stack: CommandSourceStack, args: Array<String>) {
        val sender = stack.sender
        if (sender !is Player) {
            sender.sendMessage("只能由玩家使用。")
            return
        }
        val session = plugin.session
        when (args.getOrNull(0)) {
            "join" -> join(sender, args.getOrNull(1))
            "leave" -> leave(sender)
            "respawn" -> setRespawn(sender, args.getOrNull(1))
            "reserve" -> setReserve(sender, args.getOrNull(1))
            "list" -> list(sender)
            "start" -> {
                if (session.phase != Phase.LOBBY) sender.sendMessage(Component.text("游戏已经开始。").color(NamedTextColor.RED))
                else Match.start(plugin)
            }
            "route" -> plugin.progress.chooseRoute(sender, args.getOrNull(1))
            "xp" -> plugin.hunterLoop.xp(sender, args.getOrNull(1))
            "chest" -> plugin.hunterLoop.openChest(sender)
            "event" -> event(sender, args.getOrNull(1), args.getOrNull(2))
            else -> sender.sendMessage("/manhunt join|leave|respawn|reserve|list|start|event")
        }
    }

    private fun event(player: Player, raw: String?, state: String?) {
        val session = plugin.session
        val rule = Rule.from(raw)
        if (rule == null) {
            player.sendMessage(session.rules.entries.joinToString("，") { "${it.key.label}${if (it.value) "开" else "关"}" })
            player.sendMessage("/manhunt event hunt|combat|fire|lock on|off")
            return
        }
        val enabled = when (state) {
            "on", "开" -> true
            "off", "关" -> false
            else -> {
                player.sendMessage("/manhunt event ${rule.id} on|off")
                return
            }
        }
        session.rules[rule] = enabled
        if (!enabled && rule == Rule.HUNT) session.huntCountdownEnd.clear()
        if (!enabled && rule == Rule.COMBAT) session.combatCountdownEnd = null
        player.sendMessage("${rule.label}已${if (enabled) "开启" else "关闭"}。")
    }

    private fun join(player: Player, team: String?) {
        val session = plugin.session
        if (session.phase != Phase.LOBBY) {
            player.sendMessage(Component.text("现在不能换队。").color(NamedTextColor.RED))
            return
        }
        val role = when (team) {
            "hunter" -> Role.HUNTER
            "runner" -> Role.RUNNER
            else -> {
                player.sendMessage("/manhunt join hunter|runner")
                return
            }
        }
        session.roles[player.uniqueId] = role
        plugin.ticker.syncBoard()
        plugin.ticker.refreshGlowSoon()
        player.sendMessage(Component.text(if (role == Role.HUNTER) "你加入了猎人。" else "你加入了逃生。").color(NamedTextColor.GREEN))
    }

    private fun leave(player: Player) {
        val session = plugin.session
        if (session.phase != Phase.LOBBY) {
            player.sendMessage(Component.text("现在不能离队。").color(NamedTextColor.RED))
            return
        }
        session.roles.remove(player.uniqueId)
        plugin.ticker.syncBoard()
        plugin.ticker.refreshGlowSoon()
        player.sendMessage("你退出了阵营。")
    }

    private fun setRespawn(player: Player, raw: String?) {
        if (plugin.session.phase != Phase.LOBBY) return
        val seconds = raw?.toIntOrNull()
        if (seconds == null || seconds < 1) {
            player.sendMessage("/manhunt respawn <秒>")
            return
        }
        plugin.session.respawnSeconds = seconds
        player.sendMessage("猎人复活时间设为 $seconds 秒。")
    }

    private fun setReserve(player: Player, raw: String?) {
        if (plugin.session.phase != Phase.LOBBY) return
        val amount = raw?.toIntOrNull()
        if (amount == null || amount < 1) {
            player.sendMessage("/manhunt reserve <点数>")
            return
        }
        plugin.session.reserveMax = amount
        player.sendMessage("逃生备用血量设为 $amount。")
    }

    private fun list(player: Player) {
        val session = plugin.session
        val names = session.roles.entries.groupBy({ it.value }, { org.bukkit.Bukkit.getOfflinePlayer(it.key).name ?: it.key.toString() })
        player.sendMessage("猎人：${names[Role.HUNTER]?.joinToString().orEmpty()}")
        player.sendMessage("逃生：${names[Role.RUNNER]?.joinToString().orEmpty()}")
        player.sendMessage("复活 ${session.respawnSeconds} 秒，备用血 ${session.reserveMax}")
    }

    override fun suggest(stack: CommandSourceStack, args: Array<String>): List<String> {
        if (args.size == 1) return listOf("join", "leave", "respawn", "reserve", "list", "start", "route", "xp", "chest", "event").filter { it.startsWith(args[0]) }
        if (args.size == 2 && args[0] == "event") return Rule.entries.map { it.id }.filter { it.startsWith(args[1]) }
        if (args.size == 3 && args[0] == "event") return listOf("on", "off").filter { it.startsWith(args[2]) }
        if (args.size == 2 && args[0] == "join") return listOf("hunter", "runner").filter { it.startsWith(args[1]) }
        if (args.size == 2 && args[0] == "reserve") return listOf("400").filter { it.startsWith(args[1]) }
        if (args.size == 2 && args[0] == "route") return listOf("nether", "trial", "ancient", "mineshaft").filter { it.startsWith(args[1]) }
        if (args.size == 2 && args[0] == "xp") return listOf("deposit", "withdraw").filter { it.startsWith(args[1]) }
        return emptyList()
    }
}
