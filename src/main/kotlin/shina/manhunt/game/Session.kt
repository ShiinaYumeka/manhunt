package shina.manhunt.game

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import shina.manhunt.Manhunt
import java.util.UUID

enum class Phase { LOBBY, COUNTDOWN, PLAYING, ENDED }

enum class Role { HUNTER, RUNNER }

enum class Rule(val id: String, val label: String) {
    HUNT("hunt", "狩猎诅咒"),
    COMBAT("combat", "战斗诅咒"),
    FIRE("fire", "浴火祝福"),
    LOCK("lock", "地狱封锁");

    companion object {
        fun from(raw: String?) = entries.firstOrNull { it.id.equals(raw, true) || it.label == raw }
    }
}

enum class Route(val advancement: String) {
    NETHER("route_nether"),
    TRIAL("route_trial"),
    ANCIENT("route_ancient"),
    MINESHAFT("route_mineshaft")
}

class TeamChestHolder : InventoryHolder {
    private lateinit var backing: Inventory
    fun bind(inventory: Inventory) {
        backing = inventory
    }
    override fun getInventory(): Inventory = backing
}

class CompassGoal(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val kind: String
) {
    val key: String get() = "$world:$kind:${x shr 6}:${z shr 6}"
}

class StructureMark(
    val world: String,
    val kind: String,
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double
) {
    fun covers(worldName: String, kindName: String, x: Double, z: Double): Boolean {
        if (world != worldName || kind != kindName) return false
        return x in (minX - 24.0)..(maxX + 24.0) && z in (minZ - 24.0)..(maxZ + 24.0)
    }
}

class Session(val plugin: Manhunt) {
    var phase = Phase.LOBBY
    var respawnSeconds = 35
    var reserveMax = 400
    val roles = mutableMapOf<UUID, Role>()
    val reserve = mutableMapOf<UUID, Int>()
    val eliminated = mutableSetOf<UUID>()
    val downed = mutableSetOf<UUID>()
    val rescue = mutableMapOf<UUID, Pair<UUID, Long>>()
    val giveUpSince = mutableMapOf<UUID, Long>()
    var rallyLeader: UUID? = null
    var rallyUntil = 0L
    val rallyChannel = mutableMapOf<UUID, Pair<Location, Long>>()
    var startedAt = 0L
    var early = false
    var mid = false
    var nether = false
    var heavyDream = false
    var wallEye = false
    var protectionBlessing = false
    var enchantSupplied = false
    var fullIron = false
    var routePrompted = false
    var route: Route? = null
    var netherLockUntil = 0L
    var netherUnlocked = false
    var lastCombat = 0L
    var combatCountdownEnd: Long? = null
    val armor15 = mutableSetOf<UUID>()
    val armor18 = mutableSetOf<UUID>()
    val suppress = mutableSetOf<UUID>()
    val huntCooldownUntil = mutableMapOf<UUID, Long>()
    val huntCountdownEnd = mutableMapOf<UUID, Long>()
    val curseKill = mutableSetOf<UUID>()
    val respawnReadyAt = mutableMapOf<UUID, Long>()
    val compassTarget = mutableMapOf<UUID, UUID>()
    val currentSpot = mutableMapOf<UUID, Location>()
    val exitSpot = mutableMapOf<UUID, Location>()
    val dimensionSpot = mutableMapOf<UUID, MutableMap<String, Location>>()
    val goals = mutableMapOf<UUID, MutableMap<String, CompassGoal>>()
    val visited = mutableMapOf<UUID, MutableList<StructureMark>>()
    val rules = Rule.entries.associateWith { true }.toMutableMap()
    val vitality = mutableSetOf<UUID>()
    val wasInNether = mutableSetOf<UUID>()
    var xpBank = 0
    val chestHolder = TeamChestHolder()
    lateinit var chest: Inventory

    fun noteSpot(player: Player) {
        currentSpot[player.uniqueId] = player.location.clone()
        dimensionSpot.getOrPut(player.uniqueId) { mutableMapOf() }[player.world.name] = player.location.clone()
    }

    fun lastIn(id: UUID, worldName: String): Location? = dimensionSpot[id]?.get(worldName)

    fun role(id: UUID): Role? = roles[id]

    fun livingIds(): Set<UUID> = roles.filter { it.value == Role.RUNNER && it.key !in eliminated }.keys

    fun livingRunners(): List<Player> = livingIds().mapNotNull { Bukkit.getPlayer(it) }

    fun hunters(): List<Player> = Bukkit.getOnlinePlayers().filter { role(it.uniqueId) == Role.HUNTER }

    fun enabled(rule: Rule): Boolean = rules[rule] != false

    fun netherLocked(now: Long = System.currentTimeMillis()): Boolean {
        if (!enabled(Rule.LOCK)) return false
        val chosen = route
        return chosen != null && chosen != Route.NETHER && !netherUnlocked && now < netherLockUntil
    }

    fun key(path: String): NamespacedKey = NamespacedKey(plugin, path)
}
