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
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import shina.manhunt.Manhunt
import shina.manhunt.game.CompassGoal
import shina.manhunt.game.Phase
import shina.manhunt.game.Rule
import shina.manhunt.kit.Gear

class Ticker(private val plugin: Manhunt) {
    private val board = StatusBoard(plugin)
    private val teamGlow = TeamGlow(plugin)

    fun tick() {
        val session = plugin.session
        if (session.phase != Phase.PLAYING) {
            board.refresh()
            return
        }
        val now = System.currentTimeMillis()
        rememberSpots()
        scanRunners(now)
        tickDowned(now)
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
        if (!session.downed.add(player.uniqueId)) return
        session.downedAt[player.uniqueId] = System.currentTimeMillis()
        drain(player, hit)
        syncBoard()
        if (player.uniqueId in session.eliminated) return
        val max = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        player.health = max / 2.0
        player.absorptionAmount = 0.0
        player.walkSpeed = 0f
        player.setCrawling(true)
        player.world.playSound(player.location, Sound.ENTITY_PLAYER_HURT, 1f, 0.6f)
        player.warnTitle("濒死", "等待救援")
        for (mate in session.livingRunners()) {
            if (mate == player) continue
            mate.playSound(mate, Sound.ENTITY_PLAYER_HURT, 1f, 0.6f)
            mate.warnTitle("队友倒地", player.name)
        }
        shina.manhunt.game.Match.huntersWinIfAllDown(plugin)
    }

    private fun tickDowned(now: Long) {
        val session = plugin.session
        for (id in session.downed.toList()) {
            val player = Bukkit.getPlayer(id) ?: continue
            player.walkSpeed = 0f
            player.setCrawling(true)
            player.velocity = player.velocity.multiply(0)
            val since = session.downedAt[id] ?: now
            if (now - since >= 1000) drain(player, 1)
            if (player.isSneaking) {
                val start = session.giveUpSince.getOrPut(id) { now }
                val left = ((5000 - (now - start) + 999) / 1000).coerceAtLeast(0)
                player.sendActionBar(Component.text("按住潜行放弃治疗 $left 秒"))
                if (now - start >= 5000) {
                    session.giveUpSince.remove(id)
                    shina.manhunt.game.Match.eliminate(plugin, player)
                }
            } else {
                session.giveUpSince.remove(id)
            }
        }
        shina.manhunt.game.Match.huntersWinIfAllDown(plugin)
    }

    fun holdingRescue(player: Player): Boolean {
        val session = plugin.session
        if (session.phase != Phase.PLAYING || session.role(player.uniqueId) != shina.manhunt.game.Role.RUNNER) return false
        if (player.uniqueId in session.downed || player.gameMode == GameMode.SPECTATOR) return false
        if (!player.isSneaking || player.isInWater) return false
        return rescueTarget(player) != null
    }

    private fun rescueTarget(rescuer: Player): Player? {
        val session = plugin.session
        return session.livingRunners().filter {
            it.uniqueId in session.downed && it.world == rescuer.world && !it.isInWater && it.location.distance(rescuer.location) <= 4
        }.minByOrNull { it.location.distanceSquared(rescuer.location) }
    }

    private fun tickRescue(now: Long) {
        val session = plugin.session
        val active = mutableSetOf<java.util.UUID>()
        for (rescuer in session.livingRunners()) {
            if (!holdingRescue(rescuer)) continue
            val target = rescueTarget(rescuer) ?: continue
            active.add(rescuer.uniqueId)
            val existing = session.rescue[rescuer.uniqueId]
            val start = if (existing != null && existing.first == target.uniqueId) existing.second else now
            session.rescue[rescuer.uniqueId] = target.uniqueId to start
            rescuer.walkSpeed = 0f
            rescuer.velocity = rescuer.velocity.multiply(0)
            val left = ((5000 - (now - start) + 999) / 1000).coerceAtLeast(0)
            rescuer.sendActionBar(Component.text("按住救援 $left 秒"))
            if (now - start >= 5000) {
                session.rescue.remove(rescuer.uniqueId)
                session.downed.remove(target.uniqueId)
                session.downedAt.remove(target.uniqueId)
                session.giveUpSince.remove(target.uniqueId)
                rescuer.walkSpeed = 0.2f
                target.walkSpeed = 0.2f
                target.setCrawling(false)
                target.sendMessage(Component.text("你被救起了。"))
            }
        }
        for (id in session.rescue.keys.toList()) {
            if (id in active) continue
            session.rescue.remove(id)
            val player = Bukkit.getPlayer(id) ?: continue
            if (player.uniqueId !in session.downed) player.walkSpeed = 0.2f
        }
        for (runner in session.livingRunners()) {
            if (runner.uniqueId in session.downed || runner.uniqueId in session.rescue) continue
            if (runner.walkSpeed == 0f) runner.walkSpeed = 0.2f
        }
    }

    private var glowQueued = false

    fun refreshGlowSoon() {
        if (glowQueued) return
        glowQueued = true
        plugin.server.scheduler.runTask(plugin, Runnable {
            glowQueued = false
            refreshGlow()
        })
    }

    fun refreshGlow() {
        val session = plugin.session
        if (session.phase == Phase.ENDED) {
            teamGlow.clear()
            return
        }
        val pairs = mutableListOf<Pair<Player, Player>>()
        val runners = session.livingRunners()
        for (viewer in runners) for (mate in runners) if (viewer != mate) pairs += viewer to mate
        val hunters = session.hunters()
        val visibleHunters = hunters.filter { it.gameMode != GameMode.SPECTATOR }
        for (viewer in hunters) for (mate in visibleHunters) if (viewer != mate) pairs += viewer to mate
        teamGlow.apply(pairs)
    }

    fun glowTeammate(viewer: Player, mate: Player) {
        val session = plugin.session
        if (session.phase != Phase.PLAYING || viewer == mate) return
        val viewerRole = session.role(viewer.uniqueId) ?: return
        val mateRole = session.role(mate.uniqueId) ?: return
        if (viewerRole != mateRole) return
        if (viewerRole == shina.manhunt.game.Role.RUNNER && (viewer.uniqueId in session.eliminated || mate.uniqueId in session.eliminated)) return
        if (mateRole == shina.manhunt.game.Role.HUNTER && mate.gameMode == GameMode.SPECTATOR) return
        teamGlow.show(viewer, mate)
    }

    private fun restoreHunterHealth() {
        for (hunter in plugin.session.hunters()) {
            val max = hunter.getAttribute(Attribute.MAX_HEALTH) ?: continue
            if (max.baseValue >= 20.0) continue
            max.baseValue = 20.0
        }
    }

    fun useRally(player: Player): Boolean {
        val session = plugin.session
        if (session.phase != Phase.PLAYING || session.role(player.uniqueId) != shina.manhunt.game.Role.RUNNER) return false
        if (player.uniqueId in session.downed || player.gameMode == GameMode.SPECTATOR || player.uniqueId in session.eliminated) {
            player.warnTitle("无法集结", "当前状态不能使用")
            return false
        }
        if (session.rallyUsed) {
            player.warnTitle("无法集结", "集结已经用过")
            return false
        }
        session.rallyUsed = true
        val dest = player.location.clone()
        for (mate in session.livingRunners()) {
            if (mate == player || mate.uniqueId in session.downed || mate.gameMode == GameMode.SPECTATOR) continue
            mate.teleport(dest)
            mate.fallDistance = 0f
            mate.sendMessage(Component.text("已被召回到 ${player.name} 身边。").color(NamedTextColor.GREEN))
        }
        for (online in Bukkit.getOnlinePlayers()) {
            val inventory = online.inventory
            for (slot in inventory.storageContents.indices) {
                val item = inventory.storageContents[slot] ?: continue
                if (Gear.isRally(item)) inventory.setItem(slot, null)
            }
        }
        player.sendMessage(Component.text("已召回队友。").color(NamedTextColor.GREEN))
        return true
    }

    private fun scanRunners(now: Long) {
        val session = plugin.session
        if (!session.early && (now - session.startedAt >= 5 * 60 * 1000 || session.livingRunners().any { has(it, Material.STONE_PICKAXE) })) {
            session.early = true
        }
        val resistLeft = 30 * 60 * 1000 - (now - session.startedAt)
        for (player in session.livingRunners()) {
            val resist = player.getPotionEffect(PotionEffectType.RESISTANCE)
            if (resistLeft > 0) {
                val ticks = ((resistLeft + 49) / 50).toInt().coerceAtLeast(20)
                if (resist == null || (resist.amplifier == 0 && resist.duration < ticks - 20)) {
                    player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, ticks, 0, false, true, true))
                }
            } else if (resist != null && resist.amplifier == 0) {
                player.removePotionEffect(PotionEffectType.RESISTANCE)
            }
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
            if (armor >= 15 && session.armor15.add(player.uniqueId) && session.armor15.size == 1) restoreHunterHealth()
            if (armor >= 18) session.armor18.add(player.uniqueId)
            if (protection(player) > 8) {
                session.protectionBlessing = true
                shina.manhunt.game.Match.eliminate(plugin, player)
                continue
            }
            plugin.progress.scan(player)
            updateStructureCompasses(player)
        }
        if (session.fullIron && !session.nether && session.route == null) shina.manhunt.game.Match.promptRoute(plugin)
    }

    private fun tickHunters(now: Long) {
        val session = plugin.session
        for (hunter in session.hunters()) {
            if (hunter.gameMode == GameMode.SPECTATOR) {
                val ready = session.respawnReadyAt[hunter.uniqueId]
                if (ready != null) {
                    val allowed = plugin.hunterLoop.respawnAllowed(hunter)
                    val spot = Component.text(if (allowed) "可以复活" else "不可以复活", if (allowed) NamedTextColor.GREEN else NamedTextColor.RED)
                    if (now < ready) {
                        val left = ((ready - now + 999) / 1000).coerceAtLeast(0)
                        hunter.sendActionBar(Component.text("复活还需 $left 秒  ").append(spot))
                    } else {
                        hunter.sendActionBar(if (allowed) Component.text("可以复活，右键确认", NamedTextColor.GREEN) else spot)
                    }
                }
                continue
            }
            plugin.hunterLoop.ensureHunterTools(hunter)
            val buckets = hunter.inventory.contents.filterNotNull().filter { it.type == Material.BUCKET }.sumOf { it.amount }
            if (buckets >= 5) plugin.progress.award(hunter, "buckets")
            if (!session.enabled(Rule.HUNT)) {
                if (session.huntCountdownEnd.remove(hunter.uniqueId) != null) hunter.clearTitle()
            } else {
                val cooldown = session.huntCooldownUntil[hunter.uniqueId] ?: 0L
                if (now >= cooldown) {
                    val nearest = session.livingRunners().filter { it.world == hunter.world }.minOfOrNull { it.location.distance(hunter.location) }
                    if (nearest == null || nearest > 300) {
                        val end = session.huntCountdownEnd.getOrPut(hunter.uniqueId) { now + 10_000 }
                        if (now >= end) {
                            session.huntCountdownEnd.remove(hunter.uniqueId)
                            session.huntCooldownUntil[hunter.uniqueId] = now + 60_000
                            session.huntReturn.add(hunter.uniqueId)
                            hunter.clearTitle()
                            plugin.hunterLoop.beginRespawn(hunter, opening = !session.early, immediate = true)
                        } else {
                            val left = ((end - now + 999) / 1000).coerceAtLeast(1)
                            hunter.warnTitle("狩猎诅咒", "$left 秒后进入待复活", Duration.ofMillis(1200))
                        }
                    } else if (session.huntCountdownEnd.remove(hunter.uniqueId) != null) {
                        hunter.clearTitle()
                    }
                }
            }
            pointHunter(hunter)
        }
    }

    fun refreshHunterCompass(hunter: Player): Boolean = pointHunter(hunter)

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

    private fun pointHunter(hunter: Player): Boolean {
        val session = plugin.session
        val living = session.livingRunners()
        var targetId = session.compassTarget[hunter.uniqueId]
        if (targetId == null || living.none { it.uniqueId == targetId }) {
            targetId = living.filter { it.world == hunter.world }.minByOrNull { it.location.distanceSquared(hunter.location) }?.uniqueId
                ?: living.firstOrNull()?.uniqueId
        }
        if (targetId == null) return false
        session.compassTarget[hunter.uniqueId] = targetId
        val target = Bukkit.getPlayer(targetId)
        val name = target?.name ?: Bukkit.getOfflinePlayer(targetId).name ?: "未知"
        val nearest = target != null && closestHunter(hunter, target)
        for (item in hunter.inventory.contents) {
            if (item != null && Gear.isHunterCompass(item)) Gear.labelHunterCompass(item, name, nearest)
        }
        val spot = when {
            target != null && target.world == hunter.world -> target.location
            else -> session.lastIn(targetId, hunter.world.name) ?: session.exitSpot[targetId]?.takeIf { it.world == hunter.world }
        } ?: return nearest
        aim(hunter, Gear::isHunterCompass, spot)
        return nearest
    }

    private fun closestHunter(hunter: Player, target: Player): Boolean {
        if (hunter.world != target.world || hunter.gameMode == GameMode.SPECTATOR) return false
        val distance = hunter.location.distanceSquared(target.location)
        return plugin.session.hunters().none { other ->
            other != hunter && other.gameMode != GameMode.SPECTATOR && other.world == target.world &&
                other.location.distanceSquared(target.location) < distance
        }
    }

    private fun updateStructureCompasses(player: Player) {
        val session = plugin.session
        val map = session.goals.getOrPut(player.uniqueId) { mutableMapOf() }
        val kinds = player.inventory.storageContents.mapNotNull { Gear.compassKind(it) }.toSet()
        for (kind in kinds) {
            val goal = map[kind]
            val mark = if (goal == null) null else plugin.progress.reached(player, goal)
            if (mark != null) {
                val seen = session.visited.getOrPut(player.uniqueId) { mutableListOf() }
                if (seen.none { it.covers(mark.world, mark.kind, (mark.minX + mark.maxX) / 2.0, (mark.minZ + mark.maxZ) / 2.0) }) seen.add(mark)
                map.remove(kind)
                if (kind == "village" && session.vitality.add(player.uniqueId)) plugin.progress.award(player, "vitality")
                plugin.progress.retargetCompass(player, kind, skipCurrent = false, announce = true)
                continue
            }
            if (map[kind] == null) plugin.progress.retargetCompass(player, kind, skipCurrent = false, announce = false)
            else pointStructureCompass(player, kind, map.getValue(kind))
        }
    }

    fun pointStructureCompass(player: Player, kind: String, goal: CompassGoal) {
        val world = Bukkit.getWorld(goal.world) ?: return
        aim(player, { Gear.compassKind(it) == kind }, Location(world, goal.x.toDouble(), goal.y.toDouble(), goal.z.toDouble()))
    }

    private fun aim(player: Player, match: (ItemStack) -> Boolean, spot: Location) {
        if (spot.world != player.world) return
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            val item = inventory.getItem(slot) ?: continue
            if (!match(item)) continue
            val meta = item.itemMeta as? CompassMeta ?: continue
            val current = meta.lodestone
            val same = current != null && current.world == spot.world &&
                current.blockX == spot.blockX && current.blockY == spot.blockY && current.blockZ == spot.blockZ
            if (same && !meta.isLodestoneTracked) continue
            meta.lodestone = spot
            meta.isLodestoneTracked = false
            item.itemMeta = meta
        }
    }

    private fun rememberSpots() {
        for (player in plugin.session.livingRunners()) plugin.session.noteSpot(player)
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
