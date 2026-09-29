package shina.manhunt.play

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import java.time.Duration
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.entity.Pose
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.CompassMeta
import shina.manhunt.Manhunt
import shina.manhunt.game.CompassGoal
import shina.manhunt.game.Phase
import shina.manhunt.game.Rule
import shina.manhunt.kit.Gear

class Ticker(private val plugin: Manhunt) {
    private val board = StatusBoard(plugin)

    fun tick() {
        val session = plugin.session
        if (session.phase != Phase.PLAYING) {
            board.refresh()
            return
        }
        val now = System.currentTimeMillis()
        rememberSpots()
        scanRunners(now)
        tickDowned()
        tickRescue(now)
        tickHunters(now)
        tickCombat(now)
        tickCompasses()
        board.refresh()
    }

    fun hideBoard() = board.hide()

    fun syncBoard() = board.refresh()

    fun protection(player: Player): Int = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET).sumOf { slot ->
        player.inventory.getItem(slot).getEnchantmentLevel(Enchantment.PROTECTION)
    }

    fun drain(player: Player, amount: Int) {
        val session = plugin.session
        val left = (session.reserve[player.uniqueId] ?: 0) - amount
        if (left <= 0) {
            session.reserve[player.uniqueId] = 0
            shina.manhunt.game.Match.eliminate(plugin, player)
        } else {
            session.reserve[player.uniqueId] = left
        }
    }

    fun down(player: Player, hit: Int) {
        val session = plugin.session
        val max = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        player.health = max / 2.0
        player.absorptionAmount = 0.0
        player.walkSpeed = 0f
        player.setCrawling(true)
        session.downed.add(player.uniqueId)
        drain(player, hit)
        player.warnTitle("濒死", "等待救援")
    }

    private fun tickDowned() {
        for (id in plugin.session.downed.toList()) {
            val player = Bukkit.getPlayer(id) ?: continue
            player.walkSpeed = 0f
            player.setCrawling(true)
            player.velocity = player.velocity.multiply(0)
            drain(player, 1)
        }
    }

    private fun tickRescue(now: Long) {
        val session = plugin.session
        val iterator = session.rescue.iterator()
        while (iterator.hasNext()) {
            val (rescuerId, pair) = iterator.next()
            val (targetId, start) = pair
            val rescuer = Bukkit.getPlayer(rescuerId)
            val target = Bukkit.getPlayer(targetId)
            if (rescuer == null || target == null || rescuer.gameMode == GameMode.SPECTATOR || targetId !in session.downed || rescuer.world != target.world || rescuer.location.distance(target.location) > 4) {
                iterator.remove()
                continue
            }
            if (now - start >= 5000) {
                iterator.remove()
                session.downed.remove(targetId)
                target.walkSpeed = 0.2f
                target.setCrawling(false)
                target.sendMessage(Component.text("你被救起了。"))
            }
        }
    }

    private fun scanRunners(now: Long) {
        val session = plugin.session
        if (!session.early && (now - session.startedAt >= 5 * 60 * 1000 || session.livingRunners().any { has(it, Material.STONE_PICKAXE) })) {
            session.early = true
        }
        for (player in session.livingRunners()) {
            if (has(player, Material.IRON_PICKAXE)) {
                session.mid = true
                plugin.progress.award(player, "iron_pick")
            }
            if (wearingArmor(player)) session.mid = true
            if (has(player, Material.WATER_BUCKET)) plugin.progress.award(player, "water")
            if (wearingFullIron(player)) {
                session.fullIron = true
                plugin.progress.award(player, "full_iron")
            }
            val armor = player.getAttribute(Attribute.ARMOR)?.value ?: 0.0
            if (armor >= 10) session.armor10.add(player.uniqueId)
            if (armor >= 18) session.armor18.add(player.uniqueId)
            if (protection(player) > 8) {
                session.protectionBlessing = true
                shina.manhunt.game.Match.eliminate(plugin, player)
                continue
            }
            plugin.progress.scan(player)
            updateRunnerCompass(player)
        }
        if (session.fullIron && !session.nether && session.route == null) shina.manhunt.game.Match.promptRoute(plugin)
    }

    private fun tickHunters(now: Long) {
        val session = plugin.session
        for (hunter in session.hunters()) {
            if (hunter.gameMode == GameMode.SPECTATOR) {
                val ready = session.respawnReadyAt[hunter.uniqueId]
                if (ready != null && now >= ready) hunter.sendActionBar(Component.text("右键确认复活"))
                continue
            }
            if (hunter.inventory.contents.none { it != null && Gear.isHunterCompass(it) }) hunter.inventory.addItem(Gear.hunterCompass())
            if (hunter.inventory.contents.none { it != null && Gear.isTeamChest(it) }) hunter.inventory.addItem(Gear.teamChest())
            val buckets = hunter.inventory.contents.filterNotNull().filter { it.type == Material.BUCKET }.sumOf { it.amount }
            if (buckets >= 5) plugin.progress.award(hunter, "buckets")
            if (!session.enabled(Rule.HUNT)) {
                if (session.huntCountdownEnd.remove(hunter.uniqueId) != null) hunter.clearTitle()
            } else {
                val cooldown = session.huntCooldownUntil[hunter.uniqueId] ?: 0L
                if (now >= cooldown) {
                    val nearest = session.livingRunners().filter { it.world == hunter.world }.minOfOrNull { it.location.distance(hunter.location) }
                    if (nearest == null || nearest > 300) {
                        val end = session.huntCountdownEnd.getOrPut(hunter.uniqueId) { now + 15_000 }
                        val left = ((end - now) / 1000).coerceAtLeast(0)
                        hunter.warnTitle("狩猎诅咒", "$left 秒后死亡", Duration.ofMillis(1200))
                        if (now >= end) {
                            session.huntCountdownEnd.remove(hunter.uniqueId)
                            session.curseKill.add(hunter.uniqueId)
                            hunter.health = 0.0
                        }
                    } else if (session.huntCountdownEnd.remove(hunter.uniqueId) != null) {
                        hunter.clearTitle()
                    }
                }
            }
            pointHunter(hunter)
        }
    }

    private fun tickCombat(now: Long) {
        val session = plugin.session
        if (!session.enabled(Rule.COMBAT)) {
            session.combatCountdownEnd = null
            return
        }
        val countdown = session.combatCountdownEnd
        if (countdown != null) {
            if (now >= countdown) {
                session.combatCountdownEnd = null
                session.lastCombat = now
                plugin.hunterLoop.teleportStrike()
                Bukkit.broadcast(Component.text("战斗诅咒把猎人拉到了逃生身边。"))
            }
            return
        }
        if (now - session.lastCombat >= 4 * 60 * 1000) {
            session.combatCountdownEnd = now + 10_000
            for (player in Bukkit.getOnlinePlayers()) {
                if (session.role(player.uniqueId) != null) player.warnTitle("战斗诅咒", "10 秒后传送")
            }
        }
    }

    private fun tickCompasses() = Unit

    private fun pointHunter(hunter: Player) {
        val session = plugin.session
        val targetId = session.compassTarget[hunter.uniqueId] ?: session.livingRunners().filter { it.world == hunter.world }.minByOrNull { it.location.distanceSquared(hunter.location) }?.uniqueId
        if (targetId == null) return
        session.compassTarget[hunter.uniqueId] = targetId
        val target = Bukkit.getPlayer(targetId)
        val spot = when {
            target != null && target.world == hunter.world -> target.location
            else -> session.exitSpot[targetId]?.takeIf { it.world == hunter.world }
        } ?: return
        aim(hunter, Gear::isHunterCompass, spot)
    }

    private fun updateRunnerCompass(player: Player) {
        val session = plugin.session
        val goal = session.goals[player.uniqueId]
        val mark = if (goal == null) null else plugin.progress.reached(player, goal)
        if (mark != null) {
            val seen = session.visited.getOrPut(player.uniqueId) { mutableListOf() }
            if (seen.none { it.covers(mark.world, mark.kind, (mark.minX + mark.maxX) / 2.0, (mark.minZ + mark.maxZ) / 2.0) }) seen.add(mark)
            session.goals.remove(player.uniqueId)
            if (session.vitality.add(player.uniqueId)) plugin.progress.award(player, "vitality")
        }
        if (session.goals[player.uniqueId] == null) {
            val next = plugin.progress.nextStructure(player)
            if (next == null) {
                if (mark != null) player.warnTitle("指南针", "附近没有下一座结构")
                return
            }
            session.goals[player.uniqueId] = next
            if (mark != null) hintNext(player, next)
        }
        val current = session.goals[player.uniqueId] ?: return
        val world = Bukkit.getWorld(current.world) ?: return
        aim(player, Gear::isRunnerCompass, Location(world, current.x.toDouble(), current.y.toDouble(), current.z.toDouble()))
    }

    private fun hintNext(player: Player, goal: CompassGoal) {
        val world = Bukkit.getWorld(goal.world) ?: return
        val distance = player.location.distance(Location(world, goal.x.toDouble(), player.location.y, goal.z.toDouble())).toInt()
        val name = when (goal.kind) {
            "village" -> "村庄"
            "ship" -> "沉船"
            "portal" -> "废弃传送门"
            else -> "结构"
        }
        player.sendMessage(Component.text("指南针指向下一座$name，约 $distance 格。").color(NamedTextColor.AQUA))
    }

    private fun aim(player: Player, match: (ItemStack) -> Boolean, spot: Location) {
        val item = player.inventory.contents.firstOrNull { it != null && match(it) } ?: return
        val meta = item.itemMeta as? CompassMeta ?: return
        meta.lodestone = spot
        meta.isLodestoneTracked = false
        item.itemMeta = meta
    }

    private fun rememberSpots() {
        for (player in plugin.session.livingRunners()) {
            plugin.session.currentSpot[player.uniqueId] = player.location.clone()
        }
    }

    private fun has(player: Player, material: Material) = player.inventory.contains(material)
    private fun wearingArmor(player: Player) = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET).any {
        player.inventory.getItem(it).type.equipmentSlot == it
    }
    private fun wearingFullIron(player: Player) =
        player.inventory.helmet?.type == Material.IRON_HELMET &&
            player.inventory.chestplate?.type == Material.IRON_CHESTPLATE &&
            player.inventory.leggings?.type == Material.IRON_LEGGINGS &&
            player.inventory.boots?.type == Material.IRON_BOOTS
}

fun Player.setCrawling(crawling: Boolean) {
    if (crawling) setPose(Pose.SWIMMING, true) else setPose(Pose.STANDING, false)
}

fun Player.warnTitle(title: String, subtitle: String = "", stay: Duration = Duration.ofSeconds(2)) {
    showTitle(Title.title(
        Component.text(title, NamedTextColor.RED),
        Component.text(subtitle, NamedTextColor.GOLD),
        Title.Times.times(Duration.ZERO, stay, Duration.ofMillis(200))
    ))
    playSound(this, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f)
}
