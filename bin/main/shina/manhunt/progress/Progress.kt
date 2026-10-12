package shina.manhunt.progress

import io.papermc.paper.math.Position
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.potion.PotionType
import org.bukkit.advancement.Advancement
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.generator.structure.Structure
import org.bukkit.util.BoundingBox
import org.bukkit.inventory.ItemStack
import shina.manhunt.Manhunt
import shina.manhunt.play.warnTitle
import shina.manhunt.game.CompassGoal
import shina.manhunt.game.Match
import shina.manhunt.game.StructureMark
import shina.manhunt.game.Role
import shina.manhunt.game.Route
import shina.manhunt.kit.Gear
import java.io.File

class Progress(private val plugin: Manhunt) {
    fun install() {
        val root = File(plugin.dataFolder, "datapack")
        copyResources(root)
        writeAdvancements(File(root, "data/manhunt/advancement"))
        val restore = listOf(
            "data/minecraft/advancement/adventure/minecraft_trials_edition.json",
            "data/minecraft/advancement/adventure/under_lock_and_key.json",
            "data/minecraft/advancement/adventure/revaulting.json"
        )
        for (path in restore) File(root, path).delete()
        for (world in Bukkit.getWorlds()) {
            val dest = File(world.worldFolder, "datapacks/manhunt")
            root.copyRecursively(dest, overwrite = true)
            for (path in restore) File(dest, path).delete()
        }
        Bukkit.reloadData()
    }

    fun award(player: Player, id: String) {
        val session = plugin.session
        if (session.phase != shina.manhunt.game.Phase.PLAYING) return
        if (!allowed(id)) return
        val advancement = Bukkit.getAdvancement(NamespacedKey(plugin, id)) ?: return
        val progress = player.getAdvancementProgress(advancement)
        if (progress.isDone) return
        progress.remainingCriteria.toList().forEach { progress.awardCriteria(it) }
    }

    fun chooseRoute(player: Player, raw: String?) {
        val session = plugin.session
        val route = when (raw) {
            "nether" -> Route.NETHER
            "trial" -> Route.TRIAL
            "ancient" -> Route.ANCIENT
            "mineshaft" -> Route.MINESHAFT
            else -> {
                player.sendMessage("/manhunt route nether|trial|ancient|mineshaft")
                return
            }
        }
        if (session.role(player.uniqueId) != Role.RUNNER || player.uniqueId in session.eliminated) {
            player.warnTitle("无法选路", "只有逃生可以选择路线")
            return
        }
        if (session.route != null) {
            player.warnTitle("无法选路", "路线已经选定")
            return
        }
        if (!session.fullIron || session.nether) {
            player.warnTitle("无法选路", "现在不能选择路线")
            return
        }
        session.route = route
        if (route != Route.NETHER) session.netherLockUntil = System.currentTimeMillis() + 20 * 60 * 1000
        award(player, route.advancement)
    }

    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        val session = plugin.session
        if (session.phase != shina.manhunt.game.Phase.PLAYING) return
        val player = event.player
        if (player.uniqueId in session.suppress) return
        val key = event.advancement.key
        if (key == NamespacedKey.minecraft("end/kill_dragon")) Match.runnersWin(plugin)
        if (key == NamespacedKey.minecraft("story/enchant_item") && session.role(player.uniqueId) == Role.RUNNER) supplyEnchant()
        if (key.namespace == "manhunt") {
            reward(player, key.key)
            announce(player, event.advancement)
        }
        if (session.role(player.uniqueId) == Role.RUNNER) share(event.advancement, player)
    }

    fun scan(player: Player) {
        val count = player.inventory.contents.filterNotNull().groupBy { it.type }.mapValues { it.value.sumOf { item -> item.amount } }
        fun has(material: Material, amount: Int = 1) = (count[material] ?: 0) >= amount
        if (has(Material.BREAD, 32)) award(player, "bread")
        if ((count[Material.COOKED_PORKCHOP] ?: 0) + (count[Material.COOKED_BEEF] ?: 0) >= 32) award(player, "feast")
        if (has(Material.RAW_IRON, 5)) award(player, "miner")
        if (has(Material.RAW_IRON, 20)) award(player, "old_miner")
        if (has(Material.DIAMOND)) award(player, "got_diamond")
        if (has(Material.RAW_GOLD, 30)) award(player, "gold_miner")
        if (has(Material.CROSSBOW)) award(player, "crossbow")
        if (has(Material.BUCKET, 5)) award(player, "buckets")
        if (has(Material.DIAMOND_PICKAXE)) award(player, "efficiency")
        if (has(Material.ENDER_PEARL, 12)) award(player, "pearls")
        if (has(Material.OBSIDIAN, 20)) award(player, "obsidian")
        if (has(Material.BLAZE_ROD, 6)) award(player, "blaze")
        if (has(Material.DIAMOND_LEGGINGS, 3)) award(player, "leggings")
        if (has(Material.ENCHANTED_GOLDEN_APPLE)) award(player, "gapple")
        if (has(Material.HEAVY_CORE)) award(player, "heavy_core")
        if (has(Material.DIAMOND, 64)) award(player, "best_miner")
        if (inside(player, Structure.BASTION_REMNANT)) award(player, "bastion")
        if (inside(player, Structure.FORTRESS)) award(player, "fortress")
        if (inside(player, Structure.STRONGHOLD)) award(player, "stronghold")
        if (inside(player, Structure.TRIAL_CHAMBERS)) award(player, "trial_enter")
        if (inside(player, Structure.ANCIENT_CITY)) award(player, "ancient_enter")
        if (inside(player, Structure.MINESHAFT, Structure.MINESHAFT_MESA)) award(player, "mineshaft_enter")
        val enchant = Bukkit.getAdvancement(NamespacedKey.minecraft("story/enchant_item"))
        if (enchant != null && player.getAdvancementProgress(enchant).isDone) award(player, "quiet_ench")
    }

    fun nextStructure(player: Player): CompassGoal? {
        val visited = plugin.session.visited[player.uniqueId].orEmpty()
        val villages = listOf(
            Structure.VILLAGE_PLAINS,
            Structure.VILLAGE_DESERT,
            Structure.VILLAGE_SAVANNA,
            Structure.VILLAGE_SNOWY,
            Structure.VILLAGE_TAIGA
        )
        fun usable(found: Location) = horizontalDistanceSq(player.location, found) > 64.0 * 64.0 &&
            visited.none { it.covers(player.world.name, "village", found.x, found.z) }
        fun goal(found: Location) = CompassGoal(player.world.name, found.blockX, found.blockY, found.blockZ, "village")
        val closest = nearestLocation(player.world, player.location, villages, 160)
        if (closest != null && usable(closest)) return goal(closest)
        if (closest == null) {
            nearestLocation(player.world, player.location, villages, 320, ::usable)?.let { return goal(it) }
        }
        var best: Location? = null
        var bestDistance = Double.MAX_VALUE
        for (ring in intArrayOf(256, 512, 1024, 2048)) {
            for (step in 0 until 8) {
                val angle = step * Math.PI / 4.0
                val probe = player.location.clone().add(Math.cos(angle) * ring, 0.0, Math.sin(angle) * ring)
                val found = nearestLocation(player.world, probe, villages, 16, ::usable) ?: continue
                val distance = horizontalDistanceSq(player.location, found)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = found
                }
            }
            if (best != null) break
        }
        val found = best ?: return null
        return goal(found)
    }

    private fun reward(player: Player, id: String, shared: Boolean = false) {
        val session = plugin.session
        when (id) {
            "vitality" -> give(player, ItemStack(Material.IRON_INGOT, 3))
            "iron_pick" -> give(player, *Gear.copperLegsBoots().toTypedArray())
            "water" -> give(player, *Gear.copperHelmChest().toTypedArray())
            "nether" -> {
                session.reserve[player.uniqueId] = (session.reserve[player.uniqueId] ?: 0) + 60
                goalNear(player, listOf(Structure.BASTION_REMNANT), "bastion", 96)?.let {
                    give(player, Gear.structureCompass(it, "堡垒遗迹指南针"))
                }
                permanentAbsorption(player)
            }
            "bastion" -> {
                session.reserve[player.uniqueId] = (session.reserve[player.uniqueId] ?: 0) + 40
                give(player, ItemStack(Material.GOLDEN_HELMET), ItemStack(Material.IRON_INGOT, 2))
                growHealth(player, 2.0)
            }
            "pearls" -> goalNear(player, listOf(Structure.FORTRESS), "fortress", 96)?.let {
                give(player, Gear.structureCompass(it, "下界要塞指南针"))
            }
            "obsidian" -> give(player, ItemStack(Material.IRON_BOOTS), splashFireResistance())
            "fortress" -> session.reserve[player.uniqueId] = (session.reserve[player.uniqueId] ?: 0) + 60
            "blaze" -> {
                session.reserve[player.uniqueId] = (session.reserve[player.uniqueId] ?: 0) + 40
                give(player, ItemStack(Material.DIAMOND, 2), ItemStack(Material.BOOK))
            }
            "return_overworld" -> {
                val items = mutableListOf(
                    ItemStack(Material.IRON_INGOT, 10),
                    ItemStack(Material.DIAMOND, 4),
                    enchantingTableBook(20)
                )
                goalNear(player, listOf(Structure.STRONGHOLD), "stronghold", 256)?.let {
                    items += Gear.structureCompass(it, "要塞指南针")
                }
                give(player, *items.toTypedArray())
            }
            "stronghold" -> player.giveExp(2000)
            "enter_end" -> {
                session.reserve[player.uniqueId] = (session.reserve[player.uniqueId] ?: 0) + 100
                give(player, ItemStack(Material.DIAMOND, 8))
            }
            "full_iron" -> give(player, ItemStack(Material.IRON_INGOT, 7), ItemStack(Material.COBWEB, 4))
            "route_nether" -> give(player, unbreakingPick())
            "route_trial" -> {
                session.reserve[player.uniqueId] = (session.reserve[player.uniqueId] ?: 0) + 50
                goalNear(player, listOf(Structure.TRIAL_CHAMBERS), "trial", 96)?.let {
                    give(player, Gear.structureCompass(it, "试炼密室指南针"))
                }
            }
            "route_ancient" -> {
                goalNear(player, listOf(Structure.ANCIENT_CITY), "ancient", 128)?.let {
                    give(player, Gear.structureCompass(it, "古城指南针"))
                }
                effect(player, PotionEffectType.SPEED, 2 * 60)
            }
            "route_mineshaft" -> {
                session.reserve[player.uniqueId] = (session.reserve[player.uniqueId] ?: 0) + 20
                val mineshaft = goalNear(player, listOf(Structure.MINESHAFT, Structure.MINESHAFT_MESA), "mineshaft", 96)
                val compass = mineshaft?.let { Gear.structureCompass(it, "废弃矿井指南针") }
                if (compass != null) give(player, compass, ItemStack(Material.DIAMOND, 2)) else give(player, ItemStack(Material.DIAMOND, 2))
            }
            "bread" -> give(player, ItemStack(Material.APPLE, 4))
            "feast" -> give(player, ItemStack(Material.GOLD_INGOT, 4), ItemStack(Material.IRON_INGOT, 3))
            "miner" -> give(player, Gear.fastFurnace())
            "old_miner" -> give(player, ItemStack(Material.BLAST_FURNACE), ItemStack(Material.COAL, 8))
            "got_diamond" -> {
                session.reserve[player.uniqueId] = (session.reserve[player.uniqueId] ?: 0) + 10
                give(player, ItemStack(Material.IRON_INGOT, 5))
            }
            "gold_miner" -> give(player, ItemStack(Material.BLAST_FURNACE), ItemStack(Material.COAL_BLOCK, 2), ItemStack(Material.GOLDEN_APPLE))
            "crossbow" -> give(player, ItemStack(Material.ARROW, 5), slownessArrows())
            "buckets" -> give(player, ItemStack(Material.LAVA_BUCKET), ItemStack(Material.COBWEB, 8))
            "efficiency" -> give(player, stored(org.bukkit.enchantments.Enchantment.EFFICIENCY))
            "trial_enter" -> give(player, ItemStack(Material.COOKED_BEEF, 10), ItemStack(Material.WIND_CHARGE, 4))
            "trial_vault" -> session.reserve[player.uniqueId] = (session.reserve[player.uniqueId] ?: 0) + 50
            "trial_ominous" -> {
                session.reserve[player.uniqueId] = (session.reserve[player.uniqueId] ?: 0) + 20
                give(player, ItemStack(Material.GOLDEN_APPLE, 3))
            }
            "heavy_core" -> give(player, ItemStack(Material.BREEZE_ROD, 10))
            "ancient_enter" -> {
                give(player, Gear.swiftCopperLegs())
                effect(player, PotionEffectType.NIGHT_VISION, 4 * 60)
            }
            "gapple" -> {
                player.giveExp(1000)
                give(player, efficiencyHoe())
            }
            "leggings" -> give(player, ItemStack(Material.ENCHANTING_TABLE))
            "quiet_ench" -> {
                give(player, ItemStack(Material.LAPIS_LAZULI, 20))
                player.giveExp(2000)
            }
            "mineshaft_enter" -> give(player, ItemStack(Material.SHEARS), stored(org.bukkit.enchantments.Enchantment.FORTUNE), ItemStack(Material.ANVIL))
            "best_miner" -> give(player, ItemStack(Material.OBSIDIAN, 4), ItemStack(Material.GOLDEN_APPLE, 2))
        }
        if (shared) return
        when (id) {
            "stronghold" -> {
                session.wallEye = true
                session.hunters().forEach { it.giveExp(2000) }
            }
            "heavy_core" -> {
                session.heavyDream = true
                session.hunters().forEach { give(it, ItemStack(Material.TOTEM_OF_UNDYING)) }
            }
            "ancient_enter" -> session.hunters().forEach {
                give(it, Gear.swiftCopperLegs())
                effect(it, PotionEffectType.NIGHT_VISION, 4 * 60)
            }
            "quiet_ench" -> {
                session.hunters().forEach { it.giveExp(2000) }
                session.netherUnlocked = true
            }
            "best_miner" -> session.netherUnlocked = true
        }
    }

    private fun supplyEnchant() {
        val session = plugin.session
        if (!session.enchantSupplied) {
            session.enchantSupplied = true
            session.chest.addItem(ItemStack(Material.ENCHANTING_TABLE), ItemStack(Material.LAPIS_LAZULI, 64), ItemStack(Material.LAPIS_LAZULI, 64))
        }
    }

    private fun announce(player: Player, advancement: Advancement) {
        val display = advancement.display ?: return
        val hover = display.title().color(NamedTextColor.GREEN)
            .append(Component.newline())
            .append(display.description().color(NamedTextColor.GRAY))
        val title = display.title().color(NamedTextColor.GREEN).hoverEvent(HoverEvent.showText(hover))
        Bukkit.broadcast(player.displayName().append(Component.text(" 取得了进度 ")).append(title))
    }

    private fun allowed(id: String): Boolean {
        val route = plugin.session.route
        val branch = when {
            id.startsWith("trial_") || id == "heavy_core" -> Route.TRIAL
            id.startsWith("ancient_") || id == "gapple" || id == "leggings" || id == "quiet_ench" -> Route.ANCIENT
            id.startsWith("mineshaft_") || id == "best_miner" -> Route.MINESHAFT
            else -> null
        }
        return branch == null || branch == route
    }

    private fun share(advancement: Advancement, source: Player) {
        val session = plugin.session
        val manhunt = advancement.key.namespace == "manhunt"
        val id = advancement.key.key
        for (other in session.livingRunners()) {
            if (other == source) continue
            val progress = other.getAdvancementProgress(advancement)
            if (progress.isDone) continue
            session.suppress.add(other.uniqueId)
            try {
                progress.remainingCriteria.toList().forEach { progress.awardCriteria(it) }
            } finally {
                session.suppress.remove(other.uniqueId)
            }
            if (manhunt) {
                reward(other, id, shared = true)
                announce(other, advancement)
            }
        }
    }

    fun reached(player: Player, goal: CompassGoal): StructureMark? {
        if (player.world.name != goal.world) return null
        val chunk = player.location.chunk
        val generated = player.world.getStructures(chunk.x, chunk.z).firstOrNull { structure ->
            structureKind(structure.structure) == goal.kind &&
                structure.boundingBox.contains(player.location.x, player.location.y, player.location.z) &&
                goalIn(structure.boundingBox, goal)
        } ?: return null
        val box = generated.boundingBox
        return StructureMark(goal.world, goal.kind, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
    }

    private fun inside(player: Player, vararg structures: Structure): Boolean {
        val position = Position.block(player.location)
        return structures.any { player.world.hasStructureAt(position, it) }
    }

    private fun goalIn(box: BoundingBox, goal: CompassGoal): Boolean {
        val x = goal.x + 0.5
        val z = goal.z + 0.5
        return x in (box.minX - 24.0)..(box.maxX + 24.0) && z in (box.minZ - 24.0)..(box.maxZ + 24.0)
    }

    private fun structureKind(structure: Structure): String? = when (structure) {
        Structure.VILLAGE_PLAINS, Structure.VILLAGE_DESERT, Structure.VILLAGE_SAVANNA, Structure.VILLAGE_SNOWY, Structure.VILLAGE_TAIGA -> "village"
        Structure.SHIPWRECK, Structure.SHIPWRECK_BEACHED -> "ship"
        Structure.RUINED_PORTAL, Structure.RUINED_PORTAL_DESERT, Structure.RUINED_PORTAL_JUNGLE, Structure.RUINED_PORTAL_MOUNTAIN,
        Structure.RUINED_PORTAL_NETHER, Structure.RUINED_PORTAL_OCEAN, Structure.RUINED_PORTAL_SWAMP -> "portal"
        else -> null
    }

    private fun goalNear(player: Player, structures: List<Structure>, kind: String, radius: Int): CompassGoal? {
        val found = nearestLocation(player.world, player.location, structures, radius)
            ?: nearestLocation(player.world, player.location, structures, radius * 2)
            ?: return null
        return CompassGoal(player.world.name, found.blockX, found.blockY, found.blockZ, kind)
    }

    private fun nearestLocation(
        world: org.bukkit.World,
        origin: Location,
        structures: List<Structure>,
        radius: Int,
        accept: (Location) -> Boolean = { true }
    ): Location? {
        var best: Location? = null
        var bestDistance = Double.MAX_VALUE
        for (structure in structures) {
            val found = world.locateNearestStructure(origin, structure, radius, false)?.location ?: continue
            if (!accept(found)) continue
            val distance = horizontalDistanceSq(origin, found)
            if (distance < bestDistance) {
                bestDistance = distance
                best = found
            }
        }
        return best
    }

    private fun horizontalDistanceSq(from: Location, to: Location): Double {
        val dx = from.x - to.x
        val dz = from.z - to.z
        return dx * dx + dz * dz
    }

    private fun give(player: Player, vararg items: ItemStack) {
        val left = player.inventory.addItem(*items)
        left.values.forEach { player.world.dropItem(player.location, it) }
    }

    private fun stored(enchantment: org.bukkit.enchantments.Enchantment, level: Int = 1): ItemStack {
        val item = ItemStack(Material.ENCHANTED_BOOK)
        val meta = item.itemMeta as org.bukkit.inventory.meta.EnchantmentStorageMeta
        meta.addStoredEnchant(enchantment, level, true)
        item.itemMeta = meta
        return item
    }

    private fun enchantingTableBook(power: Int): ItemStack {
        val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
        val pool = registry.filter {
            !it.isCursed && !it.isTreasure && it.key != NamespacedKey.minecraft("lunge") && it.maxLevel > 0
        }
        var level = power + 1 + kotlin.random.Random.nextInt(2) + kotlin.random.Random.nextInt(2)
        level = (level * (1.0 + (Math.random() + Math.random() - 1.0) * 0.15)).toInt().coerceAtLeast(1)
        val chosen = mutableListOf<Pair<org.bukkit.enchantments.Enchantment, Int>>()
        var available = selectable(pool, level).toMutableList()
        while (available.isNotEmpty()) {
            val pick = available.random()
            chosen += pick
            if (kotlin.random.Random.nextInt(50) > level) break
            level /= 2
            if (level <= 0) break
            available = selectable(pool, level).filter { (ench, _) -> chosen.none { it.first == ench || it.first.conflictsWith(ench) } }.toMutableList()
        }
        if (chosen.isEmpty()) return stored(org.bukkit.enchantments.Enchantment.UNBREAKING, 1)
        val item = ItemStack(Material.ENCHANTED_BOOK)
        val meta = item.itemMeta as org.bukkit.inventory.meta.EnchantmentStorageMeta
        for ((enchantment, enchantLevel) in chosen) meta.addStoredEnchant(enchantment, enchantLevel, true)
        item.itemMeta = meta
        return item
    }

    private fun selectable(pool: List<org.bukkit.enchantments.Enchantment>, level: Int) = pool.mapNotNull { enchantment ->
        val enchantLevel = (enchantment.maxLevel downTo 1).firstOrNull { step ->
            enchantment.getMinModifiedCost(step) <= level && level <= enchantment.getMaxModifiedCost(step)
        } ?: return@mapNotNull null
        enchantment to enchantLevel
    }

    private fun splashFireResistance(): ItemStack {
        val item = ItemStack(Material.SPLASH_POTION)
        val meta = item.itemMeta as PotionMeta
        meta.basePotionType = PotionType.WATER
        meta.color = Color.fromRGB(0xE49A3A)
        meta.addCustomEffect(PotionEffect(PotionEffectType.FIRE_RESISTANCE, 8 * 60 * 20, 0), true)
        item.itemMeta = meta
        return item
    }

    private fun permanentAbsorption(player: Player) {
        val max = player.getAttribute(Attribute.MAX_ABSORPTION)
        if (max != null && max.baseValue < 20.0) max.baseValue = 20.0
        player.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, PotionEffect.INFINITE_DURATION, 4, false, false, true))
        if (player.absorptionAmount < 20.0) player.absorptionAmount = 20.0
    }

    private fun unbreakingPick(): ItemStack {
        val item = ItemStack(Material.GOLDEN_PICKAXE)
        item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING, 1)
        return item
    }

    private fun efficiencyHoe(): ItemStack {
        val item = ItemStack(Material.DIAMOND_HOE)
        item.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.EFFICIENCY, 4)
        return item
    }

    private fun slownessArrows(): ItemStack {
        val item = ItemStack(Material.TIPPED_ARROW, 2)
        val meta = item.itemMeta as PotionMeta
        meta.basePotionType = PotionType.LONG_SLOWNESS
        item.itemMeta = meta
        return item
    }

    private fun effect(player: Player, type: PotionEffectType, seconds: Int) {
        player.addPotionEffect(PotionEffect(type, seconds * 20, 0, false, true, true))
    }

    private fun growHealth(player: Player, points: Double) {
        val max = player.getAttribute(Attribute.MAX_HEALTH) ?: return
        max.baseValue += points
        player.health = (player.health + points).coerceAtMost(max.value)
    }

    private fun copyResources(root: File) {
        val files = listOf(
            "pack.mcmeta",
            "data/manhunt/enchantment/cooking.json",
            "data/minecraft/tags/enchantment/non_treasure.json",
            "data/minecraft/enchantment/fortune.json",
            "data/minecraft/worldgen/structure_set/villages.json",
            "data/minecraft/worldgen/structure_set/shipwrecks.json",
            "data/minecraft/worldgen/structure_set/ruined_portals.json",
            "data/minecraft/advancement/story/iron_tools.json",
            "data/minecraft/advancement/story/enter_the_nether.json",
            "data/minecraft/advancement/story/mine_diamond.json",
            "data/minecraft/advancement/story/follow_ender_eye.json",
            "data/minecraft/advancement/story/enter_the_end.json",
            "data/minecraft/advancement/story/form_obsidian.json",
            "data/minecraft/advancement/nether/find_bastion.json",
            "data/minecraft/advancement/nether/find_fortress.json",
            "data/minecraft/advancement/nether/obtain_blaze_rod.json"
        )
        for (path in files) {
            val input = plugin.getResource("datapack/$path") ?: continue
            val out = File(root, path)
            out.parentFile.mkdirs()
            input.use { data -> out.outputStream().use { data.copyTo(it) } }
        }
    }

    private fun writeAdvancements(dir: File) {
        dir.mkdirs()
        val nodes = listOf(
            node("start", "minecraft:story/root", "游戏开始", "游戏开始\n奖励：指向最近村庄的指南针", "minecraft:compass"),
            node("vitality", "manhunt:start", "生机", "抵达初始结构\n奖励：铁锭×3", "minecraft:iron_ingot"),
            node("iron_pick", "manhunt:vitality", "这不是铁镐么？", "获得铁镐\n奖励：铜护腿、铜靴子", "minecraft:iron_pickaxe"),
            node("water", "manhunt:iron_pick", "生命之源", "获得水桶\n奖励：铜头盔、铜胸甲", "minecraft:water_bucket"),
            node("nether", "manhunt:water", "勇往直下", "进入地狱\n奖励：堡垒遗迹指南针、永久伤害吸收 V、备用血×60", "minecraft:netherrack"),
            node("bastion", "manhunt:nether", "光辉岁月", "进入堡垒遗迹\n奖励：金头盔×1、铁锭×2、最大生命值+2、备用血×40", "minecraft:golden_helmet"),
            node("pearls", "manhunt:bastion", "阳光养猪场 I", "获得 12 个末影珍珠\n奖励：下界要塞指南针", "minecraft:ender_pearl"),
            node("obsidian", "manhunt:pearls", "阳光养猪场 II", "获得 20 个黑曜石\n奖励：铁靴子、8 分钟喷溅抗火药水", "minecraft:obsidian"),
            node("fortress", "manhunt:obsidian", "阴森的要塞", "进入下界要塞\n奖励：备用血×60", "minecraft:blaze_rod"),
            node("blaze", "manhunt:fortress", "烈焰升腾", "获得 6 个烈焰棒\n奖励：钻石×2、书×1、备用血×40", "minecraft:blaze_powder"),
            node("return_overworld", "manhunt:blaze", "我从地狱里回来了", "携带 12 个末影之眼返回主世界\n奖励：铁锭×10、钻石×4、要塞指南针、20 级附魔台结果的附魔书", "minecraft:ender_eye"),
            node("stronghold", "manhunt:return_overworld", "隔墙有眼", "进入末地要塞\n奖励：2000 经验，猎人也获得", "minecraft:ender_eye"),
            node("enter_end", "manhunt:stronghold", "末路决战", "进入末地\n奖励：每人钻石×8、备用血×100", "minecraft:end_stone"),
            node("full_iron", "manhunt:start", "整装待发", "获得全套铁盔甲\n奖励：铁锭×7、蜘蛛网×4", "minecraft:iron_chestplate"),
            node("route_nether", "manhunt:full_iron", "前往地狱", "继续主线速通\n奖励：每人耐久 I 金镐×1", "minecraft:golden_pickaxe"),
            node("route_trial", "manhunt:full_iron", "前往试炼密室", "前往试炼\n奖励：每人试炼密室指南针、备用血×50", "minecraft:trial_key"),
            node("route_ancient", "manhunt:full_iron", "前往古代城市", "追求宝藏和高级附魔\n奖励：每人古城指南针、速度 I（2 分钟）", "minecraft:sculk"),
            node("route_mineshaft", "manhunt:full_iron", "前往矿井", "争取获得大量钻石\n奖励：每人废弃矿井指南针、钻石×2、备用血×20", "minecraft:rail"),
            node("bread", "manhunt:start", "饱腹感", "拥有 32 个面包\n奖励：苹果×4", "minecraft:bread"),
            node("feast", "manhunt:start", "烧烤大餐", "拥有 32 个猪排或牛排\n奖励：金锭×4、铁锭×3", "minecraft:cooked_beef"),
            node("miner", "manhunt:start", "小矿工", "拥有 5 个粗铁\n奖励：高速熔炉", "minecraft:blast_furnace"),
            node("old_miner", "manhunt:start", "老矿工", "拥有 20 个粗铁\n奖励：高炉×1、煤炭×8", "minecraft:blast_furnace"),
            node("got_diamond", "manhunt:start", "钻石！", "获得钻石\n奖励：铁锭×5、备用血×10", "minecraft:diamond"),
            node("gold_miner", "manhunt:start", "黄金矿工", "获得 30 个粗金\n奖励：高炉×1、煤炭块×2、金苹果×1", "minecraft:raw_gold"),
            node("crossbow", "manhunt:start", "枪枪爆头好运连连", "获得一把弩\n奖励：箭×5、长效缓慢箭×2", "minecraft:crossbow"),
            node("buckets", "manhunt:start", "水泵运转中", "获得 5 个空桶\n奖励：岩浆桶×1、蜘蛛网×8", "minecraft:bucket"),
            node("efficiency", "manhunt:start", "高效", "获得钻石镐\n奖励：效率 I 附魔书", "minecraft:diamond_pickaxe"),
            node("trial_enter", "manhunt:route_trial", "Minecraft 试炼版", "进入试炼密室\n奖励：牛排×10、风弹×4", "minecraft:trial_spawner"),
            node("trial_vault", "manhunt:trial_enter", "珍藏密敛", "开启普通宝库\n奖励：备用血×50", "minecraft:vault"),
            node("trial_ominous", "manhunt:trial_vault", "饱经磨砺", "开启不祥宝库\n奖励：备用血×20、金苹果×3，解除地狱封锁", "minecraft:ominous_bottle"),
            node("heavy_core", "manhunt:trial_ominous", "沉重之梦", "获得沉重核心\n奖励：旋风棒×10，猎人图腾×1", "minecraft:heavy_core"),
            node("ancient_enter", "manhunt:route_ancient", "静谧之地", "进入古城\n奖励：所有人迅捷潜行 II 铜护腿、夜视（4 分钟）", "minecraft:sculk_sensor"),
            node("gapple", "manhunt:ancient_enter", "君临天下", "获得附魔金苹果\n奖励：1000 经验、效率 IV 钻石锄", "minecraft:enchanted_golden_apple"),
            node("leggings", "manhunt:gapple", "AAA 裤头批发", "获得三件钻石护腿\n奖励：附魔台", "minecraft:diamond_leggings"),
            node("quiet_ench", "manhunt:leggings", "静谧魔咒", "成为附魔师\n奖励：青金石×20、2000 经验，猎人也获得经验", "minecraft:enchanting_table"),
            node("mineshaft_enter", "manhunt:route_mineshaft", "废弃之地", "进入废弃矿井\n奖励：剪刀、时运 I 附魔书、铁砧", "minecraft:rail"),
            node("best_miner", "manhunt:mineshaft_enter", "最佳矿工", "获得 64 颗钻石\n奖励：黑曜石×4、金苹果×2，解除地狱封锁", "minecraft:diamond_block")
        )
        for ((id, json) in nodes) File(dir, "$id.json").writeText(json, Charsets.UTF_8)
    }

    private fun node(id: String, parent: String, title: String, description: String, icon: String): Pair<String, String> = id to """
        {
          "parent": "$parent",
          "display": {
            "icon": { "id": "$icon" },
            "title": { "text": ${json("[$title]")} },
            "description": { "text": ${json(description)} },
            "frame": "task",
            "show_toast": true,
            "announce_to_chat": false
          },
          "criteria": {
            "trigger": { "trigger": "minecraft:impossible" }
          },
          "requirements": [["trigger"]]
        }
    """.trimIndent()

    private fun json(value: String) = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }
        append('"')
    }
}
