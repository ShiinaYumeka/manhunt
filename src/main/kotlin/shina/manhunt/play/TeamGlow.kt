package shina.manhunt.play

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import shina.manhunt.Manhunt
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.UUID

class TeamGlow(private val plugin: Manhunt) {
    private val shown = mutableSetOf<Pair<UUID, UUID>>()
    private var broken = false
    private var ready = false
    private lateinit var getHandle: Method
    private lateinit var getEntityData: Method
    private lateinit var readData: Method
    private lateinit var flagsAccessor: Any
    private var flagId: Int = 0
    private lateinit var byteSerializer: Any
    private lateinit var newDataValue: Constructor<*>
    private lateinit var newPacket: Constructor<*>
    private lateinit var connectionField: Field
    private lateinit var sendPacket: Method

    fun apply(pairs: List<Pair<Player, Player>>) {
        if (broken) return
        val next = pairs.map { it.first.uniqueId to it.second.uniqueId }.toSet()
        for ((viewer, mate) in pairs) paint(viewer, mate, true)
        for ((viewerId, mateId) in shown) {
            if (viewerId to mateId in next) continue
            val viewer = Bukkit.getPlayer(viewerId) ?: continue
            val mate = Bukkit.getPlayer(mateId) ?: continue
            paint(viewer, mate, false)
        }
        shown.clear()
        shown.addAll(next)
    }

    fun show(viewer: Player, mate: Player) {
        if (broken || viewer == mate) return
        if (paint(viewer, mate, true)) shown += viewer.uniqueId to mate.uniqueId
    }

    fun clear() {
        if (broken) {
            shown.clear()
            return
        }
        for ((viewerId, mateId) in shown) {
            val viewer = Bukkit.getPlayer(viewerId) ?: continue
            val mate = Bukkit.getPlayer(mateId) ?: continue
            paint(viewer, mate, false)
        }
        shown.clear()
    }

    private fun paint(viewer: Player, target: Player, glowing: Boolean): Boolean {
        if (!prepare()) return false
        return try {
            val handle = getHandle.invoke(target)
            val data = getEntityData.invoke(handle)
            val flags = (readData.invoke(data, flagsAccessor) as Byte).toInt()
            val value = (if (glowing) flags or 0x40 else flags and 0x40.inv()).toByte()
            val packed = newDataValue.newInstance(flagId, byteSerializer, value)
            val packet = newPacket.newInstance(target.entityId, listOf(packed))
            val viewerHandle = getHandle.invoke(viewer)
            val connection = connectionField.get(viewerHandle)
            sendPacket.invoke(connection, packet)
            true
        } catch (error: Exception) {
            fail(error)
            false
        }
    }

    private fun prepare(): Boolean {
        if (ready) return true
        if (broken) return false
        return try {
            val entityClass = Class.forName("net.minecraft.world.entity.Entity")
            val accessorClass = Class.forName("net.minecraft.network.syncher.EntityDataAccessor")
            val accessor = entityClass.declaredFields.first { field ->
                field.type == accessorClass && field.genericType.typeName.contains("Byte")
            }
            accessor.isAccessible = true
            flagsAccessor = accessor.get(null)
            flagId = accessorClass.getMethod("id").invoke(flagsAccessor) as Int
            byteSerializer = accessorClass.getMethod("serializer").invoke(flagsAccessor)
            val dataValue = Class.forName("net.minecraft.network.syncher.SynchedEntityData\$DataValue")
            newDataValue = dataValue.declaredConstructors.first { it.parameterCount == 3 }
            newDataValue.isAccessible = true
            val packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket")
            newPacket = packetClass.declaredConstructors.first { ctor ->
                ctor.parameterCount == 2 && ctor.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            newPacket.isAccessible = true
            val sample = Bukkit.getOnlinePlayers().firstOrNull()
            val craftClass = if (sample != null) sample.javaClass else Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer")
            getHandle = craftClass.getMethod("getHandle")
            val serverPlayer = if (sample != null) getHandle.invoke(sample).javaClass else Class.forName("net.minecraft.server.level.ServerPlayer")
            getEntityData = serverPlayer.methods.first { it.name == "getEntityData" && it.parameterCount == 0 }
            val dataClass = Class.forName("net.minecraft.network.syncher.SynchedEntityData")
            readData = dataClass.getMethod("get", accessorClass)
            connectionField = serverPlayer.getField("connection")
            val packetType = Class.forName("net.minecraft.network.protocol.Packet")
            sendPacket = connectionField.type.methods.first { method ->
                method.name == "send" && method.parameterCount == 1 && method.parameterTypes[0].isAssignableFrom(packetType)
            }
            ready = true
            true
        } catch (error: Exception) {
            fail(error)
            false
        }
    }

    private fun fail(error: Exception) {
        if (broken) return
        broken = true
        plugin.logger.warning("队友发光不可用：${error.javaClass.simpleName}: ${error.message}")
    }
}
