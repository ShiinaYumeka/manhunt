package shina.manhunt.game

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import shina.manhunt.Manhunt

class SettingsHolder : InventoryHolder {
    private lateinit var backing: Inventory
    fun bind(inventory: Inventory) {
        backing = inventory
    }
    override fun getInventory(): Inventory = backing
}

object SettingsMenu : Listener {
    private const val RESPAWN = 10
    private const val RESERVE = 12
    private const val CHEST = 14
    private const val MEDICINE = 16
    private val rules = mapOf(
        19 to Rule.HUNT,
        21 to Rule.COMBAT,
        23 to Rule.FIRE,
        25 to Rule.LOCK
    )
    private var seconds = 0

    fun tick() {
        val session = Manhunt.instance.session
        if (session.phase != Phase.LOBBY) return
        seconds++
        if (seconds % 60 == 10) promptAll()
    }

    fun prompt(player: Player) {
        player.sendMessage(Component.text("准备阶段可以查看本局设置。 ")
            .append(Component.text("[打开设置]").color(NamedTextColor.YELLOW).clickEvent(ClickEvent.runCommand("/manhunt settings"))))
    }

    fun promptAll() {
        Bukkit.getOnlinePlayers().forEach { prompt(it) }
    }

    fun open(player: Player) {
        val inventory = Bukkit.createInventory(SettingsHolder(), 27, Component.text("本局设置"))
        (inventory.holder as SettingsHolder).bind(inventory)
        paint(inventory, player)
        player.openInventory(inventory)
    }

    fun closeAll() {
        for (player in Bukkit.getOnlinePlayers()) {
            if (player.openInventory.topInventory.holder is SettingsHolder) player.closeInventory()
        }
    }

    private fun paint(inventory: Inventory, viewer: Player) {
        val session = Manhunt.instance.session
        val op = viewer.isOp
        for (slot in 0 until inventory.size) inventory.setItem(slot, pane())
        inventory.setItem(4, button(Material.BOOK, "本局设置", NamedTextColor.GOLD, false, session.matchSettings() + listOf(
            if (op) "下面的项目可以修改。" else "只有管理员可以修改。"
        )))
        inventory.setItem(RESPAWN, button(Material.CLOCK, "猎人复活 ${session.respawnSeconds} 秒", NamedTextColor.GOLD, session.respawnLocked, listOf(
            if (session.respawnLocked) "已手动固定" else "会随人数自动调整",
            adjustHint(op, "左键 +5 秒，右键 -5 秒", "Shift 左键 +1 秒，Shift 右键 -1 秒")
        )))
        inventory.setItem(RESERVE, button(Material.GOLDEN_APPLE, "备用血量 ${session.reserveMax}", NamedTextColor.GOLD, session.reserveLocked, listOf(
            if (session.reserveLocked) "已手动固定" else "会随人数自动调整",
            adjustHint(op, "左键 +30，右键 -30", "Shift 左键 +10，Shift 右键 -10")
        )))
        inventory.setItem(CHEST, button(Material.CHEST, "逃生仓库${state(session.runnerChestEnabled)}", stateColor(session.runnerChestEnabled), session.runnerChestEnabled, listOf(
            "关闭后不再发放逃生仓库",
            "关闭时金疮药一并关闭",
            toggleHint(op)
        )))
        inventory.setItem(MEDICINE, button(Material.HONEY_BOTTLE, "金疮药${state(session.medicineEnabled)}", stateColor(session.medicineEnabled), session.medicineEnabled, listOf(
            if (session.medicineEnabled) "备用血基准 300" else "备用血基准 400",
            "关闭时，发药的进度改为每人每瓶备用血 +25",
            toggleHint(op)
        )))
        for ((slot, rule) in rules) {
            val on = session.enabled(rule)
            inventory.setItem(slot, button(icon(rule), "${rule.label}${state(on)}", stateColor(on), on, listOf(toggleHint(op))))
        }
    }

    private fun adjustHint(op: Boolean, coarse: String, fine: String): String = if (op) "$coarse；$fine" else "只有管理员可以修改。"

    private fun toggleHint(op: Boolean): String = if (op) "点击切换" else "只有管理员可以修改。"

    private fun state(on: Boolean) = if (on) "开启" else "关闭"

    private fun stateColor(on: Boolean) = if (on) NamedTextColor.GREEN else NamedTextColor.RED

    private fun icon(rule: Rule): Material = when (rule) {
        Rule.HUNT -> Material.COMPASS
        Rule.COMBAT -> Material.IRON_SWORD
        Rule.FIRE -> Material.FIRE_CHARGE
        Rule.LOCK -> Material.CRYING_OBSIDIAN
    }

    private fun pane(): ItemStack = button(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.DARK_GRAY, false, emptyList())

    private fun button(material: Material, name: String, color: NamedTextColor, glint: Boolean, lore: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta.customName(Component.text(name).color(color).decoration(TextDecoration.ITALIC, false))
        meta.lore(lore.map { Component.text(it).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false) })
        meta.setEnchantmentGlintOverride(glint)
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ENCHANTS)
        item.itemMeta = meta
        return item
    }

    private fun refreshOpen() {
        for (player in Bukkit.getOnlinePlayers()) {
            val top = player.openInventory.topInventory
            if (top.holder is SettingsHolder) paint(top, player)
        }
    }

    private fun publishBalance() {
        val text = Manhunt.instance.session.adaptBalance(false) ?: return
        Bukkit.broadcast(Component.text(text).color(NamedTextColor.YELLOW))
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.view.topInventory.holder !is SettingsHolder) return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val session = Manhunt.instance.session
        if (session.phase != Phase.LOBBY) {
            player.closeInventory()
            return
        }
        val slot = event.rawSlot
        if (slot != RESPAWN && slot != RESERVE && slot != CHEST && slot != MEDICINE && slot !in rules) return
        if (!player.isOp) {
            player.sendMessage(Component.text("只有管理员可以修改设置。").color(NamedTextColor.RED))
            return
        }
        when (slot) {
            RESPAWN -> adjust(session.respawnSeconds, delta(event.click, 5, 1))?.let {
                session.respawnSeconds = it
                session.respawnLocked = true
                Bukkit.broadcast(Component.text("猎人复活时间设为 $it 秒。这项不再自动调整。").color(NamedTextColor.YELLOW))
            }
            RESERVE -> adjust(session.reserveMax, delta(event.click, 30, 10))?.let {
                session.reserveMax = it
                session.reserveLocked = true
                Bukkit.broadcast(Component.text("逃生备用血量设为 $it。这项不再自动调整。").color(NamedTextColor.YELLOW))
            }
            CHEST -> toggleChest(event.click)
            MEDICINE -> toggleMedicine(player, event.click)
            else -> rules[slot]?.let { toggleRule(event.click, it) }
        }
        refreshOpen()
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is SettingsHolder) event.isCancelled = true
    }

    private fun delta(click: ClickType, coarse: Int, fine: Int): Int? = when (click) {
        ClickType.LEFT -> coarse
        ClickType.RIGHT -> -coarse
        ClickType.SHIFT_LEFT -> fine
        ClickType.SHIFT_RIGHT -> -fine
        else -> null
    }

    private fun adjust(current: Int, amount: Int?): Int? {
        if (amount == null) return null
        val next = (current + amount).coerceAtLeast(1)
        return next.takeIf { it != current }
    }

    private fun toggleChest(click: ClickType) {
        if (!switching(click)) return
        val session = Manhunt.instance.session
        val enabled = !session.runnerChestEnabled
        val medicineWas = session.medicineEnabled
        session.runnerChestEnabled = enabled
        if (!enabled) session.medicineEnabled = false
        Bukkit.broadcast(Component.text("逃生仓库已${state(enabled)}。").color(NamedTextColor.YELLOW))
        if (!enabled && medicineWas) Bukkit.broadcast(Component.text("金疮药已关闭。").color(NamedTextColor.YELLOW))
        publishBalance()
    }

    private fun toggleMedicine(player: Player, click: ClickType) {
        if (!switching(click)) return
        val session = Manhunt.instance.session
        val enabled = !session.medicineEnabled
        if (enabled && !session.runnerChestEnabled) {
            player.sendMessage(Component.text("需要先开启逃生仓库。").color(NamedTextColor.RED))
            return
        }
        session.medicineEnabled = enabled
        Bukkit.broadcast(Component.text("金疮药已${state(enabled)}。").color(NamedTextColor.YELLOW))
        publishBalance()
    }

    private fun toggleRule(click: ClickType, rule: Rule) {
        if (!switching(click)) return
        val session = Manhunt.instance.session
        val enabled = !session.enabled(rule)
        session.rules[rule] = enabled
        Bukkit.broadcast(Component.text("${rule.label}已${state(enabled)}。").color(NamedTextColor.YELLOW))
    }

    private fun switching(click: ClickType): Boolean =
        click == ClickType.LEFT || click == ClickType.RIGHT || click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT
}
