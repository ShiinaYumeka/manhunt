package shina.manhunt.game

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.GameMode
import org.bukkit.GameRules
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.block.Chest
import org.bukkit.block.structure.Mirror
import org.bukkit.block.structure.StructureRotation
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.structure.Structure
import shina.manhunt.play.setCrawling
import shina.manhunt.play.warnTitle
import org.bukkit.inventory.ItemStack
import shina.manhunt.Manhunt
import shina.manhunt.kit.Gear
import java.time.Duration
import java.util.Random

object Match {
    private const val HUT_STAND_X = 8
    private const val HUT_STAND_Z = 8
    private var hutStructure: Structure? = null

    fun preloadHut(plugin: Manhunt) {
        if (hutStructure != null) return
        val input = plugin.getResource("hut.nbt") ?: return
        hutStructure = input.use { Bukkit.getStructureManager().loadStructure(it) }
    }
    fun joinMessage(player: Player) {
        player.sendMessage(Component.text("选择阵营： ")
            .append(Component.text("[加入猎人]").color(NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/manhunt join hunter")))
            .append(Component.text(" "))
            .append(Component.text("[加入逃生]").color(NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/manhunt join runner"))))
    }

    fun start(plugin: Manhunt) {
        val session = plugin.session
        val runners = Bukkit.getOnlinePlayers().filter { session.role(it.uniqueId) == Role.RUNNER }
        val hunters = Bukkit.getOnlinePlayers().filter { session.role(it.uniqueId) == Role.HUNTER }
        if (runners.isEmpty() || hunters.isEmpty()) {
            Bukkit.broadcast(Component.text("至少需要一名猎人和一名逃生。").color(NamedTextColor.RED))
            return
        }
        session.phase = Phase.COUNTDOWN
        val world = Bukkit.getWorlds().first { it.environment == World.Environment.NORMAL }
        Bukkit.getWorlds().forEach {
            it.difficulty = Difficulty.EASY
            it.setGameRule(GameRules.IMMEDIATE_RESPAWN, true)
            it.setGameRule(GameRules.LOCATOR_BAR, false)
        }
        val hut = stand(plugin, world)
        placeSupplyChest(hut)
        warmVillageCompasses(plugin)
        session.chest = Bukkit.createInventory(session.chestHolder, 54, Component.text("团队仓库"))
        session.chestHolder.bind(session.chest)
        Gear.placeXpButtons(session.chest, session.xpBank)
        for (player in Bukkit.getOnlinePlayers()) {
            player.inventory.clear()
            player.absorptionAmount = 0.0
            when (session.role(player.uniqueId)) {
                Role.RUNNER -> {
                    player.gameMode = GameMode.SURVIVAL
                    player.teleport(hut)
                    player.health = 20.0
                    player.foodLevel = 20
                    player.saturation = 5f
                    session.reserve[player.uniqueId] = session.reserveMax
                    player.setRespawnLocation(hut, true)
                }
                Role.HUNTER -> player.gameMode = GameMode.SPECTATOR
                null -> player.gameMode = GameMode.SPECTATOR
            }
        }
        var left = 5
        Bukkit.getScheduler().runTaskTimer(plugin, { task ->
            if (left <= 0) {
                task.cancel()
                begin(plugin)
                return@runTaskTimer
            }
            Bukkit.broadcast(Component.text("游戏将在 $left 秒后开始").color(NamedTextColor.GOLD))
            left--
        }, 0L, 20L)
    }

    private fun begin(plugin: Manhunt) {
        val session = plugin.session
        session.phase = Phase.PLAYING
        session.startedAt = System.currentTimeMillis()
        session.lastCombat = session.startedAt
        for (player in session.livingRunners()) {
            player.gameMode = GameMode.SURVIVAL
            player.health = 20.0
            absorb(player)
            player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, 30 * 60 * 20, 0, false, true, true))
            player.inventory.addItem(Gear.runnerCompass())
            player.inventory.addItem(Gear.rallyItem())
            plugin.progress.award(player, "start")
        }
        for (hunter in session.hunters()) {
            plugin.hunterLoop.beginRespawn(hunter, opening = true)
        }
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (session.phase != Phase.PLAYING) return@Runnable
            for (runner in session.livingRunners()) clearAbsorb(runner)
        }, 5 * 60 * 20L)
        plugin.ticker.refreshGlowSoon()
        Bukkit.broadcast(Component.text("游戏开始。").color(NamedTextColor.GREEN))
    }

    private fun warmVillageCompasses(plugin: Manhunt) {
        val pending = plugin.session.livingRunners().map { it.uniqueId }.toMutableList()
        Bukkit.getScheduler().runTaskTimer(plugin, { task ->
            val session = plugin.session
            if (session.phase != Phase.COUNTDOWN && session.phase != Phase.PLAYING) {
                task.cancel()
                return@runTaskTimer
            }
            val id = pending.removeFirstOrNull() ?: run {
                task.cancel()
                return@runTaskTimer
            }
            val player = Bukkit.getPlayer(id) ?: return@runTaskTimer
            plugin.progress.retargetCompass(player, "village", skipCurrent = false, announce = false)
        }, 1L, 5L)
    }

    fun eliminate(plugin: Manhunt, player: Player) {
        val session = plugin.session
        if (session.role(player.uniqueId) != Role.RUNNER) return
        if (!session.eliminated.add(player.uniqueId)) return
        session.downed.remove(player.uniqueId)
        session.downedAt.remove(player.uniqueId)
        session.giveUpSince.remove(player.uniqueId)
        val rescuers = session.rescue.filterValues { it.first == player.uniqueId }.keys.toList()
        session.rescue.entries.removeIf { it.key == player.uniqueId || it.value.first == player.uniqueId }
        for (id in rescuers) {
            val rescuer = Bukkit.getPlayer(id) ?: continue
            if (rescuer.uniqueId !in session.downed) rescuer.walkSpeed = 0.2f
        }
        player.removePotionEffect(PotionEffectType.RESISTANCE)
        dropBelongings(player)
        player.walkSpeed = 0.2f
        player.setCrawling(false)
        player.gameMode = GameMode.SPECTATOR
        player.warnTitle("淘汰", "你被淘汰了")
        plugin.ticker.refreshGlowSoon()
        if (session.livingIds().isEmpty() && session.phase == Phase.PLAYING) huntersWin(plugin)
    }

    fun huntersWinIfAllDown(plugin: Manhunt) {
        val session = plugin.session
        if (session.phase != Phase.PLAYING) return
        val living = session.livingIds()
        if (living.isNotEmpty() && living.all { it in session.downed }) huntersWin(plugin)
    }

    fun huntersWin(plugin: Manhunt) {
        val session = plugin.session
        if (session.phase == Phase.ENDED) return
        session.phase = Phase.ENDED
        Bukkit.broadcast(Component.text("猎人胜利。").color(NamedTextColor.RED))
        val title = Title.title(
            Component.text("猎人胜利", NamedTextColor.RED),
            Component.text("GG", NamedTextColor.GOLD),
            Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(4), Duration.ofMillis(800))
        )
        for (player in Bukkit.getOnlinePlayers()) {
            player.showTitle(title)
            player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BIT, 0.7f, 1.0f)
        }
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BIT, 0.7f, 1.26f)
        }, 4L)
        showStats(plugin)
    }

    fun runnersWin(plugin: Manhunt) {
        val session = plugin.session
        if (session.phase != Phase.PLAYING) return
        session.phase = Phase.ENDED
        Bukkit.broadcast(Component.text("逃生胜利。").color(NamedTextColor.GREEN))
        showStats(plugin)
    }

    private fun showStats(plugin: Manhunt) {
        val session = plugin.session
        Bukkit.broadcast(Component.text("伤害统计").color(NamedTextColor.GOLD))
        val rows = session.roles.map { (id, role) ->
            val name = Bukkit.getOfflinePlayer(id).name ?: "未知"
            Triple(role, name, (session.damageDealt[id] ?: 0.0) to (session.damageTaken[id] ?: 0.0))
        }.sortedByDescending { it.third.first }
        for ((role, name, pair) in rows) {
            val side = if (role == Role.HUNTER) "猎人" else "逃生"
            val dealt = String.format("%.1f", pair.first)
            val taken = String.format("%.1f", pair.second)
            Bukkit.broadcast(Component.text("$side $name  造成 $dealt  承受 $taken"))
        }
    }

    fun promptRoute(plugin: Manhunt) {
        val session = plugin.session
        if (session.routePrompted || session.route != null || session.nether) return
        session.routePrompted = true
        val message = Component.text("选择路线： ")
            .append(link("前往地狱", "nether"))
            .append(Component.text(" "))
            .append(link("试炼密室", "trial"))
            .append(Component.text(" "))
            .append(link("古代城市", "ancient"))
            .append(Component.text(" "))
            .append(link("矿井", "mineshaft"))
        for (player in session.livingRunners()) {
            plugin.progress.reportRouteDistances(player)
            player.sendMessage(message)
        }
    }

    private fun dropBelongings(player: Player) {
        val loc = player.location
        val inventory = player.inventory
        for (stack in inventory.storageContents + inventory.armorContents + inventory.extraContents) {
            if (stack == null || stack.type == Material.AIR) continue
            player.world.dropItemNaturally(loc, stack.clone())
        }
        inventory.clear()
    }

    private fun absorb(player: Player) {
        val max = player.getAttribute(Attribute.MAX_ABSORPTION)
        if (max != null && max.baseValue < 30.0) max.baseValue = 30.0
        player.absorptionAmount = 30.0
    }

    private fun clearAbsorb(player: Player) {
        val effect = player.getPotionEffect(PotionEffectType.ABSORPTION)
        val kept = if (effect == null) 0.0 else (effect.amplifier + 1) * 4.0
        player.absorptionAmount = kept
        val max = player.getAttribute(Attribute.MAX_ABSORPTION) ?: return
        if (effect == null && max.baseValue == 30.0) max.baseValue = 0.0
        if (effect != null && max.baseValue == 30.0) max.baseValue = kept
        if (effect != null && max.baseValue < kept) max.baseValue = kept
    }

    private fun link(label: String, route: String): Component =
        Component.text("[$label]").color(NamedTextColor.YELLOW).clickEvent(ClickEvent.runCommand("/manhunt route $route"))

    fun ensureHut(plugin: Manhunt, world: World) {
        if (world.environment != World.Environment.NORMAL) return
        if (marker(world).isFile) return
        val origin = buildHut(plugin, world)
        marker(world).writeText("${origin.blockX} ${origin.blockY} ${origin.blockZ}")
    }

    fun stand(plugin: Manhunt, world: World): Location {
        ensureHut(plugin, world)
        val origin = readOrigin(world) ?: world.spawnLocation
        return origin.clone().add(HUT_STAND_X + 0.5, 1.0, HUT_STAND_Z + 0.5)
    }

    private fun placeSupplyChest(stand: Location) {
        val world = stand.world ?: return
        val block = world.getBlockAt(stand.blockX + 2, stand.blockY, stand.blockZ)
        block.type = Material.CHEST
        val chest = block.state as Chest
        chest.blockInventory.clear()
        chest.blockInventory.addItem(ItemStack(Material.STICK, Random().nextInt(2, 5)))
        chest.blockInventory.addItem(ItemStack(Material.ROTTEN_FLESH, Random().nextInt(6, 13)))
    }

    private fun buildHut(plugin: Manhunt, world: World): Location {
        preloadHut(plugin)
        val structure = hutStructure
        val spawn = world.spawnLocation
        return if (structure == null) plankHut(world, spawn) else placeHut(world, structure, spawn)
    }

    private fun marker(world: World) = java.io.File(world.worldFolder, "manhunt-hut.txt")

    private fun readOrigin(world: World): Location? {
        val file = marker(world)
        if (!file.isFile) return null
        val parts = file.readText().trim().split(" ")
        if (parts.size != 3) return null
        val x = parts[0].toIntOrNull() ?: return null
        val y = parts[1].toIntOrNull() ?: return null
        val z = parts[2].toIntOrNull() ?: return null
        return Location(world, x.toDouble(), y.toDouble(), z.toDouble())
    }

    private fun placeHut(world: World, structure: Structure, spawn: Location): Location {
        val size = structure.size
        val width = size.blockX.coerceAtLeast(1)
        val depth = size.blockZ.coerceAtLeast(1)
        var bestX = spawn.blockX - HUT_STAND_X
        var bestZ = spawn.blockZ - HUT_STAND_Z
        var bestScore = Int.MAX_VALUE
        var bestGround = surface(world, spawn.blockX, spawn.blockZ)
        for (radius in intArrayOf(0, 16, 32)) {
            val offsets = if (radius == 0) listOf(0 to 0) else ring(radius)
            for ((dx, dz) in offsets) {
                val ox = spawn.blockX + dx - HUT_STAND_X
                val oz = spawn.blockZ + dz - HUT_STAND_Z
                val heights = listOf(
                    surface(world, ox, oz),
                    surface(world, ox + width - 1, oz),
                    surface(world, ox, oz + depth - 1),
                    surface(world, ox + width - 1, oz + depth - 1),
                    surface(world, ox + width / 2, oz + depth / 2)
                )
                val score = heights.max() - heights.min()
                if (score < bestScore) {
                    bestScore = score
                    bestX = ox
                    bestZ = oz
                    bestGround = heights.max()
                }
            }
            if (bestScore <= 2) break
        }
        val origin = Location(world, bestX.toDouble(), bestGround.toDouble(), bestZ.toDouble())
        structure.place(origin, false, StructureRotation.NONE, Mirror.NONE, 0, 1f, Random())
        return origin
    }

    private fun plankHut(world: World, spawn: Location): Location {
        val x = spawn.blockX - HUT_STAND_X
        val z = spawn.blockZ - HUT_STAND_Z
        val y = surface(world, spawn.blockX, spawn.blockZ)
        for (dx in -2..2) for (dz in -2..2) for (dy in 0..3) {
            val block = world.getBlockAt(x + dx, y + dy, z + dz)
            val edge = dx == -2 || dx == 2 || dz == -2 || dz == 2 || dy == 0 || dy == 3
            block.type = if (edge) Material.OAK_PLANKS else Material.AIR
        }
        world.getBlockAt(x, y + 1, z + 2).type = Material.AIR
        world.getBlockAt(x, y + 2, z + 2).type = Material.AIR
        return Location(world, x.toDouble(), y.toDouble(), z.toDouble())
    }

    private fun surface(world: World, x: Int, z: Int): Int =
        world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES).coerceAtLeast(world.minHeight + 1)

    private fun ring(radius: Int): List<Pair<Int, Int>> {
        val points = mutableListOf<Pair<Int, Int>>()
        var n = -radius
        while (n <= radius) {
            points += n to -radius
            points += n to radius
            points += -radius to n
            points += radius to n
            n += 16
        }
        return points.distinct()
    }
}
