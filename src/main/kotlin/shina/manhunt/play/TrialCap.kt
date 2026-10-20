package shina.manhunt.play

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import shina.manhunt.Manhunt
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

object TrialCap {
    private const val MAX_PLAYERS = 4
    private val told = mutableMapOf<UUID, Long>()
    private var wrapped = 0
    private var failed = false
    private var playersMissing = false

    fun start(plugin: Manhunt) {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { scan(plugin) }, 40L, 40L)
    }

    private fun scan(plugin: Manhunt) {
        val seen = HashSet<Long>()
        for (player in Bukkit.getOnlinePlayers()) {
            val world = player.world
            val cx = player.location.chunk.x
            val cz = player.location.chunk.z
            for (dx in -2..2) for (dz in -2..2) {
                val x = cx + dx
                val z = cz + dz
                if (!world.isChunkLoaded(x, z)) continue
                val key = world.uid.mostSignificantBits xor (x.toLong() shl 32) xor z.toLong()
                if (!seen.add(key)) continue
                val chunk = world.getChunkAt(x, z)
                for (state in chunk.tileEntities) {
                    if (state.type != Material.TRIAL_SPAWNER) continue
                    wrap(plugin, state.block.world, state.x, state.y, state.z)
                }
            }
        }
    }

    private fun wrap(plugin: Manhunt, world: World, x: Int, y: Int, z: Int) {
        if (failed && wrapped == 0) return
        try {
            val level = world.javaClass.getMethod("getHandle").invoke(world)
            val posClass = Class.forName("net.minecraft.core.BlockPos")
            val pos = posClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(x, y, z)
            val entity = level.javaClass.methods.first {
                it.name == "getBlockEntity" && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(posClass)
            }.invoke(level, pos) ?: return
            val (owner, field) = detectorField(entity) ?: return
            val current = field.get(owner) ?: return
            if (Proxy.isProxyClass(current.javaClass)) return
            val iface = field.type
            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), handler(current, owner))
            setField(owner, field, proxy)
            trim(owner)
            wrapped++
            if (wrapped == 1) plugin.logger.info("试炼笼激活人数已限制为 $MAX_PLAYERS。")
        } catch (error: Throwable) {
            if (!failed) {
                failed = true
                plugin.logger.warning("没能限制试炼笼激活人数：${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }

    private fun handler(original: Any, spawner: Any) = InvocationHandler { _, method, args ->
        if (method.declaringClass == Any::class.java) return@InvocationHandler invokeObject(method, original, args)
        val detected = method.invoke(original, *(args ?: emptyArray()))
        if (!isDetect(method) || detected !is List<*>) return@InvocationHandler detected
        val seen = detected.filterIsInstance<UUID>()
        val already = detectedPlayers(spawner)
        if (already == null) {
            if (!playersMissing) {
                playersMissing = true
                Bukkit.getLogger().warning("试炼笼找不到已激活玩家列表，人数限制没有生效。")
            }
            return@InvocationHandler detected
        }
        val allowed = cap(seen, already)
        notify(seen, allowed)
        allowed
    }

    private fun isDetect(method: Method) =
        method.parameterCount == 5 && List::class.java.isAssignableFrom(method.returnType)

    private fun cap(seen: List<UUID>, already: Set<UUID>): List<UUID> {
        if (already.size >= MAX_PLAYERS) return seen.filter { it in already }
        val room = MAX_PLAYERS - already.size
        val accepted = ArrayList<UUID>(room)
        for (id in seen) {
            if (id in already) continue
            if (accepted.size >= room) break
            accepted += id
        }
        val accept = accepted.toSet()
        return seen.filter { it in already || it in accept }
    }

    private fun notify(seen: List<UUID>, allowed: List<UUID>) {
        if (seen.size == allowed.size) return
        val kept = allowed.toSet()
        val now = System.currentTimeMillis()
        for (id in seen) {
            if (id in kept) continue
            val last = told[id] ?: 0L
            if (now - last < 3000) continue
            told[id] = now
            val player = Bukkit.getPlayer(id) ?: continue
            player.sendActionBar(Component.text("试炼笼最多 4 人激活").color(NamedTextColor.RED))
        }
        if (told.size > 128) told.entries.removeIf { now - it.value > 10000 }
    }

    private fun trim(spawner: Any) {
        val players = detectedPlayers(spawner) ?: return
        if (players.size <= MAX_PLAYERS) return
        val keep = players.take(MAX_PLAYERS).toSet()
        players.retainAll(keep)
    }

    private fun detectedPlayers(spawner: Any): MutableSet<UUID>? {
        namedSet(spawner, "detectedPlayers")?.let { return it }
        for (field in spawner.javaClass.declaredFields) {
            if (!field.type.name.contains("TrialSpawnerData")) continue
            field.isAccessible = true
            val data = field.get(spawner) ?: continue
            namedSet(data, "detectedPlayers")?.let { return it }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun namedSet(target: Any, name: String): MutableSet<UUID>? {
        val field = target.javaClass.declaredFields.firstOrNull { it.name == name } ?: return null
        field.isAccessible = true
        return field.get(target) as? MutableSet<UUID>
    }

    private fun detectorField(entity: Any): Pair<Any, Field>? {
        fieldOfType(entity, "PlayerDetector")?.let { return entity to it }
        for (field in entity.javaClass.declaredFields) {
            if (field.type.isPrimitive || field.type.name.startsWith("java.")) continue
            field.isAccessible = true
            val nested = field.get(entity) ?: continue
            fieldOfType(nested, "PlayerDetector")?.let { return nested to it }
        }
        return null
    }

    private fun fieldOfType(target: Any, simpleName: String): Field? =
        target.javaClass.declaredFields.firstOrNull { it.type.simpleName == simpleName }?.also { it.isAccessible = true }

    private fun setField(target: Any, field: Field, value: Any) {
        field.isAccessible = true
        try {
            field.set(target, value)
        } catch (_: Throwable) {
            val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val offset = unsafe.javaClass.getMethod("objectFieldOffset", Field::class.java).invoke(unsafe, field) as Long
            unsafe.javaClass.getMethod("putObject", Any::class.java, java.lang.Long.TYPE, Any::class.java)
                .invoke(unsafe, target, offset, value)
        }
    }

    private fun invokeObject(method: Method, original: Any, args: Array<Any?>?) = when (method.name) {
        "toString" -> "TrialCap(${original.javaClass.simpleName})"
        "hashCode" -> System.identityHashCode(original)
        "equals" -> original == args?.firstOrNull()
        else -> method.invoke(original, *(args ?: emptyArray()))
    }
}
