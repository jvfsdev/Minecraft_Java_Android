package dev.prismdroid.install

import dev.prismdroid.PrismDroidApp
import dev.prismdroid.meta.Meta
import dev.prismdroid.meta.Platform
import dev.prismdroid.meta.s
import dev.prismdroid.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class Progress(val stage: String, val done: Long, val total: Long)

class UnsupportedVersionException(v: String) : Exception(
    "Minecraft $v uses OpenGL/GLFW natives that this Android build does not provide yet. " +
        "Use 26.1 or newer (SDL3 + Vulkan).")

/** Resolves and downloads everything an instance needs (libraries, client, assets), Prism-style. */
object Installer {
    private const val RESOURCES = "https://resources.download.minecraft.net"

    suspend fun resolve(inst: Instance): JsonObject {
        var v = Meta.vanilla(inst.config.mcVersion)
        inst.config.loader?.let { l ->
            when (l.type) {
                "fabric" -> v = Meta.merge(v, Meta.fabricProfile(inst.config.mcVersion, l.version))
                else -> error("Loader ${l.type} is not supported yet")
            }
        }
        return v
    }

    suspend fun install(inst: Instance, onProgress: (Progress) -> Unit): JsonObject = withContext(Dispatchers.IO) {
        val p = PrismDroidApp.paths
        onProgress(Progress("Resolving version", 0, 1))
        val version = resolve(inst)
        if (!Platform.isSupported(version)) throw UnsupportedVersionException(inst.config.mcVersion)

        data class Job(val url: String, val dest: File, val sha1: String?, val size: Long)
        val jobs = mutableListOf<Job>()

        Platform.libraries(version).forEach { jobs += Job(it.url, File(p.libraries, it.path), it.sha1, it.size) }

        val vanillaId = inst.config.mcVersion
        version["downloads"]?.jsonObject?.get("client")?.jsonObject?.let { c ->
            jobs += Job(c.s("url")!!, clientJar(vanillaId), c.s("sha1"), c["size"]?.jsonPrimitive?.long ?: 0)
        }

        version["logging"]?.jsonObject?.get("client")?.jsonObject?.get("file")?.jsonObject?.let { f ->
            jobs += Job(f.s("url")!!, File(p.assets, "log_configs/${f.s("id")}"), f.s("sha1"), f["size"]?.jsonPrimitive?.long ?: 0)
        }

        val ai = version["assetIndex"]!!.jsonObject
        val indexFile = File(p.assets, "indexes/${ai.s("id")}.json")
        Http.download(ai.s("url")!!, indexFile, ai.s("sha1"))
        val objects = Http.json.parseToJsonElement(indexFile.readText()).jsonObject["objects"]!!.jsonObject
        for ((_, o) in objects) {
            val hash = o.jsonObject.s("hash")!!
            jobs += Job("$RESOURCES/${hash.take(2)}/$hash", File(p.assets, "objects/${hash.take(2)}/$hash"), hash,
                o.jsonObject["size"]!!.jsonPrimitive.long)
        }

        val pending = jobs.filter { !(it.dest.isFile && (it.size == 0L || it.dest.length() == it.size)) }
        val total = pending.sumOf { it.size }
        val done = AtomicLong()
        val gate = Semaphore(12)
        onProgress(Progress("Downloading ${pending.size} files", 0, total))
        coroutineScope {
            pending.map { j ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        Http.download(j.url, j.dest, j.sha1) { n ->
                            onProgress(Progress("Downloading ${pending.size} files", done.addAndGet(n), total))
                        }
                    }
                }
            }.awaitAll()
        }

        inst.resolvedVersion.writeText(version.toString())
        onProgress(Progress("Ready", total, total))
        version
    }

    fun clientJar(id: String) = File(PrismDroidApp.paths.libraries, "com/mojang/minecraft/$id/minecraft-$id-client.jar")
}
