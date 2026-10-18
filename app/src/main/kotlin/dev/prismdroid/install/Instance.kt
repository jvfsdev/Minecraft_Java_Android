package dev.prismdroid.install

import dev.prismdroid.PrismDroidApp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Loader(val type: String, val version: String)

/** One game instance, like a Prism instance: its own .minecraft (mods, saves, options) under minecraft/. */
@Serializable
data class InstanceConfig(
    val name: String,
    val mcVersion: String,
    val loader: Loader? = null,
    val memoryMb: Int = 2048,
    val extraJvmArgs: String = "",
    val lastServer: String = "",
    val lastPlayed: Long = 0,
)

class Instance(val dir: File, val config: InstanceConfig) {
    val gameDir get() = File(dir, "minecraft")
    val resolvedVersion get() = File(dir, "version.json")
    val launchFile get() = File(dir, "launch.args")
    val logFile get() = File(dir, "logs/launcher.log")
    val id get() = dir.name
}

object Instances {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val root get() = PrismDroidApp.paths.instances

    fun list(): List<Instance> = (root.listFiles() ?: emptyArray())
        .filter { File(it, "instance.json").isFile }
        .mapNotNull { d -> runCatching { Instance(d, json.decodeFromString<InstanceConfig>(File(d, "instance.json").readText())) }.getOrNull() }
        .sortedByDescending { it.config.lastPlayed }

    fun create(cfg: InstanceConfig): Instance {
        val base = cfg.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "instance" }
        var dir = File(root, base); var n = 1
        while (dir.exists()) dir = File(root, "$base (${n++})")
        File(dir, "minecraft").mkdirs()
        return save(Instance(dir, cfg))
    }

    fun save(inst: Instance): Instance {
        inst.dir.mkdirs()
        File(inst.dir, "instance.json").writeText(json.encodeToString(inst.config))
        return inst
    }

    fun update(inst: Instance, cfg: InstanceConfig) = save(Instance(inst.dir, cfg))

    fun delete(inst: Instance) { inst.dir.deleteRecursively() }
}
