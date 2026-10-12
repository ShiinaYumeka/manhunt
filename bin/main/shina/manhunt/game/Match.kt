package shina.manhunt.game

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.GameMode
import org.bukkit.GameRules
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.block.Chest
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import shina.manhunt.play.setCrawling
import shina.manhunt.play.warnTitle
import org.bukkit.inventory.ItemStack
import shina.manhunt.Manhunt
import shina.manhunt.kit.Gear
import kotlin.random.Random

object Match {
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
        val hut = buildHut(world)
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
        Bukkit.broadcast(Component.text("游戏开始。").color(NamedTextColor.GREEN))
    }

    fun eliminate(plugin: Manhunt, player: Player) {
        val session = plugin.session
        if (session.role(player.uniqueId) != Role.RUNNER) return
        if (!session.eliminated.add(player.uniqueId)) return
        session.downed.remove(player.uniqueId)
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
        if (session.livingIds().isEmpty() && session.phase == Phase.PLAYING) {
            session.phase = Phase.ENDED
            Bukkit.broadcast(Component.text("猎人胜利。").color(NamedTextColor.RED))
        }
    }

    fun huntersWinIfAllDown(plugin: Manhunt) {
        val session = plugin.session
        if (session.phase != Phase.PLAYING) return
        val living = session.livingIds()
        if (living.isNotEmpty() && living.all { it in session.downed }) {
            session.phase = Phase.ENDED
            Bukkit.broadcast(Component.text("猎人胜利。").color(NamedTextColor.RED))
        }
    }

    fun runnersWin(plugin: Manhunt) {
        val session = plugin.session
        if (session.phase != Phase.PLAYING) return
        session.phase = Phase.ENDED
        Bukkit.broadcast(Component.text("逃生胜利。").color(NamedTextColor.GREEN))
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
        for (player in session.livingRunners()) player.sendMessage(message)
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

    private fun buildHut(world: World): Location {
        val ground = world.getHighestBlockYAt(0, 0).coerceAtLeast(world.minHeight + 1)
        val y = ground + 1
        for (x in -2..2) for (z in -2..2) for (dy in 0..3) {
            val block = world.getBlockAt(x, y + dy, z)
            val edge = x == -2 || x == 2 || z == -2 || z == 2 || dy == 0 || dy == 3
            val door = z == 2 && x == 0 && dy in 1..2
            block.type = if (edge && !door) Material.OAK_PLANKS else Material.AIR
        }
        val chestBlock = world.getBlockAt(0, y, 0)
        chestBlock.type = Material.CHEST
        val chest = chestBlock.state as Chest
        chest.blockInventory.addItem(ItemStack(Material.STICK, Random.nextInt(2, 5)))
        chest.blockInventory.addItem(ItemStack(Material.ROTTEN_FLESH, Random.nextInt(6, 13)))
        return Location(world, 0.5, y + 1.0, 1.5)
    }
}
