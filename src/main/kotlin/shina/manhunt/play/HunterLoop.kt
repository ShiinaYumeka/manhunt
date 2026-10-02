package shina.manhunt.play

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import shina.manhunt.Manhunt
import shina.manhunt.game.Role
import shina.manhunt.game.TeamChestHolder
import shina.manhunt.kit.Gear
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

class HunterLoop(private val plugin: Manhunt) {
    fun beginRespawn(player: Player, opening: Boolean) {
        val session = plugin.session
        val extra = if (opening || !session.early) 10 else 0
        val seconds = session.respawnSeconds + extra
        player.gameMode = GameMode.SPECTATOR
        session.respawnReadyAt[player.uniqueId] = System.currentTimeMillis() + seconds * 1000L
        player.sendMessage(Component.text("旁观 $seconds 秒后，在合法位置右键确认复活。").color(NamedTextColor.GRAY))
    }

    fun confirm(player: Player) {
        val session = plugin.session
        if (session.phase != shina.manhunt.game.Phase.PLAYING) return
        if (session.role(player.uniqueId) != Role.HUNTER) return
        if (player.gameMode != GameMode.SPECTATOR) return
        val ready = session.respawnReadyAt[player.uniqueId] ?: return
        val now = System.currentTimeMillis()
        if (now < ready) {
            player.warnTitle("无法复活", "还需 ${(ready - now + 999) / 1000} 秒")
            return
        }
        val reason = invalidReason(player)
        if (reason != null) {
            player.warnTitle("无法复活", reason)
            return
        }
        session.respawnReadyAt.remove(player.uniqueId)
        player.gameMode = GameMode.SURVIVAL
        player.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 16.0
        player.health = 16.0
        player.foodLevel = 20
        player.inventory.addItem(Gear.hunterCompass())
        player.inventory.addItem(Gear.teamChest())
        val kit = Gear.loadout(session).toMutableList()
        val blessed = kit.firstOrNull { it.containsEnchantment(org.bukkit.enchantments.Enchantment.BINDING_CURSE) && it.type.equipmentSlot.isArmor }
        if (blessed != null) kit.remove(blessed)
        val leftover = player.inventory.addItem(*kit.toTypedArray())
        leftover.values.forEach { player.world.dropItem(player.location, it) }
        if (blessed != null) player.inventory.setItem(blessed.type.equipmentSlot, blessed)
        val runners = session.livingRunners()
        if (runners.isNotEmpty() && session.compassTarget[player.uniqueId] == null) {
            session.compassTarget[player.uniqueId] = nearest(player, runners)?.uniqueId ?: runners.first().uniqueId
        }
        player.sendMessage(Component.text("你已复活。").color(NamedTextColor.GREEN))
    }

    fun openChest(player: Player) {
        val session = plugin.session
        if (session.phase != shina.manhunt.game.Phase.PLAYING || session.role(player.uniqueId) != Role.HUNTER) return
        if (session.livingRunners().any { it.world == player.world && it.location.distance(player.location) <= 70 }) {
            player.warnTitle("无法打开", "必须离逃生超过 70 格")
            return
        }
        player.openInventory(session.chest)
        player.sendMessage(Component.text("经验池：${session.xpBank}  ")
            .append(Component.text("[存入]").color(NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/manhunt xp deposit")))
            .append(Component.text(" "))
            .append(Component.text("[取出]").color(NamedTextColor.GOLD).clickEvent(ClickEvent.runCommand("/manhunt xp withdraw"))))
    }

    fun xp(player: Player, action: String?) {
        val session = plugin.session
        if (player.openInventory.topInventory.holder !is TeamChestHolder) {
            player.warnTitle("无法存取", "先打开团队仓库")
            return
        }
        when (action) {
            "deposit" -> {
                val points = player.expPoints()
                if (points <= 0) return
                player.clearExp()
                session.xpBank += points
                player.sendMessage("已存入 $points 点经验，池中共 ${session.xpBank}。")
            }
            "withdraw" -> {
                val points = session.xpBank
                if (points <= 0) return
                session.xpBank = 0
                player.giveExp(points)
                player.sendMessage("已取出 $points 点经验。")
            }
            else -> player.sendMessage("/manhunt xp deposit|withdraw")
        }
    }

    fun cycle(player: Player) {
        val runners = plugin.session.livingRunners()
        if (runners.isEmpty()) return
        val current = plugin.session.compassTarget[player.uniqueId]
        val index = runners.indexOfFirst { it.uniqueId == current }
        val next = runners[Math.floorMod(index + 1, runners.size)]
        plugin.session.compassTarget[player.uniqueId] = next.uniqueId
        player.sendActionBar(Component.text("追踪 ${next.name}"))
    }

    fun teleportStrike() {
        val session = plugin.session
        val runners = session.livingRunners()
        if (runners.isEmpty()) return
        val runner = runners.random()
        val angle = Math.random() * Math.PI * 2
        val x = runner.location.blockX + (50 * cos(angle)).toInt()
        val z = runner.location.blockZ + (50 * sin(angle)).toInt()
        val y = openHeight(runner.world, x, z, runner.location.blockY)
        val dest = Location(runner.world, x + 0.5, y.toDouble(), z + 0.5)
        clearBody(dest)
        if (!runner.world.getBlockAt(x, y - 1, z).type.isSolid) platform(dest)
        for (hunter in session.hunters()) {
            if (hunter.gameMode == GameMode.SPECTATOR) continue
            hunter.teleport(dest)
            hunter.fallDistance = 0f
        }
    }

    private fun openHeight(world: World, x: Int, z: Int, preferred: Int): Int {
        fun open(y: Int) = y > world.minHeight && y < world.maxHeight - 1 &&
            !world.getBlockAt(x, y, z).type.isSolid && !world.getBlockAt(x, y + 1, z).type.isSolid
        if (open(preferred)) return preferred
        for (delta in 1..16) {
            if (open(preferred + delta)) return preferred + delta
            if (open(preferred - delta)) return preferred - delta
        }
        return preferred.coerceIn(world.minHeight + 1, world.maxHeight - 2)
    }

    private fun clearBody(dest: Location) {
        val world = dest.world ?: return
        for (dx in -1..1) for (dy in 0..1) for (dz in -1..1) {
            val block = world.getBlockAt(dest.blockX + dx, dest.blockY + dy, dest.blockZ + dz)
            if (block.type == Material.OBSIDIAN || block.type.hardness < 0f) continue
            block.type = Material.AIR
        }
    }

    private fun platform(dest: Location) {
        val world = dest.world ?: return
        for (dx in -1..1) for (dz in -1..1) {
            val block = world.getBlockAt(dest.blockX + dx, dest.blockY - 1, dest.blockZ + dz)
            if (block.type == Material.OBSIDIAN || block.type.hardness < 0f || block.type.isSolid) continue
            block.type = Material.COBBLESTONE
        }
    }

    fun respawnAllowed(hunter: Player): Boolean = invalidReason(hunter) == null

    private fun invalidReason(hunter: Player): String? {
        val runners = plugin.session.livingRunners().filter { it.world == hunter.world }
        if (runners.none { it.location.distance(hunter.location) in 50.0..300.0 }) return "需要至少一名逃生在 50 到 300 格内"
        if (runners.any { sees(it, hunter) }) return "你还在逃生的视线内"
        return null
    }

    private fun sees(runner: Player, hunter: Player): Boolean {
        val start = runner.eyeLocation
        val toward = hunter.eyeLocation.toVector().subtract(start.toVector())
        val distance = toward.length()
        if (distance < 0.5) return true
        val hit = runner.world.rayTraceBlocks(start, toward.normalize(), distance, FluidCollisionMode.NEVER, true)
        return hit == null || hit.hitBlock == null
    }

    private fun nearest(hunter: Player, runners: List<Player>): Player? =
        runners.filter { it.world == hunter.world }.minByOrNull { it.location.distanceSquared(hunter.location) }
}

fun Player.expPoints(): Int {
    fun toReach(level: Int): Int = when {
        level <= 16 -> level * level + 6 * level
        level <= 31 -> (2.5 * level * level - 40.5 * level + 360).toInt()
        else -> (4.5 * level * level - 162.5 * level + 2220).toInt()
    }
    fun toNext(level: Int): Int = when {
        level >= 31 -> 9 * level - 158
        level >= 16 -> 5 * level - 38
        else -> 2 * level + 7
    }
    return toReach(level) + ceil(exp * toNext(level)).toInt()
}

fun Player.clearExp() {
    level = 0
    exp = 0f
    totalExperience = 0
}
