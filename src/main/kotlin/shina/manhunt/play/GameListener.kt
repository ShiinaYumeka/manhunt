package shina.manhunt.play

import org.bukkit.event.entity.PiglinBarterEvent
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.Difficulty
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityRegainHealthEvent
import org.bukkit.event.entity.PlayerDeathEvent
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
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.LootGenerateEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import shina.manhunt.Manhunt
import shina.manhunt.game.Match
import shina.manhunt.game.Phase
import shina.manhunt.game.Role
import shina.manhunt.kit.Gear
import java.util.UUID
import kotlin.math.ceil

class GameListener(private val plugin: Manhunt) : Listener {
    private val fireBlessingReady = mutableMapOf<UUID, Long>()
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (plugin.session.phase == Phase.LOBBY) Match.joinMessage(event.player)
        plugin.ticker.syncBoard()
    }

    @EventHandler
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to
        val from = event.from
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return
        val session = plugin.session
        if (session.phase == Phase.COUNTDOWN || event.player.uniqueId in session.downed) event.isCancelled = true
    }

    @EventHandler
    fun onWorldLoad(event: WorldLoadEvent) {
        event.world.setGameRule(GameRules.LOCATOR_BAR, false)
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
            plugin.ticker.drain(player, ceil(event.finalDamage).toInt().coerceAtLeast(1))
            return
        }
        val lethal = event.finalDamage >= player.health + player.absorptionAmount
        if (!lethal) return
        event.isCancelled = true
        if (plugin.ticker.protection(player) > 8) {
            session.protectionBlessing = true
            Match.eliminate(plugin, player)
            return
        }
        plugin.ticker.down(player, ceil(event.finalDamage).toInt().coerceAtLeast(1))
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
        if (event.player.uniqueId in plugin.session.downed) event.isCancelled = true
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand == org.bukkit.inventory.EquipmentSlot.OFF_HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val player = event.player
        if (player.gameMode == GameMode.SPECTATOR) {
            plugin.hunterLoop.confirm(player)
            return
        }
        val item = event.item ?: return
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
        }
    }

    @EventHandler
    fun onRescue(event: PlayerInteractEntityEvent) {
        if (event.hand == org.bukkit.inventory.EquipmentSlot.OFF_HAND) return
        val target = event.rightClicked as? Player ?: return
        val session = plugin.session
        if (target.uniqueId !in session.downed) return
        val rescuer = event.player
        if (rescuer.gameMode == GameMode.SPECTATOR) return
        if (session.role(rescuer.uniqueId) != Role.RUNNER || rescuer.uniqueId in session.downed) return
        session.rescue[rescuer.uniqueId] = target.uniqueId to System.currentTimeMillis()
        rescuer.sendMessage("正在救援，保持靠近 5 秒。")
    }

    @EventHandler
    fun onDrop(event: PlayerDropItemEvent) {
        if (!Gear.isHunterCompass(event.itemDrop.itemStack)) return
        event.isCancelled = true
        plugin.hunterLoop.cycle(event.player)
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        val session = plugin.session
        val player = event.player
        if (session.phase != Phase.PLAYING) return
        when (session.role(player.uniqueId)) {
            Role.HUNTER -> {
                event.drops.removeIf { Gear.isHunterCompass(it) || Gear.isTeamChest(it) }
                if (player.uniqueId in session.curseKill) {
                    session.curseKill.remove(player.uniqueId)
                    session.huntCooldownUntil[player.uniqueId] = System.currentTimeMillis() + 60_000
                    session.huntCountdownEnd.remove(player.uniqueId)
                }
            }
            Role.RUNNER -> Match.eliminate(plugin, player)
            null -> Unit
        }
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        val session = plugin.session
        val player = event.player
        if (session.phase != Phase.PLAYING) return
        if (session.role(player.uniqueId) != Role.HUNTER || player.uniqueId in session.eliminated) return
        plugin.server.scheduler.runTask(plugin, Runnable { plugin.hunterLoop.beginRespawn(player, opening = !session.early) })
    }

    @EventHandler
    fun onWorld(event: PlayerChangedWorldEvent) {
        val session = plugin.session
        val player = event.player
        if (session.phase != Phase.PLAYING || session.role(player.uniqueId) != Role.RUNNER) return
        val previous = session.currentSpot[player.uniqueId]
        if (previous?.world == event.from) session.exitSpot[player.uniqueId] = previous
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
            path == "chests/trial_chambers/reward_ominous" -> (event.entity as? Player)?.let {
                plugin.progress.award(it, "trial_ominous")
                plugin.session.netherUnlocked = true
            }
            path == "chests/trial_chambers/reward" -> (event.entity as? Player)?.let { plugin.progress.award(it, "trial_vault") }
        }
    }

    @EventHandler
    fun onBarter(event: PiglinBarterEvent) {
        for (item in event.outcome) {
            if (item.type == Material.ENDER_PEARL || item.type == Material.OBSIDIAN) {
                item.amount = (item.amount * 2).coerceAtMost(item.maxStackSize)
            }
        }
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
