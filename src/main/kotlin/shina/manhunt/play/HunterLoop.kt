package shina.manhunt.play

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import shina.manhunt.Manhunt
import shina.manhunt.game.Role
import shina.manhunt.game.TeamChestHolder
import shina.manhunt.kit.Gear
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

class HunterLoop(private val plugin: Manhunt) {
    fun beginRespawn(player: Player, opening: Boolean, immediate: Boolean = false) {
        val session = plugin.session
        val extra = if (opening || !session.early) 10 else 0
        val seconds = if (immediate) 0 else session.respawnSeconds + extra
        player.gameMode = GameMode.SPECTATOR
        session.respawnReadyAt[player.uniqueId] = System.currentTimeMillis() + seconds * 1000L
        val text = if (seconds == 0) "可以立刻复活，在合法位置右键确认。" else "旁观 $seconds 秒后，在合法位置右键确认复活。"
        player.sendMessage(Component.text(text).color(NamedTextColor.GRAY))
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
        val keepPack = session.huntReturn.remove(player.uniqueId)
        if (!keepPack) {
            val maxHealth = if (session.armor15.isEmpty()) 16.0 else 20.0
            player.getAttribute(Attribute.MAX_HEALTH)?.baseValue = maxHealth
            player.health = maxHealth
            player.foodLevel = 20
            giveHunterGear(player, Gear.loadout(session).toMutableList())
        }
        val runners = session.livingRunners()
        if (runners.isNotEmpty() && session.compassTarget[player.uniqueId] == null) {
            session.compassTarget[player.uniqueId] = nearest(player, runners)?.uniqueId ?: runners.first().uniqueId
        }
        player.sendMessage(Component.text("你已复活。").color(NamedTextColor.GREEN))
        plugin.ticker.refreshGlowSoon()
    }

    fun ensureHunterTools(player: Player) {
        if (player.inventory.contents.any { it != null && Gear.isTeamChest(it) }) return
        val item = Gear.teamChest()
        val storage = player.inventory.storageContents
        val index = (8 downTo 0).firstOrNull { empty(storage[it]) }
            ?: storage.indices.reversed().firstOrNull { empty(storage[it]) }
        if (index == null) player.world.dropItem(player.location, item) else storage[index] = item
        player.inventory.storageContents = storage
    }

    private fun giveHunterGear(player: Player, kit: MutableList<ItemStack>) {
        val armor = kit.filter { it.type.equipmentSlot.isArmor }
        kit.removeAll(armor.toSet())
        val displaced = mutableListOf<ItemStack>()
        for ((slot, pieces) in armor.groupBy { it.type.equipmentSlot }) {
            val chosen = pieces.firstOrNull { it.containsEnchantment(Enchantment.BINDING_CURSE) } ?: pieces.maxBy { armorTier(it.type) }
            val current = player.inventory.getItem(slot)
            if (current != null && current.type != Material.AIR) displaced += current
            player.inventory.setItem(slot, chosen)
            displaced += pieces.filter { it !== chosen }
        }
        kit += displaced
        val swords = kit.filter { it.type.name.endsWith("_SWORD") }
        val axes = kit.filter { it.type.name.endsWith("_AXE") }
        kit.removeAll(swords.toSet())
        kit.removeAll(axes.toSet())
        val storage = player.inventory.storageContents
        val reserved = mutableSetOf<Int>()
        for (item in listOf(Gear.hunterCompass(), Gear.teamChest())) {
            val index = (8 downTo 0).firstOrNull { it !in reserved && empty(storage[it]) }
                ?: storage.indices.reversed().firstOrNull { it !in reserved && empty(storage[it]) }
            if (index == null) player.world.dropItem(player.location, item) else {
                storage[index] = item
                reserved += index
            }
        }
        var cursor = 0
        for (item in swords + axes + kit) {
            while (cursor < storage.size && (cursor in reserved || !empty(storage[cursor]))) cursor++
            if (cursor >= storage.size) player.world.dropItem(player.location, item) else storage[cursor++] = item
        }
        player.inventory.storageContents = storage
    }

    private fun empty(item: ItemStack?) = item == null || item.type == Material.AIR

    private fun armorTier(type: Material): Int = when {
        type.name.startsWith("NETHERITE_") -> 5
        type.name.startsWith("DIAMOND_") -> 4
        type.name.startsWith("IRON_") -> 3
        type.name.startsWith("CHAINMAIL_") -> 2
        type.name.startsWith("GOLDEN_") || type.name.startsWith("COPPER_") -> 1
        else -> 0
    }

    fun openChest(player: Player) {
        val session = plugin.session
        if (session.phase != shina.manhunt.game.Phase.PLAYING || session.role(player.uniqueId) != Role.HUNTER) return
        if (session.livingRunners().any { it.world == player.world && it.location.distance(player.location) <= 20 }) {
            player.warnTitle("无法打开", "必须离逃生超过 20 格")
            return
        }
        Gear.placeXpButtons(session.chest, session.xpBank)
        player.openInventory(session.chest)
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
                if (points <= 0) {
                    player.sendActionBar(Component.text("没有可存入的经验"))
                    return
                }
                player.clearExp()
                session.xpBank += points
                stored(player, session, points)
            }
            "deposit-some" -> {
                val points = if (player.level > 0) levelPointDelta(player.level, player.exp, -1) else player.expPoints()
                if (points <= 0) {
                    player.sendActionBar(Component.text("没有可存入的经验"))
                    return
                }
                if (player.level > 0) player.giveExpLevels(-1) else player.clearExp()
                session.xpBank += points
                stored(player, session, points)
            }
            "withdraw" -> {
                val points = session.xpBank
                if (points <= 0) {
                    player.sendActionBar(Component.text("经验池是空的"))
                    return
                }
                session.xpBank = 0
                player.giveExp(points)
                taken(player, session, points)
            }
            "withdraw-some" -> {
                if (session.xpBank <= 0) {
                    player.sendActionBar(Component.text("经验池是空的"))
                    return
                }
                val cost = levelPointDelta(player.level, player.exp, 1)
                val points = minOf(cost, session.xpBank)
                session.xpBank -= points
                if (points == cost) player.giveExpLevels(1) else player.giveExp(points)
                taken(player, session, points)
            }
            else -> player.sendMessage("/manhunt xp deposit|withdraw")
        }
    }

    private fun stored(player: Player, session: shina.manhunt.game.Session, points: Int) {
        Gear.placeXpButtons(session.chest, session.xpBank)
        player.sendActionBar(Component.text("已存入 $points 点，池中共 ${session.xpBank}"))
    }

    private fun taken(player: Player, session: shina.manhunt.game.Session, points: Int) {
        Gear.placeXpButtons(session.chest, session.xpBank)
        player.sendActionBar(Component.text("已取出 $points 点经验"))
    }

    fun cycle(player: Player) {
        val session = plugin.session
        val runners = session.livingRunners().sortedWith(compareBy({ it.name }, { it.uniqueId }))
        if (runners.isEmpty()) return
        val current = session.compassTarget[player.uniqueId]
        val others = runners.filter { it.uniqueId != current }
        if (others.isEmpty()) {
            player.sendActionBar(Component.text("没有其他逃生"))
            plugin.ticker.refreshHunterCompass(player)
            return
        }
        val index = runners.indexOfFirst { it.uniqueId == current }
        val stepped = runners[Math.floorMod(index + 1, runners.size)]
        val next = if (stepped.uniqueId == current) others.first() else stepped
        session.compassTarget[player.uniqueId] = next.uniqueId
        val nearest = plugin.ticker.refreshHunterCompass(player)
        val mark = if (nearest) " 【最近】" else ""
        player.sendActionBar(Component.text("追踪 ${next.name}$mark"))
    }

    fun teleportStrike() {
        val session = plugin.session
        val runners = session.livingRunners()
        if (runners.isEmpty()) return
        val runner = runners.random()
        val angle = Math.random() * Math.PI * 2
        val x = runner.location.blockX + (15 * cos(angle)).toInt()
        val z = runner.location.blockZ + (15 * sin(angle)).toInt()
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
        if (!plugin.session.early && hunter.location.y < 40) return "开局五分钟内不能在 Y 40 以下复活"
        if (runners.none { it.location.distance(hunter.location) in 40.0..300.0 }) return "需要至少一名逃生在 40 到 300 格内"
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

fun Player.expPoints(): Int = pointsAt(level, exp)

private fun levelPointDelta(level: Int, exp: Float, direction: Int): Int {
    val next = (level + direction).coerceAtLeast(0)
    return kotlin.math.abs(pointsAt(next, exp) - pointsAt(level, exp))
}

private fun pointsAt(level: Int, exp: Float): Int {
    fun toReach(value: Int): Int = when {
        value <= 16 -> value * value + 6 * value
        value <= 31 -> (2.5 * value * value - 40.5 * value + 360).toInt()
        else -> (4.5 * value * value - 162.5 * value + 2220).toInt()
    }
    fun toNext(value: Int): Int = when {
        value >= 31 -> 9 * value - 158
        value >= 16 -> 5 * value - 38
        else -> 2 * value + 7
    }
    val safe = level.coerceAtLeast(0)
    return toReach(safe) + ceil(exp.coerceIn(0f, 1f) * toNext(safe)).toInt()
}

fun Player.clearExp() {
    level = 0
    exp = 0f
    totalExperience = 0
}
