package shina.manhunt.game

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import shina.manhunt.Manhunt
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.random.Random

object MapReset {
    private const val MARK = "manhunt-reset.txt"
    private const val LOBBY = "lobby"
    private val PROXY_CHANNELS = listOf("bungeecord:main", "BungeeCord")

    fun request(plugin: Manhunt, sender: CommandSender) {
        if (sender is Player && !sender.isOp) {
            sender.sendMessage(Component.text("只有管理员可以重置地图。").color(NamedTextColor.RED))
            return
        }
        val root = plugin.server.worldContainer
        val props = File(root, "server.properties")
        if (!props.isFile) {
            sender.sendMessage(Component.text("找不到 server.properties，没有重置。").color(NamedTextColor.RED))
            return
        }
        val seed = Random.nextLong()
        val newName = "world$seed"
        val oldName = levelName(props.readLines()) ?: plugin.server.worlds.firstOrNull()?.name ?: "world"
        val newline = if (props.readText().contains("\r\n")) "\r\n" else "\n"
        props.writeText(rewrite(props.readLines(), seed, newName).joinToString(newline) + newline)
        File(root, MARK).writeText(listOf(oldName, "${oldName}_nether", "${oldName}_the_end").joinToString("\n"))
        Bukkit.broadcast(Component.text("地图将重置为种子 $seed，服务器即将重启。").color(NamedTextColor.GOLD))
        val players = Bukkit.getOnlinePlayers().toList()
        if (players.isEmpty()) {
            plugin.server.scheduler.runTask(plugin, Runnable { plugin.server.restart() })
            return
        }
        registerProxy(plugin)
        for (player in players) {
            if (sendLobby(plugin, player)) continue
            player.kick(Component.text("服务器即将重置。").color(NamedTextColor.GOLD))
        }
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                player.kick(Component.text("服务器即将重置。").color(NamedTextColor.GOLD))
            }
            plugin.server.restart()
        }, 40L)
    }

    private fun registerProxy(plugin: Manhunt) {
        val messenger = plugin.server.messenger
        val registered = messenger.getOutgoingChannels(plugin)
        for (channel in PROXY_CHANNELS) {
            if (!registered.contains(channel)) messenger.registerOutgoingPluginChannel(plugin, channel)
        }
    }

    private fun sendLobby(plugin: Manhunt, player: Player): Boolean {
        val channel = PROXY_CHANNELS.firstOrNull { player.listeningPluginChannels.contains(it) } ?: return false
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeUTF("Connect")
            it.writeUTF(LOBBY)
        }
        player.sendPluginMessage(plugin, channel, bytes.toByteArray())
        player.sendMessage(Component.text("正在返回大厅。").color(NamedTextColor.GOLD))
        return true
    }

    fun cleanup(plugin: Manhunt) {
        val root = plugin.server.worldContainer
        val mark = File(root, MARK)
        if (!mark.isFile) return
        val live = plugin.server.worlds.map { it.name }.toSet()
        val rootPath = root.canonicalFile.toPath()
        var failed = false
        for (name in mark.readLines().map { it.trim() }.filter { it.isNotEmpty() }) {
            if (!name.matches(Regex("[A-Za-z0-9_-]+"))) continue
            if (name in live) continue
            val dir = File(root, name)
            if (!dir.isDirectory) continue
            val path = dir.canonicalFile.toPath()
            if (!path.startsWith(rootPath)) continue
            if (!dir.deleteRecursively()) failed = true
        }
        if (!failed) mark.delete()
        else plugin.logger.warning("旧地图没有全部删除，下次启动会再试。")
    }

    private fun levelName(lines: List<String>): String? =
        lines.firstOrNull { it.startsWith("level-name=") }?.substringAfter("=")?.trim()?.takeIf { it.isNotEmpty() }

    private fun rewrite(lines: List<String>, seed: Long, name: String): List<String> {
        var hasSeed = false
        var hasName = false
        val updated = lines.map { line ->
            when {
                line.startsWith("level-seed=") -> {
                    hasSeed = true
                    "level-seed=$seed"
                }
                line.startsWith("level-name=") -> {
                    hasName = true
                    "level-name=$name"
                }
                else -> line
            }
        }.toMutableList()
        if (!hasSeed) updated += "level-seed=$seed"
        if (!hasName) updated += "level-name=$name"
        return updated
    }
}
