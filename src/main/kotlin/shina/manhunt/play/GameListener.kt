package shina.manhunt.play

import org.bukkit.event.entity.PiglinBarterEvent
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.entity.Warden
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.Difficulty
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityRegainHealthEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.FurnaceStartSmeltEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.GameRules
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import io.papermc.paper.event.player.PlayerTrackEntityEvent
import org.bukkit.event.world.LootGenerateEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.loot.Lootable
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import shina.manhunt.Manhunt
import shina.manhunt.game.Match
import shina.manhunt.game.Phase
import shina.manhunt.game.Role
import shina.manhunt.game.TeamChestHolder
import shina.manhunt.kit.Gear
import java.util.UUID
import kotlin.math.ceil

class GameListener(private val plugin: Manhunt) : Listener {
    private val fireBlessingReady = mutableMapOf<UUID, Long>()
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (plugin.session.phase == Phase.LOBBY) Match.joinMessage(event.player)
        plugin.ticker.syncBoard()
        plugin.ticker.refreshGlowSoon()
    }

    @EventHandler
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to
        val from = event.from
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return
        val session = plugin.session
        if (session.phase == Phase.COUNTDOWN || event.player.uniqueId in session.downed) {
            event.isCancelled = true
            return
        }
        if (event.player.uniqueId in session.rescue || plugin.ticker.holdingRescue(event.player)) {
            to.x = from.x
            to.y = from.y
            to.z = from.z
        }
    }

    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        event.world.setGameRule(GameRules.LOCATOR_BAR, false)
        Match.ensureHut(plugin, event.world)
        if (plugin.session.phase != Phase.LOBBY) {
            event.world.difficulty = Difficulty.EASY
            event.world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true)
        }
    }

    @EventHandler
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        val session = plugin.session
        if (session.phase == Phase.COUNTDOWN) {
            event.isCancelled = true
            return
        }
        if (session.phase != Phase.PLAYING || session.role(player.uniqueId) != Role.RUNNER) {
            blessFire(player, event)
            return
        }
        blessFire(player, event)
        if (player.uniqueId in session.eliminated) {
            event.isCancelled = true
            return
        }
        if (player.uniqueId in session.downed) {
            event.isCancelled = true
            return
        }
        val lethal = event.finalDamage >= player.health + player.absorptionAmount
        if (!lethal) return
        event.isCancelled = true
        noteHit(event)
        if (plugin.ticker.protection(player) > 8) {
            session.protectionBlessing = true
            Match.eliminate(plugin, player)
            return
        }
        val source = event.damageSource
        val attacked = event is EntityDamageByEntityEvent || source.causingEntity != null || source.directEntity != null
        val cost = if (attacked) 20 else ceil(event.finalDamage).toInt().coerceAtLeast(1)
        plugin.ticker.down(player, cost)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamageRecord(event: EntityDamageEvent) {
        noteHit(event)
    }

    @EventHandler
    fun onHit(event: EntityDamageByEntityEvent) {
        val session = plugin.session
        val attacker = attacker(event)
        if (attacker != null && attacker.uniqueId in session.downed) {
            event.isCancelled = true
            return
        }
        if (session.phase != Phase.PLAYING) return
        val victim = event.entity as? Player ?: return
        if (victim.uniqueId in session.downed) {
            event.isCancelled = true
            return
        }
        if (attacker == null) return
        val roles = setOf(session.role(victim.uniqueId), session.role(attacker.uniqueId))
        if (roles == setOf(Role.HUNTER, Role.RUNNER) && event.finalDamage > 3) {
            session.lastCombat = System.currentTimeMillis()
        }
    }

    @EventHandler
    fun onRegen(event: EntityRegainHealthEvent) {
        val player = event.entity as? Player ?: return
        val session = plugin.session
        if (session.phase != Phase.PLAYING || session.role(player.uniqueId) != Role.RUNNER) return
        if (event.regainReason != EntityRegainHealthEvent.RegainReason.SATIATED) return
        val reserve = session.reserve[player.uniqueId] ?: 0
        if (reserve <= 0 || player.uniqueId in session.downed) {
            event.isCancelled = true
            return
        }
        val cost = ceil(event.amount).toInt().coerceAtLeast(1)
        if (reserve <= cost) {
            event.amount = reserve.toDouble()
            session.reserve[player.uniqueId] = 0
        } else {
            session.reserve[player.uniqueId] = reserve - cost
        }
    }

    @EventHandler
    fun onPlace(event: BlockPlaceEvent) {
        if (event.player.uniqueId in plugin.session.downed) {
            event.isCancelled = true
            return
        }
        if (!Gear.isFastFurnace(event.itemInHand)) return
        val state = event.blockPlaced.state as? org.bukkit.block.TileState ?: return
        Gear.markFurnace(state)
    }

    @EventHandler
    fun onBreak(event: BlockBreakEvent) {
        if (event.block.type == Material.SPAWNER) {
            event.isCancelled = true
            return
        }
        val type = event.block.type
        if ((type == Material.DIAMOND_ORE || type == Material.DEEPSLATE_DIAMOND_ORE) && hunter(event.player)) {
            event.isCancelled = true
            return
        }
        if (unopenedStructureChest(event.block) && hunter(event.player)) {
            event.isCancelled = true
            event.player.warnTitle("无法破坏", "战利品还没生成")
        }
    }

    @EventHandler
    fun onEntityExplode(event: EntityExplodeEvent) {
        val hunterBlast = causedByHunter(event.entity)
        event.blockList().removeIf { it.type == Material.SPAWNER || (hunterBlast && unopenedStructureChest(it)) }
    }

    @EventHandler
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf { it.type == Material.SPAWNER }
    }

    @EventHandler
    fun onBreakFurnace(event: BlockDropItemEvent) {
        val state = event.blockState as? org.bukkit.block.TileState ?: return
        if (!Gear.isFastFurnace(state)) return
        for (drop in event.items) {
            if (drop.itemStack.type == Material.BLAST_FURNACE) drop.itemStack = Gear.fastFurnace()
        }
    }

    @EventHandler
    fun onSmelt(event: FurnaceStartSmeltEvent) {
        val state = event.block.state as? org.bukkit.block.TileState ?: return
        if (!Gear.isFastFurnace(state)) return
        val type = event.source.type
        when {
            type.name.endsWith("_ORE") || type == Material.RAW_IRON || type == Material.RAW_GOLD ||
                type == Material.RAW_COPPER || type == Material.ANCIENT_DEBRIS -> event.totalCookTime = 20
            type == Material.SAND || type == Material.RED_SAND -> event.totalCookTime = 40
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onUnopenedChestClick(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (!hunter(event.player) || !unopenedStructureChest(block)) return
        event.setUseInteractedBlock(Event.Result.DENY)
        if (event.player.gameMode != GameMode.SPECTATOR) event.player.warnTitle("无法打开", "战利品还没生成")
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onUnopenedLootEntity(event: PlayerInteractEntityEvent) {
        val loot = event.rightClicked as? Lootable ?: return
        if (loot.lootTable == null || !hunter(event.player)) return
        event.isCancelled = true
        if (event.player.gameMode != GameMode.SPECTATOR) event.player.warnTitle("无法打开", "战利品还没生成")
    }

    @EventHandler
    fun onUnopenedLootVehicle(event: VehicleDestroyEvent) {
        val loot = event.vehicle as? Lootable ?: return
        if (loot.lootTable == null || !causedByHunter(event.attacker)) return
        event.isCancelled = true
        (event.attacker as? Player)?.warnTitle("无法破坏", "战利品还没生成")
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val player = event.player
        if (event.hand == org.bukkit.inventory.EquipmentSlot.OFF_HAND) {
            val off = event.item
            if (off != null && !trackingCompass(player.inventory.itemInMainHand) && trackingCompass(off)) {
                event.isCancelled = true
                resetCompass(player, off)
            }
            return
        }
        if (player.gameMode == GameMode.SPECTATOR) {
            plugin.hunterLoop.confirm(player)
            return
        }
        val item = event.item ?: return
        if (Gear.isRally(item)) {
            event.isCancelled = true
            plugin.ticker.useRally(player)
            return
        }
        if (Gear.isGift(item)) {
            event.isCancelled = true
            val hand = event.hand ?: return
            val loot = Gear.openGift(item)
            val stack = player.inventory.getItem(hand)
            if (Gear.isGift(stack)) {
                stack.amount -= 1
                if (stack.amount <= 0) player.inventory.setItem(hand, null)
            }
            val left = player.inventory.addItem(*loot.toTypedArray())
            left.values.forEach { player.world.dropItem(player.location, it) }
            return
        }
        if (Gear.isTeamChest(item)) {
            event.isCancelled = true
            plugin.hunterLoop.openChest(player)
            return
        }
        if (trackingCompass(item)) {
            event.isCancelled = true
            resetCompass(player, item)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onStructureChestOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        if (!hunter(player) || !unopenedStructureChest(event.inventory.location?.block)) return
        event.isCancelled = true
        player.warnTitle("无法打开", "战利品还没生成")
    }

    private fun hunter(player: Player) = plugin.session.role(player.uniqueId) == Role.HUNTER

    private fun causedByHunter(entity: org.bukkit.entity.Entity?): Boolean {
        val player = when (entity) {
            is Player -> entity
            is Projectile -> entity.shooter as? Player
            is TNTPrimed -> entity.source as? Player
            else -> null
        } ?: return false
        return hunter(player)
    }

    private fun unopenedStructureChest(block: Block?): Boolean {
        if (block == null) return false
        if (unopenedChestBlock(block)) return true
        val data = block.blockData as? org.bukkit.block.data.type.Chest ?: return false
        if (data.type == org.bukkit.block.data.type.Chest.Type.SINGLE) return false
        val side = if (data.type == org.bukkit.block.data.type.Chest.Type.LEFT) leftOf(data.facing) else rightOf(data.facing)
        return unopenedChestBlock(block.getRelative(side))
    }

    private fun unopenedChestBlock(block: Block): Boolean =
        (block.state as? Lootable)?.lootTable != null

    private fun leftOf(facing: BlockFace) = when (facing) {
        BlockFace.NORTH -> BlockFace.WEST
        BlockFace.WEST -> BlockFace.SOUTH
        BlockFace.SOUTH -> BlockFace.EAST
        BlockFace.EAST -> BlockFace.NORTH
        else -> facing
    }

    private fun rightOf(facing: BlockFace) = when (facing) {
        BlockFace.NORTH -> BlockFace.EAST
        BlockFace.EAST -> BlockFace.SOUTH
        BlockFace.SOUTH -> BlockFace.WEST
        BlockFace.WEST -> BlockFace.NORTH
        else -> facing
    }

    private fun trackingCompass(item: ItemStack?): Boolean =
        item != null && (Gear.isHunterCompass(item) || Gear.compassKind(item) != null)

    private fun resetCompass(player: Player, item: ItemStack) {
        if (Gear.isHunterCompass(item)) {
            plugin.hunterLoop.cycle(player)
            return
        }
        val kind = Gear.compassKind(item) ?: return
        plugin.progress.retargetCompass(player, kind, skipCurrent = true, announce = true)
    }

    @EventHandler
    fun onSneak(event: PlayerToggleSneakEvent) {
        if (event.isSneaking) return
        val id = event.player.uniqueId
        if (plugin.session.rescue.remove(id) != null && id !in plugin.session.downed) {
            event.player.walkSpeed = 0.2f
        }
    }

    @EventHandler
    fun onDrop(event: PlayerDropItemEvent) {
        if (Gear.isHunterCompass(event.itemDrop.itemStack)) event.isCancelled = true
    }

    @EventHandler
    fun onCompassClick(event: InventoryClickEvent) {
        if (event.click != ClickType.DROP && event.click != ClickType.CONTROL_DROP) return
        val item = event.currentItem ?: return
        if (Gear.isHunterCompass(item)) event.isCancelled = true
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        val session = plugin.session
        val player = event.player
        if (session.phase != Phase.PLAYING) return
        when (session.role(player.uniqueId)) {
            Role.HUNTER -> {
                event.drops.removeIf { Gear.isHunterCompass(it) || Gear.isTeamChest(it) }
                val killer = player.killer
                if (killer != null && session.role(killer.uniqueId) == Role.RUNNER) {
                    killer.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, 60, 1, false, true, true))
                }
            }
            Role.RUNNER -> {
                event.drops.clear()
                Match.eliminate(plugin, player)
            }
            null -> Unit
        }
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        val session = plugin.session
        val player = event.player
        if (session.phase != Phase.PLAYING) return
        if (session.role(player.uniqueId) != Role.HUNTER || player.uniqueId in session.eliminated) return
        plugin.server.scheduler.runTask(plugin, Runnable {
            plugin.hunterLoop.beginRespawn(player, opening = !session.early)
            plugin.ticker.refreshGlowSoon()
        })
    }

    @EventHandler
    fun onWorld(event: PlayerChangedWorldEvent) {
        val session = plugin.session
        val player = event.player
        plugin.ticker.refreshGlowSoon()
        if (session.phase != Phase.PLAYING || session.role(player.uniqueId) != Role.RUNNER) return
        val previous = session.currentSpot[player.uniqueId]
        if (previous?.world == event.from) {
            session.exitSpot[player.uniqueId] = previous
            session.dimensionSpot.getOrPut(player.uniqueId) { mutableMapOf() }[event.from.name] = previous.clone()
        }
        if (player.world.environment == World.Environment.NETHER) {
            session.nether = true
            session.wasInNether.add(player.uniqueId)
            plugin.progress.award(player, "nether")
        }
        if (player.world.environment == World.Environment.THE_END) plugin.progress.award(player, "enter_end")
        if (event.from.environment == World.Environment.NETHER && player.world.environment == World.Environment.NORMAL && count(player, Material.ENDER_EYE) >= 12) {
            plugin.progress.award(player, "return_overworld")
        }
    }

    @EventHandler
    fun onPortal(event: PlayerPortalEvent) {
        if (event.cause != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return
        if (!plugin.session.netherLocked()) return
        event.isCancelled = true
        event.player.warnTitle("地狱封锁", "传送门暂时无法传送")
    }

    @EventHandler
    fun onChestClick(event: InventoryClickEvent) {
        val top = event.view.topInventory
        if (top.holder !is TeamChestHolder) return
        val onButton = event.rawSlot == Gear.XP_SLOT || Gear.isXpButton(event.currentItem)
        if (!onButton && !Gear.isXpButton(event.cursor)) return
        event.isCancelled = true
        if (!onButton) return
        val player = event.whoClicked as? Player ?: return
        when (event.click) {
            ClickType.SHIFT_LEFT -> plugin.hunterLoop.xp(player, "deposit-some")
            ClickType.SHIFT_RIGHT -> plugin.hunterLoop.xp(player, "withdraw-some")
            ClickType.LEFT -> plugin.hunterLoop.xp(player, "deposit")
            ClickType.RIGHT -> plugin.hunterLoop.xp(player, "withdraw")
            else -> player.sendActionBar(net.kyori.adventure.text.Component.text("左键存入全部，右键取出全部，Shift 为 1 级"))
        }
    }

    @EventHandler
    fun onChestDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder !is TeamChestHolder) return
        if (event.rawSlots.any { it == Gear.XP_SLOT } || Gear.isXpButton(event.oldCursor)) event.isCancelled = true
    }

    @EventHandler
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        plugin.progress.onAdvancement(event)
    }

    @EventHandler
    fun onLoot(event: LootGenerateEvent) {
        val key = event.lootTable.key
        if (key.namespace != "minecraft") return
        val path = key.key
        val loot = event.loot
        when {
            path.startsWith("chests/village/") -> loot.add(ItemStack(Material.IRON_NUGGET, kotlin.random.Random.nextInt(5, 19)))
            path == "chests/shipwreck_treasure" -> loot.add(ItemStack(Material.IRON_INGOT, 7))
            path == "chests/shipwreck_supply" -> loot.add(ItemStack(Material.BREAD, 5))
            path.startsWith("chests/ruined_portal") -> {
                loot.removeIf { it.type.isArmor() || it.type.name.endsWith("_SHOVEL") || it.type.name.endsWith("_HOE") }
                loot.add(ItemStack(Material.IRON_SWORD))
                loot.add(ItemStack(Material.IRON_AXE))
                loot.add(ItemStack(Material.IRON_PICKAXE))
                loot.add(ItemStack(Material.IRON_NUGGET, 20))
            }
            path.startsWith("chests/bastion") -> {
                loot.filter { it.type == Material.IRON_INGOT || it.type == Material.DIAMOND }.forEach { it.amount = (it.amount * 2).coerceAtMost(it.maxStackSize) }
                if (loot.none { it.type == Material.DIAMOND } && Math.random() < 0.2) loot.add(ItemStack(Material.DIAMOND))
            }
            path.startsWith("chests/trial_chambers/reward_ominous") || path.startsWith("trial_chambers/reward_ominous") ->
                lootRunner(event)?.let { plugin.progress.award(it, "trial_ominous") }
            path.startsWith("chests/trial_chambers/reward") || path.startsWith("trial_chambers/reward") ->
                lootRunner(event)?.let { plugin.progress.award(it, "trial_vault") }
        }
    }

    private fun lootRunner(event: LootGenerateEvent): Player? {
        val direct = event.entity as? Player
        if (direct != null && plugin.session.role(direct.uniqueId) == Role.RUNNER) return direct
        val loc = event.lootContext.location
        val world = loc.world ?: return null
        return world.players
            .filter { plugin.session.role(it.uniqueId) == Role.RUNNER && it.location.distanceSquared(loc) <= 64.0 }
            .minByOrNull { it.location.distanceSquared(loc) }
    }

    @EventHandler
    fun onWarden(event: CreatureSpawnEvent) {
        val warden = event.entity as? Warden ?: return
        if (event.spawnReason != CreatureSpawnEvent.SpawnReason.NATURAL) return
        warden.getAttribute(Attribute.MAX_HEALTH)?.baseValue = 100.0
        warden.health = 100.0
    }

    @EventHandler
    fun onBarter(event: PiglinBarterEvent) {
        val outcome = event.outcome
        for (item in outcome) {
            if (item.type == Material.OBSIDIAN) item.amount = (item.amount + 2).coerceAtMost(item.maxStackSize)
        }
        if (outcome.none { it.type == Material.ENDER_PEARL } && Math.random() < 50.0 / 449.0) {
            outcome.add(ItemStack(Material.ENDER_PEARL, kotlin.random.Random.nextInt(2, 5)))
        }
        if (Math.random() < 0.5) outcome.add(ItemStack(Material.OBSIDIAN, kotlin.random.Random.nextInt(1, 4)))
    }

    private fun noteHit(event: EntityDamageEvent) {
        val session = plugin.session
        if (session.phase != Phase.PLAYING) return
        val victim = event.entity as? Player ?: return
        if (session.role(victim.uniqueId) == null) return
        val amount = event.finalDamage
        if (amount <= 0.0) return
        session.damageTaken[victim.uniqueId] = (session.damageTaken[victim.uniqueId] ?: 0.0) + amount
        if (event is EntityDamageByEntityEvent) {
            val attacker = attacker(event)
            if (attacker != null && attacker != victim && session.role(attacker.uniqueId) != null) {
                session.damageDealt[attacker.uniqueId] = (session.damageDealt[attacker.uniqueId] ?: 0.0) + amount
            }
        }
    }

    @EventHandler
    fun onTrack(event: PlayerTrackEntityEvent) {
        val mate = event.entity as? Player ?: return
        plugin.ticker.glowTeammate(event.player, mate)
    }

    private fun blessFire(player: Player, event: EntityDamageEvent) {
        if (!plugin.session.enabled(shina.manhunt.game.Rule.FIRE)) return
        if (player.world.environment != World.Environment.NETHER) return
        if (event.cause != EntityDamageEvent.DamageCause.FIRE && event.cause != EntityDamageEvent.DamageCause.FIRE_TICK && event.cause != EntityDamageEvent.DamageCause.LAVA) return
        if (player.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)) return
        val now = System.currentTimeMillis()
        if (now < (fireBlessingReady[player.uniqueId] ?: 0L)) return
        fireBlessingReady[player.uniqueId] = now + 45_000
        player.addPotionEffect(PotionEffect(PotionEffectType.FIRE_RESISTANCE, 15 * 20, 0, true, true))
    }

    private fun attacker(event: EntityDamageByEntityEvent): Player? {
        val damager = event.damager
        if (damager is Player) return damager
        if (damager is Projectile) return damager.shooter as? Player
        return null
    }
}

private fun Material.isArmor(): Boolean = equipmentSlot.name in setOf("HEAD", "CHEST", "LEGS", "FEET")

private fun count(player: Player, material: Material): Int = player.inventory.contents.filterNotNull().filter { it.type == material }.sumOf { it.amount }
