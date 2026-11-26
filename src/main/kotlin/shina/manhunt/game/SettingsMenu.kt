package shina.manhunt.game

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.command.CommandSender
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
import shina.manhunt.kit.Gear
import java.time.Duration
import java.util.UUID

class SettingsHolder : InventoryHolder {
    private lateinit var backing: Inventory
    fun bind(inventory: Inventory) {
        backing = inventory
    }
    override fun getInventory(): Inventory = backing
}

object SettingsMenu : Listener {
    private const val SUMMARY = 4
    private const val JOIN_HUNTER = 10
    private const val JOIN_RUNNER = 12
    private const val JOIN_SPECTATOR = 14
    private const val LEAVE = 16
    private const val HUNTER_LIST = 19
    private const val READY = 21
    private const val RUNNER_LIST = 23
    private const val SPECTATOR_LIST = 25
    private const val RESPAWN = 28
    private const val RESERVE = 30
    private const val CHEST = 32
    private const val MEDICINE = 34
    private const val ACTION = 49
    private val rules = mapOf(
        37 to Rule.HUNT,
        39 to Rule.COMBAT,
        41 to Rule.FIRE,
        43 to Rule.LOCK
    )
    private var seconds = 0
    private var lastCountdownSecond: Int? = null
    private var resetArmed = false
    private val openedAt = mutableMapOf<UUID, Long>()
    private val noticedAt = mutableMapOf<UUID, Long>()

    fun tick() {
        val session = Manhunt.instance.session
        when (session.phase) {
            Phase.LOBBY -> {
                seconds++
                if (seconds % 60 == 0) promptAll()
                tickCountdown()
                syncStars()
            }
            Phase.ENDED -> syncStars()
            Phase.COUNTDOWN, Phase.PLAYING -> syncStars()
        }
    }

    fun prompt(player: Player) {
        noticedAt[player.uniqueId] = System.currentTimeMillis()
        player.sendMessage(Component.text("准备阶段可以查看本局设置。 ")
            .append(Component.text("[打开设置]").color(NamedTextColor.YELLOW).clickEvent(ClickEvent.runCommand("/manhunt settings"))))
    }

    fun promptAll() {
        val now = System.currentTimeMillis()
        for (player in Bukkit.getOnlinePlayers()) {
            if (player.openInventory.topInventory.holder is SettingsHolder) continue
            val recent = maxOf(openedAt[player.uniqueId] ?: 0L, noticedAt[player.uniqueId] ?: 0L)
            if (now - recent < 60_000) continue
            prompt(player)
        }
    }

    fun forget(id: UUID) {
        openedAt.remove(id)
        noticedAt.remove(id)
    }

    fun open(player: Player) {
        val session = Manhunt.instance.session
        if (session.phase != Phase.LOBBY && session.phase != Phase.ENDED) {
            player.sendMessage(Component.text("现在不能打开面板。").color(NamedTextColor.RED))
            return
        }
        openedAt[player.uniqueId] = System.currentTimeMillis()
        val title = if (session.phase == Phase.ENDED) "结算面板" else "准备面板"
        val inventory = Bukkit.createInventory(SettingsHolder(), 54, Component.text(title))
        (inventory.holder as SettingsHolder).bind(inventory)
        paint(inventory, player)
        player.openInventory(inventory)
    }

    fun closeAll() {
        for (player in Bukkit.getOnlinePlayers()) {
            if (player.openInventory.topInventory.holder is SettingsHolder) player.closeInventory()
        }
    }

    fun abandonCountdown() {
        val session = Manhunt.instance.session
        val active = session.readyCountdownUntil != null || lastCountdownSecond != null
        session.readyCountdownUntil = null
        lastCountdownSecond = null
        if (!active) return
        for (player in Bukkit.getOnlinePlayers()) player.clearTitle()
    }

    fun refreshOpen() {
        for (player in Bukkit.getOnlinePlayers()) {
            val top = player.openInventory.topInventory
            if (top.holder is SettingsHolder) paint(top, player)
        }
    }

    fun afterRosterChange() {
        val session = Manhunt.instance.session
        if (session.phase != Phase.LOBBY) {
            refreshOpen()
            return
        }
        session.ready.removeIf { id ->
            val role = session.role(id)
            role != Role.HUNTER && role != Role.RUNNER
        }
        if (session.readyCountdownUntil != null && !readyToStart()) {
            stopCountdown("准备人数变化，倒计时已中断。")
            return
        }
        if (session.readyCountdownUntil == null && readyToStart()) {
            beginCountdown()
            return
        }
        refreshOpen()
    }

    fun afterPlayerLeft() {
        val session = Manhunt.instance.session
        if (session.phase != Phase.ENDED) return
        val online = Bukkit.getOnlinePlayers()
        if (online.all { it.uniqueId in session.resetAgreed }) requestAgreedReset()
        else refreshOpen()
    }

    fun syncStars() {
        val session = Manhunt.instance.session
        val allow = session.phase == Phase.LOBBY || session.phase == Phase.ENDED
        for (player in Bukkit.getOnlinePlayers()) {
            if (allow) giveStar(player) else stripStar(player)
        }
    }

    fun syncStar(player: Player) {
        val session = Manhunt.instance.session
        if (session.phase == Phase.LOBBY || session.phase == Phase.ENDED) giveStar(player) else stripStar(player)
    }

    fun commitRespawn(seconds: Int) {
        val session = Manhunt.instance.session
        if (session.phase != Phase.LOBBY) return
        session.respawnSeconds = seconds
        session.respawnLocked = true
        Bukkit.broadcast(Component.text("猎人复活时间设为 $seconds 秒。这项不再自动调整。").color(NamedTextColor.YELLOW))
        refreshOpen()
    }

    fun commitReserve(amount: Int) {
        val session = Manhunt.instance.session
        if (session.phase != Phase.LOBBY) return
        session.reserveMax = amount
        session.reserveLocked = true
        Bukkit.broadcast(Component.text("逃生备用血量设为 $amount。这项不再自动调整。").color(NamedTextColor.YELLOW))
        refreshOpen()
    }

    fun commitChest(enabled: Boolean) {
        val session = Manhunt.instance.session
        if (session.phase != Phase.LOBBY) return
        val medicineWas = session.medicineEnabled
        session.runnerChestEnabled = enabled
        if (!enabled) session.medicineEnabled = false
        Bukkit.broadcast(Component.text("逃生仓库已${state(enabled)}。").color(NamedTextColor.YELLOW))
        if (!enabled && medicineWas) Bukkit.broadcast(Component.text("金疮药已关闭。").color(NamedTextColor.YELLOW))
        publishBalance()
        refreshOpen()
    }

    fun commitMedicine(actor: CommandSender, enabled: Boolean) {
        val session = Manhunt.instance.session
        if (session.phase != Phase.LOBBY) return
        if (enabled && !session.runnerChestEnabled) {
            actor.sendMessage(Component.text("需要先开启逃生仓库。").color(NamedTextColor.RED))
            return
        }
        session.medicineEnabled = enabled
        Bukkit.broadcast(Component.text("金疮药已${state(enabled)}。").color(NamedTextColor.YELLOW))
        publishBalance()
        refreshOpen()
    }

    fun commitRule(rule: Rule, enabled: Boolean) {
        val session = Manhunt.instance.session
        session.rules[rule] = enabled
        if (!enabled && rule == Rule.HUNT) session.huntCountdownEnd.clear()
        if (!enabled && rule == Rule.COMBAT) session.combatCountdownEnd = null
        Bukkit.broadcast(Component.text("${rule.label}已${state(enabled)}。").color(NamedTextColor.YELLOW))
        refreshOpen()
    }

    fun forceStart(sender: CommandSender) {
        if (sender is Player && !sender.isOp) {
            sender.sendMessage(Component.text("只有管理员可以强制开始。").color(NamedTextColor.RED))
            return
        }
        val session = Manhunt.instance.session
        if (session.phase != Phase.LOBBY) {
            sender.sendMessage(Component.text("游戏已经开始。").color(NamedTextColor.RED))
            return
        }
        if (!bothSides()) {
            sender.sendMessage(Component.text("至少需要一名猎人和一名逃生。").color(NamedTextColor.RED))
            return
        }
        abandonCountdown()
        Match.start(Manhunt.instance)
    }

    fun requestAgreedReset() {
        if (resetArmed) return
        val session = Manhunt.instance.session
        if (session.phase != Phase.ENDED) return
        resetArmed = true
        MapReset.request(Manhunt.instance, Bukkit.getConsoleSender())
    }

    fun endSession() {
        val session = Manhunt.instance.session
        session.resetAgreed.clear()
        session.ready.clear()
        abandonCountdown()
        closeAll()
        syncStars()
        if (Bukkit.getOnlinePlayers().isEmpty()) requestAgreedReset()
    }

    private fun paint(inventory: Inventory, viewer: Player) {
        val session = Manhunt.instance.session
        val op = viewer.isOp
        val lobby = session.phase == Phase.LOBBY
        for (slot in 0 until inventory.size) inventory.setItem(slot, pane())
        inventory.setItem(SUMMARY, summary(op))
        inventory.setItem(JOIN_HUNTER, joinButton(Role.HUNTER, viewer))
        inventory.setItem(JOIN_RUNNER, joinButton(Role.RUNNER, viewer))
        inventory.setItem(JOIN_SPECTATOR, joinButton(Role.SPECTATOR, viewer))
        inventory.setItem(LEAVE, leaveButton(viewer))
        inventory.setItem(HUNTER_LIST, rosterButton(Role.HUNTER))
        inventory.setItem(READY, readyButton(viewer))
        inventory.setItem(RUNNER_LIST, rosterButton(Role.RUNNER))
        inventory.setItem(SPECTATOR_LIST, rosterButton(Role.SPECTATOR))
        inventory.setItem(RESPAWN, button(Material.CLOCK, "猎人复活 ${session.respawnSeconds} 秒", NamedTextColor.GOLD, session.respawnLocked, listOf(
            if (session.respawnLocked) "已手动固定" else "会随人数自动调整",
            adjustHint(op, lobby, "左键 +5 秒，右键 -5 秒", "Shift 左键 +1 秒，Shift 右键 -1 秒")
        )))
        inventory.setItem(RESERVE, button(Material.GOLDEN_APPLE, "备用血量 ${session.reserveMax}", NamedTextColor.GOLD, session.reserveLocked, listOf(
            if (session.reserveLocked) "已手动固定" else "会随人数自动调整",
            adjustHint(op, lobby, "左键 +30，右键 -30", "Shift 左键 +10，Shift 右键 -10")
        )))
        inventory.setItem(CHEST, button(Material.CHEST, "逃生仓库${state(session.runnerChestEnabled)}", stateColor(session.runnerChestEnabled), session.runnerChestEnabled, listOf(
            "关闭后不再发放逃生仓库",
            "关闭时金疮药一并关闭",
            toggleHint(op, lobby)
        )))
        inventory.setItem(MEDICINE, button(Material.HONEY_BOTTLE, "金疮药${state(session.medicineEnabled)}", stateColor(session.medicineEnabled), session.medicineEnabled, listOf(
            if (session.medicineEnabled) "备用血基准 300" else "备用血基准 400",
            "关闭时，发药的进度改为每人每瓶备用血 +25",
            toggleHint(op, lobby)
        )))
        for ((slot, rule) in rules) {
            val on = session.enabled(rule)
            inventory.setItem(slot, button(icon(rule), "${rule.label}${state(on)}", stateColor(on), on, listOf(toggleHint(op, lobby))))
        }
        inventory.setItem(ACTION, if (lobby) forceButton(op) else if (session.phase == Phase.ENDED) resetButton(viewer) else pane())
    }

    private fun summary(op: Boolean): ItemStack {
        val session = Manhunt.instance.session
        val lines = session.matchSettings().toMutableList()
        if (session.phase == Phase.LOBBY) {
            val participants = Bukkit.getOnlinePlayers().filter {
                val role = session.role(it.uniqueId)
                role == Role.HUNTER || role == Role.RUNNER
            }
            val ready = participants.count { it.uniqueId in session.ready }
            lines += "已准备 $ready/${participants.size}。旁观不需要准备。"
            val left = secondsLeft()
            if (left != null) lines += "准备倒计时还剩 $left 秒。"
            lines += if (op) "下面的项目可以修改。" else "只有管理员可以修改。"
        } else {
            lines += "对局已结束。设置不能再改。"
        }
        return button(Material.BOOK, "本局设置", NamedTextColor.GOLD, false, lines)
    }

    private fun joinButton(role: Role, viewer: Player): ItemStack {
        val session = Manhunt.instance.session
        val mine = session.role(viewer.uniqueId) == role
        val (material, name, color) = when (role) {
            Role.HUNTER -> Triple(Material.IRON_SWORD, "加入猎人", NamedTextColor.RED)
            Role.RUNNER -> Triple(Material.FEATHER, "加入逃生", NamedTextColor.GREEN)
            Role.SPECTATOR -> Triple(Material.ENDER_EYE, "加入旁观", NamedTextColor.AQUA)
        }
        val lines = mutableListOf<String>()
        if (mine) lines += "当前阵营"
        if (session.phase != Phase.LOBBY && role != Role.SPECTATOR) lines += "现在不能加入。"
        else lines += "点击加入。"
        return button(material, name, color, mine, lines)
    }

    private fun leaveButton(viewer: Player): ItemStack {
        val session = Manhunt.instance.session
        val lines = when {
            session.phase != Phase.LOBBY -> listOf("现在不能离队。")
            session.role(viewer.uniqueId) == null -> listOf("你还没有阵营。")
            else -> listOf("点击退出当前阵营。")
        }
        return button(Material.OAK_DOOR, "退出阵营", NamedTextColor.YELLOW, false, lines)
    }

    private fun rosterButton(role: Role): ItemStack {
        val (material, name, color) = when (role) {
            Role.HUNTER -> Triple(Material.RED_BANNER, "猎人", NamedTextColor.RED)
            Role.RUNNER -> Triple(Material.GREEN_BANNER, "逃生", NamedTextColor.GREEN)
            Role.SPECTATOR -> Triple(Material.LIGHT_GRAY_BANNER, "旁观", NamedTextColor.GRAY)
        }
        return button(material, name, color, false, roster(role))
    }

    private fun roster(role: Role): List<String> {
        val session = Manhunt.instance.session
        val rows = session.roles.filterValues { it == role }.map { (id, _) ->
            val online = Bukkit.getPlayer(id)
            val name = online?.name ?: Bukkit.getOfflinePlayer(id).name ?: "未知"
            val mark = when {
                online == null -> "（离线）"
                role == Role.SPECTATOR -> ""
                id in session.ready -> " 已准备"
                else -> " 未准备"
            }
            name to "$name$mark"
        }.sortedBy { it.first }
        return rows.map { it.second }.ifEmpty { listOf("没有人") }
    }

    private fun readyButton(viewer: Player): ItemStack {
        val session = Manhunt.instance.session
        val role = session.role(viewer.uniqueId)
        val ready = viewer.uniqueId in session.ready
        val left = secondsLeft()
        val name = when {
            session.phase != Phase.LOBBY -> "准备"
            left != null -> "取消准备（$left 秒）"
            ready -> "取消准备"
            else -> "准备"
        }
        val lines = when {
            session.phase != Phase.LOBBY -> listOf("现在不能准备。")
            role != Role.HUNTER && role != Role.RUNNER -> listOf(if (role == Role.SPECTATOR) "旁观不需要准备。" else "先加入猎人或逃生。")
            left != null -> listOf("倒计时还剩 $left 秒。", "点击取消准备，所有人需要重新准备。")
            ready -> listOf("点击取消准备。")
            else -> listOf("点击准备。", "猎人和逃生都准备后，进入 10 秒倒计时。")
        }
        val color = if (ready || left != null) NamedTextColor.GREEN else NamedTextColor.GRAY
        return button(if (ready || left != null) Material.LIME_DYE else Material.GRAY_DYE, name, color, ready, lines)
    }

    private fun forceButton(op: Boolean): ItemStack {
        val lines = if (op) listOf("点击强制开始。", "不等待其他人准备。") else listOf("只有管理员可以强制开始。")
        return button(Material.CLOCK, "强制开始", NamedTextColor.GOLD, false, lines)
    }

    private fun resetButton(viewer: Player): ItemStack {
        val session = Manhunt.instance.session
        val online = Bukkit.getOnlinePlayers()
        val agreed = online.count { it.uniqueId in session.resetAgreed }
        val mine = viewer.uniqueId in session.resetAgreed
        return button(Material.TNT, "重置地图", NamedTextColor.RED, mine, listOf(
            "需要所有在线玩家同意。",
            "已同意 $agreed/${online.size}。",
            if (mine) "你已经同意。" else "点击同意重置地图。"
        ))
    }

    private fun adjustHint(op: Boolean, lobby: Boolean, coarse: String, fine: String): String {
        if (!lobby) return "现在不能修改。"
        return if (op) "$coarse；$fine" else "只有管理员可以修改。"
    }

    private fun toggleHint(op: Boolean, lobby: Boolean): String {
        if (!lobby) return "现在不能修改。"
        return if (op) "点击切换" else "只有管理员可以修改。"
    }

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

    private fun publishBalance() {
        val text = Manhunt.instance.session.adaptBalance(false) ?: return
        Bukkit.broadcast(Component.text(text).color(NamedTextColor.YELLOW))
    }

    private fun toggleReady(player: Player) {
        val session = Manhunt.instance.session
        if (session.phase != Phase.LOBBY) {
            player.sendMessage(Component.text("现在不能准备。").color(NamedTextColor.RED))
            return
        }
        val role = session.role(player.uniqueId)
        if (role != Role.HUNTER && role != Role.RUNNER) {
            val text = if (role == Role.SPECTATOR) "旁观不需要准备。" else "先加入猎人或逃生。"
            player.sendMessage(Component.text(text).color(NamedTextColor.RED))
            return
        }
        if (player.uniqueId in session.ready) {
            if (session.readyCountdownUntil != null) {
                session.ready.clear()
                stopCountdown("有人取消准备，倒计时已中断。所有人需要重新准备。")
            } else {
                session.ready.remove(player.uniqueId)
                player.sendMessage(Component.text("你取消了准备。").color(NamedTextColor.YELLOW))
                refreshOpen()
            }
            return
        }
        session.ready.add(player.uniqueId)
        player.sendMessage(Component.text("你已准备。").color(NamedTextColor.GREEN))
        if (session.readyCountdownUntil == null && readyToStart()) beginCountdown()
        else refreshOpen()
    }

    private fun agreeReset(player: Player) {
        val session = Manhunt.instance.session
        if (session.phase != Phase.ENDED) return
        if (!session.resetAgreed.add(player.uniqueId)) {
            player.sendMessage(Component.text("你已经同意重置地图。").color(NamedTextColor.YELLOW))
            refreshOpen()
            return
        }
        val online = Bukkit.getOnlinePlayers()
        val agreed = online.count { it.uniqueId in session.resetAgreed }
        Bukkit.broadcast(Component.text("${player.name} 同意重置地图（$agreed/${online.size}）。").color(NamedTextColor.GOLD))
        if (online.all { it.uniqueId in session.resetAgreed }) requestAgreedReset()
        else refreshOpen()
    }

    private fun editSetting(player: Player, slot: Int, click: ClickType) {
        val session = Manhunt.instance.session
        if (session.phase != Phase.LOBBY) {
            player.sendMessage(Component.text("现在不能修改设置。").color(NamedTextColor.RED))
            return
        }
        if (!player.isOp) {
            player.sendMessage(Component.text("只有管理员可以修改设置。").color(NamedTextColor.RED))
            return
        }
        when (slot) {
            RESPAWN -> adjust(session.respawnSeconds, delta(click, 5, 1))?.let { commitRespawn(it) }
            RESERVE -> adjust(session.reserveMax, delta(click, 30, 10))?.let { commitReserve(it) }
            CHEST -> if (switching(click)) commitChest(!session.runnerChestEnabled)
            MEDICINE -> if (switching(click)) commitMedicine(player, !session.medicineEnabled)
        }
    }

    private fun editRule(player: Player, rule: Rule, click: ClickType) {
        if (!switching(click)) return
        val session = Manhunt.instance.session
        if (session.phase != Phase.LOBBY) {
            player.sendMessage(Component.text("现在不能修改设置。").color(NamedTextColor.RED))
            return
        }
        if (!player.isOp) {
            player.sendMessage(Component.text("只有管理员可以修改设置。").color(NamedTextColor.RED))
            return
        }
        commitRule(rule, !session.enabled(rule))
    }

    private fun readyToStart(): Boolean {
        val session = Manhunt.instance.session
        if (session.phase != Phase.LOBBY) return false
        val online = Bukkit.getOnlinePlayers()
        val hunters = online.filter { session.role(it.uniqueId) == Role.HUNTER }
        val runners = online.filter { session.role(it.uniqueId) == Role.RUNNER }
        if (hunters.isEmpty() || runners.isEmpty()) return false
        return (hunters + runners).all { it.uniqueId in session.ready }
    }

    private fun bothSides(): Boolean {
        val session = Manhunt.instance.session
        val online = Bukkit.getOnlinePlayers()
        return online.any { session.role(it.uniqueId) == Role.HUNTER } && online.any { session.role(it.uniqueId) == Role.RUNNER }
    }

    private fun beginCountdown() {
        val session = Manhunt.instance.session
        session.readyCountdownUntil = System.currentTimeMillis() + 10_000L
        lastCountdownSecond = 10
        Bukkit.broadcast(Component.text("全员已准备，10 秒后开始。取消准备会中断倒计时。").color(NamedTextColor.GOLD))
        showCountdown(10)
        refreshOpen()
    }

    private fun tickCountdown() {
        val session = Manhunt.instance.session
        val until = session.readyCountdownUntil ?: return
        if (!readyToStart()) {
            stopCountdown("准备人数变化，倒计时已中断。")
            return
        }
        val left = ((until - System.currentTimeMillis() + 999) / 1000).toInt()
        if (left <= 0) {
            abandonCountdown()
            Match.start(Manhunt.instance)
            if (Manhunt.instance.session.phase == Phase.LOBBY && readyToStart()) beginCountdown()
            return
        }
        if (left != lastCountdownSecond) {
            lastCountdownSecond = left
            showCountdown(left)
            refreshOpen()
        }
    }

    private fun stopCountdown(message: String) {
        val session = Manhunt.instance.session
        session.readyCountdownUntil = null
        lastCountdownSecond = null
        for (player in Bukkit.getOnlinePlayers()) player.clearTitle()
        Bukkit.broadcast(Component.text(message).color(NamedTextColor.YELLOW))
        refreshOpen()
    }

    private fun showCountdown(seconds: Int) {
        val title = Title.title(
            Component.text(seconds.toString(), NamedTextColor.GOLD),
            Component.text("取消准备可以中断", NamedTextColor.YELLOW),
            Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)
        )
        for (player in Bukkit.getOnlinePlayers()) {
            player.showTitle(title)
            player.playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.2f)
        }
    }

    private fun secondsLeft(): Int? {
        val until = Manhunt.instance.session.readyCountdownUntil ?: return null
        return ((until - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)
    }

    private fun giveStar(player: Player) {
        if (holdsStar(player)) return
        val inventory = player.inventory
        val hand = inventory.heldItemSlot
        val slot = when {
            empty(inventory.getItem(hand)) -> hand
            empty(inventory.getItem(8)) -> 8
            else -> (0..35).firstOrNull { empty(inventory.getItem(it)) }
        }
        val item = Gear.lobbyStar()
        if (slot != null) {
            inventory.setItem(slot, item)
            return
        }
        val displaced = inventory.getItem(8)
        inventory.setItem(8, item)
        if (displaced != null && displaced.type != Material.AIR && !Gear.isLobbyStar(displaced)) {
            player.world.dropItem(player.location, displaced)
        }
    }

    private fun stripStar(player: Player) {
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            val item = inventory.getItem(slot) ?: continue
            if (Gear.isLobbyStar(item)) inventory.setItem(slot, null)
        }
        if (Gear.isLobbyStar(player.itemOnCursor)) player.setItemOnCursor(null)
    }

    private fun holdsStar(player: Player): Boolean {
        if (Gear.isLobbyStar(player.itemOnCursor)) return true
        return player.inventory.contents.any { it != null && Gear.isLobbyStar(it) }
    }

    private fun empty(item: ItemStack?) = item == null || item.type == Material.AIR

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.view.topInventory.holder !is SettingsHolder) return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val session = Manhunt.instance.session
        if (session.phase != Phase.LOBBY && session.phase != Phase.ENDED) {
            player.closeInventory()
            return
        }
        val slot = event.rawSlot
        if (slot < 0 || slot >= event.view.topInventory.size) return
        if (!switching(event.click) && slot != RESPAWN && slot != RESERVE) return
        when (slot) {
            JOIN_HUNTER -> if (switching(event.click)) Manhunt.instance.commands.join(player, "hunter")
            JOIN_RUNNER -> if (switching(event.click)) Manhunt.instance.commands.join(player, "runner")
            JOIN_SPECTATOR -> if (switching(event.click)) Manhunt.instance.commands.join(player, "spectator")
            LEAVE -> if (switching(event.click)) Manhunt.instance.commands.leave(player)
            READY -> if (switching(event.click)) toggleReady(player)
            RESPAWN, RESERVE, CHEST, MEDICINE -> editSetting(player, slot, event.click)
            ACTION -> if (switching(event.click)) {
                if (session.phase == Phase.ENDED) agreeReset(player) else forceStart(player)
            }
            else -> rules[slot]?.let { editRule(player, it, event.click) }
        }
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

    private fun switching(click: ClickType): Boolean =
        click == ClickType.LEFT || click == ClickType.RIGHT || click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT
}
