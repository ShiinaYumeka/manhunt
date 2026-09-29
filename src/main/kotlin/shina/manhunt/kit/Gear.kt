package shina.manhunt.kit

import com.google.common.collect.ArrayListMultimap
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.AttackRange
import io.papermc.paper.datacomponent.item.Consumable
import io.papermc.paper.datacomponent.item.CustomModelData
import io.papermc.paper.datacomponent.item.FoodProperties
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers
import io.papermc.paper.datacomponent.item.ItemLore
import io.papermc.paper.datacomponent.item.KineticWeapon
import io.papermc.paper.datacomponent.item.TooltipDisplay
import io.papermc.paper.datacomponent.item.Weapon
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.persistence.PersistentDataType
import shina.manhunt.Manhunt
import shina.manhunt.game.Session
import kotlin.random.Random

object Gear {
    private val kitKey get() = NamespacedKey(Manhunt.instance, "kit")
    private val hunterCompass get() = NamespacedKey(Manhunt.instance, "hunter_compass")
    private val runnerCompass get() = NamespacedKey(Manhunt.instance, "runner_compass")
    private val chestKey get() = NamespacedKey(Manhunt.instance, "team_chest")
    private val giftKey get() = NamespacedKey(Manhunt.instance, "gift")

    fun isKit(item: ItemStack): Boolean = item.itemMeta?.persistentDataContainer?.has(kitKey, PersistentDataType.BYTE) == true
    fun isHunterCompass(item: ItemStack): Boolean = item.itemMeta?.persistentDataContainer?.has(hunterCompass, PersistentDataType.BYTE) == true
    fun isRunnerCompass(item: ItemStack): Boolean = item.itemMeta?.persistentDataContainer?.has(runnerCompass, PersistentDataType.BYTE) == true
    fun isTeamChest(item: ItemStack): Boolean = item.itemMeta?.persistentDataContainer?.has(chestKey, PersistentDataType.BYTE) == true
    fun isGift(item: ItemStack): Boolean = item.itemMeta?.persistentDataContainer?.has(giftKey, PersistentDataType.STRING) == true

    fun hunterCompass(): ItemStack = named(Material.COMPASS, "猎人指南针", NamedTextColor.RED).also { mark(it, hunterCompass) }
    fun runnerCompass(): ItemStack = named(Material.COMPASS, "结构指南针", NamedTextColor.AQUA).also { mark(it, runnerCompass) }
    fun teamChest(): ItemStack = named(Material.CHEST, "团队仓库", NamedTextColor.GOLD).also { mark(it, chestKey) }

    fun loadout(session: Session): List<ItemStack> {
        if (!session.early) return emptyList()
        val level = when {
            session.heavyDream || session.armor18.isNotEmpty() -> 4
            session.nether -> 3
            session.mid -> 2
            true -> 1
            else -> 0
        }
        val items = mutableListOf<ItemStack>()
        when (level) {
            1 -> {
                items += many(Material.OAK_LOG, 5)
                items += many(Material.COBBLESTONE, 8)
                items += many(Material.BREAD, 3)
                items += gift("origin", "起源礼盒")
            }
            2 -> {
                items += many(Material.OAK_LOG, 5)
                items += many(Material.COBBLESTONE, 10)
                items += fixed(Material.STONE_AXE, 1)
                items += fixed(Material.STONE_PICKAXE, 1)
                items += many(Material.IRON_INGOT, 2)
                items += fixed(damageLeft(Material.GOLDEN_PICKAXE, 10))
                items += many(Material.BREAD, 10)
                items += gift(if (session.armor10.isEmpty()) "mid" else "late", if (session.armor10.isEmpty()) "中期起源礼盒" else "末期起源礼盒")
            }
            3 -> {
                items += many(Material.OAK_LOG, 10)
                items += many(Material.COBBLESTONE, 40)
                items += fixed(Material.STONE_AXE, 1)
                items += fixed(Material.STONE_PICKAXE, 1)
                items += fixed(Material.IRON_CHESTPLATE, 1)
                items += fixed(Material.IRON_HELMET, 1)
                items += fixed(Material.IRON_SWORD, 1)
                items += many(Material.BREAD, 20)
                items += fixed(Material.SHIELD, 1)
                items += gift("nether", "下界礼盒")
            }
            4 -> {
                items += many(Material.OAK_LOG, 16)
                items += many(Material.COBBLESTONE, 64)
                items += fixed(Material.IRON_AXE, 1)
                items += fixed(Material.IRON_PICKAXE, 1)
                items += fixed(Material.IRON_SWORD, 1)
                items += many(Material.BREAD, 10)
                items += many(Material.COOKED_BEEF, 5)
                items += many(Material.WATER_BUCKET, 1)
                items += many(Material.LAVA_BUCKET, 1)
                items += fixed(Material.SHIELD, 1)
                items += gift("present", "中期礼盒")
                if (session.nether) items += gift("nether", "下界礼盒")
            }
        }
        if (level >= 2 && session.armor10.isNotEmpty()) {
            val people = session.armor10.size
            items += many(Material.IRON_INGOT, 3 * people)
            items += many(Material.BREAD, 10 * people)
            items += many(Material.COOKED_BEEF, 3 * people)
            items += many(Material.OAK_LOG, 5 * people)
            if (level != 3) {
                ironOrder().take(people.coerceAtMost(4)).forEach { items += fixed(it, 1) }
                val diamonds = (session.armor18.size - 1).coerceAtLeast(0).coerceAtMost(4)
                diamondOrder().take(diamonds).forEach { items += fixed(it, 1) }
            }
        }
        if (session.heavyDream) {
            val have = items.count { it.type in diamondArmor }
            repeat((2 - have).coerceAtLeast(0)) { index ->
                items += fixed(diamondOrder()[index.coerceAtMost(diamondOrder().lastIndex)], 1)
            }
            if (items.none { it.type == Material.DIAMOND_SWORD }) items += fixed(Material.DIAMOND_SWORD, 1)
            items += many(Material.DIAMOND, 1)
        }
        if (session.protectionBlessing) bless(items)
        if ((session.nether || session.armor18.isNotEmpty()) && Random.nextInt(100) < 30) {
            items += gift("extra", "额外礼盒")
        }
        return items.filter { it.type != Material.AIR }
    }

    fun openGift(item: ItemStack): List<ItemStack> {
        val id = item.itemMeta?.persistentDataContainer?.get(giftKey, PersistentDataType.STRING) ?: return emptyList()
        val pool = when (id) {
            "origin" -> originPool()
            "mid" -> midPool()
            "late" -> latePool()
            "nether" -> netherPool()
            "present" -> giftPool()
            "extra" -> extraPool()
            else -> return emptyList()
        }
        return roll(pool)
    }

    private fun gift(id: String, title: String): ItemStack {
        val item = named(Material.CHEST, title, NamedTextColor.LIGHT_PURPLE)
        val meta = item.itemMeta
        meta.lore(listOf(text("右键开启", NamedTextColor.GRAY)))
        meta.persistentDataContainer.set(giftKey, PersistentDataType.STRING, id)
        item.itemMeta = meta
        return item
    }

    private fun bless(items: MutableList<ItemStack>) {
        val armors = items.filter { it.type.equipmentSlot.isArmor }
        if (armors.isNotEmpty()) {
            val piece = armors.random()
            piece.addUnsafeEnchantment(Enchantment.PROTECTION, 3)
            piece.addUnsafeEnchantment(Enchantment.BINDING_CURSE, 1)
        }
        val weapon = items.firstOrNull { it.type.name.endsWith("SWORD") } ?: items.firstOrNull { it.type.name.endsWith("_AXE") }
        weapon?.addUnsafeEnchantment(Enchantment.SHARPNESS, 2)
    }

    fun ritualAxe(): ItemStack {
        val item = ItemStack(Material.IRON_AXE)
        val meta = item.itemMeta
        meta.customName(text("祭祀伐木斧", NamedTextColor.AQUA))
        meta.addEnchant(Enchantment.LOOTING, 1, true)
        cooking()?.let { meta.addEnchant(it, 1, true) }
            ?: meta.lore(listOf(text("烹饪 I", NamedTextColor.GRAY)))
        val mods = ArrayListMultimap.create<Attribute, AttributeModifier>()
        mods.put(Attribute.ATTACK_DAMAGE, modifier("axe_damage", 8.0))
        mods.put(Attribute.ATTACK_SPEED, modifier("axe_speed", -3.1))
        meta.attributeModifiers = mods
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        item.itemMeta = meta
        item.setData(io.papermc.paper.datacomponent.DataComponentTypes.MAX_DAMAGE, 444)
        return fixed(item)
    }

    fun wardShield(): ItemStack {
        val item = ItemStack(Material.SHIELD)
        val meta = item.itemMeta
        meta.customName(text("牢盾", NamedTextColor.AQUA))
        meta.lore(listOf(text("耐久 824", NamedTextColor.WHITE), text("可附魔", NamedTextColor.WHITE)))
        item.itemMeta = meta
        item.setData(io.papermc.paper.datacomponent.DataComponentTypes.MAX_DAMAGE, 824)
        return fixed(item)
    }

    fun copperLegsBoots(): List<ItemStack> = listOf(ItemStack(Material.COPPER_LEGGINGS), ItemStack(Material.COPPER_BOOTS))
    fun copperHelmChest(): List<ItemStack> = listOf(ItemStack(Material.COPPER_HELMET), ItemStack(Material.COPPER_CHESTPLATE))

    fun swiftCopperLegs(): ItemStack {
        val item = ItemStack(Material.COPPER_LEGGINGS)
        item.addUnsafeEnchantment(Enchantment.SWIFT_SNEAK, 2)
        return item
    }

    fun soulBoots(material: Material): ItemStack {
        val item = ItemStack(material)
        item.addUnsafeEnchantment(Enchantment.SOUL_SPEED, 2)
        return item
    }

    fun structureCompass(goal: shina.manhunt.game.CompassGoal, title: String): ItemStack {
        val item = named(Material.COMPASS, title, NamedTextColor.GREEN)
        val meta = item.itemMeta as org.bukkit.inventory.meta.CompassMeta
        val world = org.bukkit.Bukkit.getWorld(goal.world)
        if (world != null) {
            meta.lodestone = org.bukkit.Location(world, goal.x.toDouble(), goal.y.toDouble(), goal.z.toDouble())
            meta.isLodestoneTracked = false
        }
        item.itemMeta = meta
        return item
    }

    private fun originPool() = listOf(
        entry(5) { listOf(stack(Material.IRON_INGOT, 1)) },
        entry(10) { listOf(stack(Material.COPPER_INGOT, Random.nextInt(4, 7))) },
        entry(10) { listOf(stack(Material.BREAD, 4)) },
        entry(10) { listOf(stack(Material.OAK_LOG, 4)) },
        entry(10) { listOf(stack(Material.COAL, 7)) },
        entry(10) { listOf(stack(Material.RAW_IRON, 1)) },
        entry(10) { listOf(stack(Material.APPLE, 2)) },
        entry(10) { listOf(stack(Material.STRING, 3)) },
        entry(10) { listOf(stack(Material.COOKED_PORKCHOP, 1)) }
    )

    private fun midPool() = originPool() + listOf(
        entry(3) { listOf(stack(Material.WATER_BUCKET, 1)) },
        entry(10) { copperLegsBoots() },
        entry(3) { listOf(ItemStack(Material.IRON_PICKAXE)) },
        entry(10) { listOf(stack(Material.RAW_IRON, 5)) },
        entry(10) { listOf(stack(Material.STRING, 6)) }
    )

    private fun latePool() = midPool() + listOf(
        entry(1) { listOf(stack(Material.LAVA_BUCKET, 1)) },
        entry(10) { copperLegsBoots() },
        entry(3) { listOf(stack(Material.DIAMOND, 2)) },
        entry(10) { listOf(stack(Material.IRON_INGOT, 5)) },
        entry(10) { listOf(stack(Material.STRING, 8)) },
        entry(10) { listOf(stack(Material.TNT, 1)) },
        entry(10) { listOf(stack(Material.FLINT_AND_STEEL, 1)) },
        entry(10) { listOf(stack(Material.COOKED_BEEF, 3)) },
        entry(10) { listOf(stack(Material.COBWEB, 2)) },
        entry(1) { listOf(ItemStack(Material.CROSSBOW)) },
        entry(3) { listOf(stack(Material.ENDER_PEARL, 1)) }
    )

    private fun netherPool() = listOf(
        entry(10) { listOf(stack(Material.LAVA_BUCKET, 1)) },
        entry(10) { listOf(stack(Material.GOLD_INGOT, 10)) },
        entry(10) { listOf(soulBoots(Material.GOLDEN_BOOTS)) },
        entry(10) { listOf(soulBoots(Material.IRON_BOOTS)) },
        entry(3) { listOf(stack(Material.DIAMOND, 2)) },
        entry(10) { listOf(stack(Material.ENDER_PEARL, 1)) },
        entry(10) { listOf(named(Material.WARPED_FUNGUS, "诡异菌柄", NamedTextColor.WHITE).apply { amount = 16 }) },
        entry(10) { listOf(stack(Material.GOLDEN_APPLE, 1)) },
        entry(10) { listOf(stack(Material.COOKED_PORKCHOP, 3)) },
        entry(10) { listOf(stack(Material.COBWEB, 4)) },
        entry(10) { listOf(ItemStack(Material.WARPED_FUNGUS_ON_A_STICK)) },
        entry(10) { listOf(ItemStack(Material.FISHING_ROD)) },
        entry(10) { listOf(ItemStack(Material.IRON_PICKAXE)) },
        entry(10) { listOf(wardShield()) },
        entry(10) { listOf(ritualAxe()) }
    )

    private fun giftPool() = listOf(
        entry(3) { listOf(stack(Material.DIAMOND, 2)) },
        entry(10) { listOf(ItemStack(ironOrder().random()), stack(Material.IRON_INGOT, 3)) },
        entry(10) { listOf(stack(Material.COBWEB, 8)) },
        entry(10) { listOf(stack(Material.WIND_CHARGE, 3)) },
        entry(10) { listOf(ItemStack(Material.CROSSBOW), stack(Material.ARROW, 5)) },
        entry(10) { listOf(stack(Material.TNT, 1), ItemStack(Material.FLINT_AND_STEEL)) },
        entry(10) { listOf(book(Enchantment.SHARPNESS, 1)) },
        entry(10) { listOf(stack(Material.ENDER_PEARL, 1)) },
        entry(10) { listOf(ItemStack(Material.SADDLE), ItemStack(Material.IRON_HORSE_ARMOR)) },
        entry(10) { listOf(stack(Material.STRING, 10)) + many(Material.WATER_BUCKET, 3) },
        entry(10) { listOf(stack(Material.EXPERIENCE_BOTTLE, 16), stack(Material.BOOK, 3)) }
    )

    private fun extraPool() = listOf(
        entry(10) { listOf(simpleSpear()) },
        entry(10) { listOf(stack(Material.COBWEB, 12)) + many(Material.WATER_BUCKET, 2) },
        entry(10) { listOf(stack(Material.GOLDEN_APPLE, 1)) },
        entry(10) { listOf(stack(Material.WIND_CHARGE, 4)) },
        entry(10) { listOf(shipBreaker()) },
        entry(10) { listOf(randomBook(15)) },
        entry(10) { listOf(ItemStack(Material.FISHING_ROD)) },
        entry(10) { listOf(snack(10)) },
        entry(10) { listOf(voidShovel()) }
    )

    private fun simpleSpear(): ItemStack {
        val item = ItemStack(Material.IRON_SPEAR)
        item.setData(DataComponentTypes.ITEM_NAME, text("简易长矛", NamedTextColor.AQUA))
        item.setData(DataComponentTypes.LORE, ItemLore.lore(listOf(
            text("耐久上限为5", NamedTextColor.WHITE),
            text("*对气球造成十倍伤害", NamedTextColor.GRAY)
        )))
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(1f).build())
        item.setData(DataComponentTypes.MAX_DAMAGE, 1)
        item.setData(DataComponentTypes.REPAIR_COST, 99)
        item.addUnsafeEnchantment(Enchantment.LUNGE, 2)
        return item
    }

    private fun shipBreaker(): ItemStack {
        val item = ItemStack(Material.WOODEN_SPEAR)
        item.setData(DataComponentTypes.CUSTOM_NAME, text("破舰者", NamedTextColor.AQUA))
        item.setData(DataComponentTypes.LORE, ItemLore.lore(listOf(
            text("攻击命中敌人盾牌后，使其禁用120秒。", NamedTextColor.WHITE),
            text("-需要蓄力满才可以进行攻击", NamedTextColor.WHITE),
            text("-只能使用两次", NamedTextColor.WHITE),
            text("*你不会永远地安全，不会永远的逃脱", NamedTextColor.GRAY),
            text("", NamedTextColor.DARK_GREEN),
            text("在主手时：", NamedTextColor.GRAY),
            text(" 1 攻击伤害", NamedTextColor.DARK_GREEN),
            text(" 2 攻击速度", NamedTextColor.DARK_GREEN)
        )))
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(1f).build())
        item.setData(DataComponentTypes.ITEM_MODEL, Key.key("minecraft:wooden_axe"))
        item.setData(DataComponentTypes.MAX_DAMAGE, 1)
        item.setData(DataComponentTypes.REPAIR_COST, 99)
        item.setData(DataComponentTypes.WEAPON, Weapon.weapon().disableBlockingForSeconds(10f).itemDamagePerAttack(1).build())
        item.setData(DataComponentTypes.KINETIC_WEAPON, KineticWeapon.kineticWeapon().delayTicks(9999999).build())
        item.setData(
            DataComponentTypes.ATTACK_RANGE,
            AttackRange.attackRange().maxReach(3f).minReach(0f).mobFactor(0f).build()
        )
        item.setData(
            DataComponentTypes.ATTRIBUTE_MODIFIERS,
            ItemAttributeModifiers.itemAttributes()
                .addModifier(Attribute.ATTACK_SPEED, modifier("shipbreaker_speed", -2.0))
                .build()
        )
        item.setData(
            DataComponentTypes.TOOLTIP_DISPLAY,
            TooltipDisplay.tooltipDisplay().addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS).build()
        )
        return item
    }

    private fun snack(amount: Int): ItemStack {
        val item = ItemStack(Material.GOLD_NUGGET, amount)
        item.setData(DataComponentTypes.ITEM_NAME, text("小零食", NamedTextColor.LIGHT_PURPLE))
        item.setData(DataComponentTypes.LORE, ItemLore.lore(listOf(
            text("10饱食 12饱和", NamedTextColor.YELLOW),
            text("你肯定没吃过！穷鬼！", NamedTextColor.GRAY),
            text("（愤怒的呐喊）", NamedTextColor.GRAY),
            text("穷光蛋你连小零食都吃不起啊你", NamedTextColor.GRAY),
            text("大穷光蛋一个!", NamedTextColor.GRAY),
            text("你太穷了（拖长音）", NamedTextColor.GRAY),
            text("你实在是太穷了！！", NamedTextColor.GRAY),
            text("穷光蛋你连小零食都吃不起啊你", NamedTextColor.GRAY),
            text("(疯狂的颤抖)", NamedTextColor.GRAY),
            text("食用时间减少0.2秒", NamedTextColor.GRAY)
        )))
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(2f).build())
        item.setData(DataComponentTypes.FOOD, FoodProperties.food().nutrition(10).saturation(12f).canAlwaysEat(false).build())
        item.setData(
            DataComponentTypes.CONSUMABLE,
            Consumable.consumable().consumeSeconds(1.4f).animation(ItemUseAnimation.EAT).build()
        )
        item.setData(DataComponentTypes.MAX_STACK_SIZE, 64)
        return item
    }

    private fun voidShovel(): ItemStack {
        val item = ItemStack(Material.NETHERITE_SHOVEL)
        item.setData(DataComponentTypes.CUSTOM_NAME, text("太虚量天", NamedTextColor.LIGHT_PURPLE))
        item.setData(DataComponentTypes.LORE, ItemLore.lore(listOf(
            text("*纳星宿为柄，丈九霄云厚", NamedTextColor.GRAY),
            text("", NamedTextColor.DARK_GREEN),
            text("在主手时：", NamedTextColor.GRAY),
            text(" 8 攻击伤害", NamedTextColor.DARK_GREEN),
            text(" 1 攻击速度", NamedTextColor.DARK_GREEN)
        )))
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(1f).build())
        item.setData(DataComponentTypes.MAX_DAMAGE, 3)
        item.setData(
            DataComponentTypes.ATTRIBUTE_MODIFIERS,
            ItemAttributeModifiers.itemAttributes()
                .addModifier(Attribute.ATTACK_SPEED, modifier("void_shovel_speed", -3.0))
                .addModifier(Attribute.ATTACK_DAMAGE, modifier("void_shovel_damage", 7.0))
                .build()
        )
        item.setData(
            DataComponentTypes.TOOLTIP_DISPLAY,
            TooltipDisplay.tooltipDisplay().addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS).build()
        )
        item.addUnsafeEnchantment(Enchantment.EFFICIENCY, 10)
        item.addUnsafeEnchantment(Enchantment.FORTUNE, 3)
        item.addUnsafeEnchantment(Enchantment.KNOCKBACK, 3)
        return item
    }

    private fun randomBook(level: Int): ItemStack {
        val choices = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
            .filter { !it.isCursed && it.key != NamespacedKey.minecraft("lunge") }
        return book(choices.random(), level)
    }

    private fun roll(pool: List<Pair<Int, () -> List<ItemStack>>>): List<ItemStack> {
        val total = pool.sumOf { it.first }
        var roll = Random.nextInt(total)
        for ((weight, give) in pool) {
            roll -= weight
            if (roll < 0) return give()
        }
        return pool.last().second()
    }

    private fun entry(weight: Int, give: () -> List<ItemStack>) = weight to give

    private fun ironOrder() = listOf(Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_HELMET, Material.IRON_BOOTS)
    private fun diamondOrder() = listOf(Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_HELMET, Material.DIAMOND_BOOTS)
    private val diamondArmor = diamondOrder().toSet()

    private fun fixed(material: Material, amount: Int): ItemStack = fixed(stack(material, amount))
    private fun fixed(item: ItemStack): ItemStack {
        val meta = item.itemMeta
        meta.addEnchant(Enchantment.VANISHING_CURSE, 1, true)
        meta.persistentDataContainer.set(kitKey, PersistentDataType.BYTE, 1)
        item.itemMeta = meta
        return item
    }

    private fun mark(item: ItemStack, key: NamespacedKey) {
        val meta = item.itemMeta
        meta.persistentDataContainer.set(key, PersistentDataType.BYTE, 1)
        item.itemMeta = meta
    }

    private fun stack(material: Material, amount: Int): ItemStack =
        ItemStack(material, amount.coerceIn(1, material.maxStackSize.coerceAtLeast(1)))

    private fun many(material: Material, amount: Int): List<ItemStack> {
        val result = mutableListOf<ItemStack>()
        var left = amount
        val max = material.maxStackSize.coerceAtLeast(1)
        while (left > 0) {
            val size = left.coerceAtMost(max)
            result += ItemStack(material, size)
            left -= size
        }
        return result
    }

    private fun damageLeft(material: Material, uses: Int): ItemStack {
        val item = ItemStack(material)
        val max = material.maxDurability.toInt()
        if (max > 0) {
            val meta = item.itemMeta as Damageable
            meta.damage = (max - uses).coerceAtLeast(0)
            item.itemMeta = meta
        }
        return item
    }

    private fun book(enchantment: Enchantment, level: Int): ItemStack {
        val item = ItemStack(Material.ENCHANTED_BOOK)
        val meta = item.itemMeta as org.bukkit.inventory.meta.EnchantmentStorageMeta
        meta.addStoredEnchant(enchantment, level, true)
        item.itemMeta = meta
        return item
    }

    private fun named(material: Material, name: String, color: NamedTextColor): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta.customName(text(name, color))
        item.itemMeta = meta
        return item
    }

    private fun text(value: String, color: NamedTextColor): Component =
        Component.text(value).color(color).decoration(TextDecoration.ITALIC, false)

    private fun cooking(): Enchantment? =
        RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(NamespacedKey("manhunt", "cooking"))

    private fun modifier(name: String, amount: Double): AttributeModifier =
        AttributeModifier(NamespacedKey(Manhunt.instance, name), amount, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND)
}
