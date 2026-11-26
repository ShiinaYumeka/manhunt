package shina.manhunt.game

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.attribute.Attribute
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffectType
import shina.manhunt.Manhunt
import shina.manhunt.kit.Gear
import shina.manhunt.play.setCrawling

class ManhuntCommand(private val plugin: Manhunt) : BasicCommand {
    override fun execute(stack: CommandSourceStack, args: Array<String>) {
        val sender = stack.sender
        if (args.getOrNull(0) == "reset") {
            MapReset.request(plugin, sender)
            return
        }
        if (args.getOrNull(0) == "role") {
            setRole(sender, args.getOrNull(1), args.getOrNull(2))
            return
        }
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
            "option" -> option(sender, args.getOrNull(1), args.getOrNull(2))
            "settings" -> {
                if (session.phase != Phase.LOBBY) sender.sendMessage(Component.text("现在不能打开设置。").color(NamedTextColor.RED))
                else SettingsMenu.open(sender)
            }
            else -> sender.sendMessage("/manhunt join|leave|respawn|reserve|list|start|event|option|settings|reset|role")
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

    private fun option(player: Player, raw: String?, state: String?) {
        val session = plugin.session
        if (raw == null) {
            player.sendMessage("逃生仓库${if (session.runnerChestEnabled) "开" else "关"}，金疮药${if (session.medicineEnabled) "开" else "关"}")
            player.sendMessage("/manhunt option chest|medicine on|off")
            return
        }
        if (session.phase != Phase.LOBBY) {
            player.sendMessage(Component.text("游戏已经开始，不能修改。").color(NamedTextColor.RED))
            return
        }
        val enabled = when (state) {
            "on", "开" -> true
            "off", "关" -> false
            else -> {
                player.sendMessage("/manhunt option $raw on|off")
                return
            }
        }
        when (raw) {
            "chest" -> {
                session.runnerChestEnabled = enabled
                if (!enabled) session.medicineEnabled = false
                player.sendMessage("逃生仓库已${if (enabled) "开启" else "关闭"}。")
                if (!enabled) player.sendMessage("金疮药已关闭。")
            }
            "medicine" -> {
                if (enabled && !session.runnerChestEnabled) {
                    player.sendMessage(Component.text("需要先开启逃生仓库。").color(NamedTextColor.RED))
                    return
                }
                session.medicineEnabled = enabled
                player.sendMessage("金疮药已${if (enabled) "开启" else "关闭"}。")
            }
            else -> {
                player.sendMessage("/manhunt option chest|medicine on|off")
                return
            }
        }
        publishBalance(false)
    }

    private fun join(player: Player, team: String?) {
        val session = plugin.session
        val role = when (team) {
            "hunter" -> Role.HUNTER
            "runner" -> Role.RUNNER
            "spectator" -> Role.SPECTATOR
            else -> {
                player.sendMessage("/manhunt join hunter|runner|spectator")
                return
            }
        }
        if (session.phase != Phase.LOBBY && role != Role.SPECTATOR) {
            player.sendMessage(Component.text("现在不能加入猎人或逃生。").color(NamedTextColor.RED))
            return
        }
        assign(player, role)
        val text = when (role) {
            Role.HUNTER -> "你加入了猎人。"
            Role.RUNNER -> "你加入了逃生。"
            Role.SPECTATOR -> "你加入了旁观。"
        }
        player.sendMessage(Component.text(text).color(NamedTextColor.GREEN))
    }

    private fun setRole(sender: CommandSender, name: String?, team: String?) {
        if (sender is Player && !sender.isOp) {
            sender.sendMessage("只有管理员可以更改他人身份。")
            return
        }
        val role = when (team) {
            "hunter" -> Role.HUNTER
            "runner" -> Role.RUNNER
            "spectator" -> Role.SPECTATOR
            else -> {
                sender.sendMessage("/manhunt role <玩家> hunter|runner|spectator")
                return
            }
        }
        val target = name?.let { Bukkit.getPlayerExact(it) }
        if (target == null) {
            sender.sendMessage("找不到在线玩家。")
            return
        }
        assign(target, role)
        val label = roleLabel(role)
        sender.sendMessage("已将 ${target.name} 设为$label。")
        if (sender != target) target.sendMessage(Component.text("你被设为$label。").color(NamedTextColor.GREEN))
    }

    private fun assign(player: Player, role: Role) {
        val session = plugin.session
        val previous = session.role(player.uniqueId)
        val wasLivingRunner = previous == Role.RUNNER && player.uniqueId !in session.eliminated
        if (previous == Role.RUNNER && role != Role.RUNNER) releaseRunner(player)
        if (previous == Role.HUNTER && role != Role.HUNTER) releaseHunter(player)
        session.roles[player.uniqueId] = role
        if (session.phase != Phase.LOBBY) {
            when (role) {
                Role.SPECTATOR -> player.gameMode = GameMode.SPECTATOR
                Role.HUNTER -> enterHunter(player, previous)
                Role.RUNNER -> enterRunner(player, previous)
            }
        }
        if (wasLivingRunner && role != Role.RUNNER && session.phase == Phase.PLAYING) {
            if (session.livingIds().isEmpty()) Match.huntersWin(plugin)
            else Match.huntersWinIfAllDown(plugin)
        }
        plugin.ticker.syncBoard()
        plugin.ticker.refreshGlowSoon()
        if (session.phase == Phase.LOBBY) publishBalance(false)
    }

    private fun releaseRunner(player: Player) {
        val session = plugin.session
        val id = player.uniqueId
        session.downed.remove(id)
        session.downedAt.remove(id)
        val rescuers = session.rescue.filterValues { it.first == id }.keys.toList()
        session.rescue.entries.removeIf { it.key == id || it.value.first == id }
        for (rescuerId in rescuers) {
            val rescuer = Bukkit.getPlayer(rescuerId) ?: continue
            if (rescuer.uniqueId !in session.downed) rescuer.walkSpeed = 0.2f
        }
        player.walkSpeed = 0.2f
        player.setCrawling(false)
        player.removePotionEffect(PotionEffectType.RESISTANCE)
    }

    private fun releaseHunter(player: Player) {
        val session = plugin.session
        session.respawnReadyAt.remove(player.uniqueId)
        session.huntReturn.remove(player.uniqueId)
    }

    private fun enterHunter(player: Player, previous: Role?) {
        if (previous == Role.HUNTER) return
        player.inventory.clear()
        player.absorptionAmount = 0.0
        player.gameMode = GameMode.SPECTATOR
        if (plugin.session.phase == Phase.PLAYING) plugin.hunterLoop.beginRespawn(player, opening = !plugin.session.early)
    }

    private fun enterRunner(player: Player, previous: Role?) {
        val session = plugin.session
        val id = player.uniqueId
        val eliminated = id in session.eliminated
        val fresh = previous != Role.RUNNER || eliminated
        session.eliminated.remove(id)
        session.downed.remove(id)
        session.downedAt.remove(id)
        player.walkSpeed = 0.2f
        player.setCrawling(false)
        if (!fresh || session.phase == Phase.ENDED) {
            if (fresh && session.phase == Phase.ENDED) player.gameMode = GameMode.SURVIVAL
            return
        }
        if (id !in session.reserve || eliminated) {
            session.reserve[id] = session.reserveMax
            session.reserveInitial[id] = session.reserveMax
        }
        player.gameMode = GameMode.SURVIVAL
        if (session.phase == Phase.COUNTDOWN) return
        val max = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        player.health = max
        player.foodLevel = 20
        if (player.inventory.contents.none { it != null && Gear.isRunnerCompass(it) }) {
            player.inventory.addItem(Gear.runnerCompass())
            plugin.progress.retargetCompass(player, "village", skipCurrent = false, announce = false)
        }
        if (!session.rallyUsed && player.inventory.contents.none { it != null && Gear.isRally(it) }) {
            Gear.giveToBackpack(player, Gear.rallyItem())
        }
        if (session.runnerChestEnabled && player.inventory.contents.none { it != null && Gear.isRunnerChest(it) }) {
            player.inventory.addItem(Gear.runnerChest())
        }
    }

    private fun roleLabel(role: Role) = when (role) {
        Role.HUNTER -> "猎人"
        Role.RUNNER -> "逃生"
        Role.SPECTATOR -> "旁观"
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
        publishBalance(false)
        player.sendMessage("你退出了阵营。")
    }

    private fun publishBalance(atStart: Boolean) {
        val text = plugin.session.adaptBalance(atStart) ?: return
        Bukkit.broadcast(Component.text(text).color(NamedTextColor.YELLOW))
    }

    private fun setRespawn(player: Player, raw: String?) {
        if (plugin.session.phase != Phase.LOBBY) return
        val seconds = raw?.toIntOrNull()
        if (seconds == null || seconds < 1) {
            player.sendMessage("/manhunt respawn <秒>")
            return
        }
        plugin.session.respawnSeconds = seconds
        plugin.session.respawnLocked = true
        player.sendMessage("猎人复活时间设为 $seconds 秒。这项不再自动调整。")
    }

    private fun setReserve(player: Player, raw: String?) {
        if (plugin.session.phase != Phase.LOBBY) return
        val amount = raw?.toIntOrNull()
        if (amount == null || amount < 1) {
            player.sendMessage("/manhunt reserve <点数>")
            return
        }
        plugin.session.reserveMax = amount
        plugin.session.reserveLocked = true
        player.sendMessage("逃生备用血量设为 $amount。这项不再自动调整。")
    }

    private fun list(player: Player) {
        val session = plugin.session
        val names = session.roles.entries.groupBy({ it.value }, { org.bukkit.Bukkit.getOfflinePlayer(it.key).name ?: it.key.toString() })
        player.sendMessage("猎人：${names[Role.HUNTER]?.joinToString().orEmpty()}")
        player.sendMessage("逃生：${names[Role.RUNNER]?.joinToString().orEmpty()}")
        player.sendMessage("旁观：${names[Role.SPECTATOR]?.joinToString().orEmpty()}")
        player.sendMessage("复活 ${session.respawnSeconds} 秒，备用血 ${session.reserveMax}")
        player.sendMessage("逃生仓库${if (session.runnerChestEnabled) "开" else "关"}，金疮药${if (session.medicineEnabled) "开" else "关"}")
    }

    override fun suggest(stack: CommandSourceStack, args: Array<String>): List<String> {
        if (args.size == 1) return listOf("join", "leave", "respawn", "reserve", "list", "start", "route", "xp", "chest", "event", "option", "settings", "reset", "role").filter { it.startsWith(args[0]) }
        if (args.size == 2 && args[0] == "option") return listOf("chest", "medicine").filter { it.startsWith(args[1]) }
        if (args.size == 3 && args[0] == "option") return listOf("on", "off").filter { it.startsWith(args[2]) }
        if (args.size == 2 && args[0] == "event") return Rule.entries.map { it.id }.filter { it.startsWith(args[1]) }
        if (args.size == 3 && args[0] == "event") return listOf("on", "off").filter { it.startsWith(args[2]) }
        if (args.size == 2 && args[0] == "join") return listOf("hunter", "runner", "spectator").filter { it.startsWith(args[1]) }
        if (args.size == 2 && args[0] == "role") return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) }
        if (args.size == 3 && args[0] == "role") return listOf("hunter", "runner", "spectator").filter { it.startsWith(args[2]) }
        if (args.size == 2 && args[0] == "reserve") return listOf(plugin.session.reserveBase().toString()).filter { it.startsWith(args[1]) }
        if (args.size == 2 && args[0] == "route") return listOf("nether", "trial", "ancient", "mineshaft").filter { it.startsWith(args[1]) }
        if (args.size == 2 && args[0] == "xp") return listOf("deposit", "withdraw").filter { it.startsWith(args[1]) }
        return emptyList()
    }
}
