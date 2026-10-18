package dev.prismdroid.meta

import dev.prismdroid.PrismDroidApp
import dev.prismdroid.net.Http
import kotlinx.serialization.json.*
import java.io.File

data class ManifestEntry(val id: String, val type: String, val url: String, val sha1: String, val releaseTime: String)

/**
 * Version metadata straight from Mojang (piston-meta) plus Fabric's meta API.
 * A version JSON may `inheritsFrom` another (Fabric profiles do); [resolve] merges the chain
 * the same way the vanilla launcher and Prism's component system do.
 */
object Meta {
    private const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private const val FABRIC = "https://meta.fabricmc.net/v2"

    private val dir get() = PrismDroidApp.paths.meta

    suspend fun manifest(refresh: Boolean = false): List<ManifestEntry> {
        val cache = File(dir, "version_manifest_v2.json")
        val text = if (!refresh && cache.isFile && System.currentTimeMillis() - cache.lastModified() < 3_600_000) {
            cache.readText()
        } else {
            runCatching { Http.getText(MANIFEST).also { cache.parentFile!!.mkdirs(); cache.writeText(it) } }
                .getOrElse { if (cache.isFile) cache.readText() else throw it }
        }
        return Http.json.parseToJsonElement(text).jsonObject["versions"]!!.jsonArray.map {
            val o = it.jsonObject
            ManifestEntry(o.s("id")!!, o.s("type")!!, o.s("url")!!, o.s("sha1")!!, o.s("releaseTime")!!)
        }
    }

    /** Downloads (sha1-verified) and returns the vanilla version JSON. */
    suspend fun vanilla(id: String): JsonObject {
        val f = File(dir, "net.minecraft/$id.json")
        if (!f.isFile) {
            val e = manifest().firstOrNull { it.id == id } ?: manifest(refresh = true).first { it.id == id }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Http.download(e.url, f, e.sha1) }
        }
        return Http.json.parseToJsonElement(f.readText()).jsonObject
    }

    suspend fun fabricLoaders(mcVersion: String): List<String> =
        Http.getJson("$FABRIC/versions/loader/$mcVersion").jsonArray.map {
            it.jsonObject["loader"]!!.jsonObject.s("version")!!
        }

    suspend fun fabricProfile(mcVersion: String, loader: String): JsonObject {
        val f = File(dir, "net.fabricmc.fabric-loader/$mcVersion-$loader.json")
        if (!f.isFile) {
            val text = Http.getText("$FABRIC/versions/loader/$mcVersion/$loader/profile/json")
            f.parentFile!!.mkdirs(); f.writeText(text)
        }
        return Http.json.parseToJsonElement(f.readText()).jsonObject
    }

    /** Merges a child profile over its parent: libraries/arguments are concatenated, scalars overridden. */
    fun merge(parent: JsonObject, child: JsonObject): JsonObject = buildJsonObject {
        for ((k, v) in parent) if (k !in child) put(k, v)
        for ((k, v) in child) when (k) {
            "inheritsFrom" -> {}
            // Child libraries win over parent libraries with the same group:artifact (e.g. ASM).
            "libraries" -> {
                val childLibs = v.jsonArray
                val childKeys = childLibs.map { Library.key(it.jsonObject.s("name")!!) }.toSet()
                putJsonArray("libraries") {
                    childLibs.forEach { add(it) }
                    parent["libraries"]?.jsonArray?.filter { Library.key(it.jsonObject.s("name")!!) !in childKeys }?.forEach { add(it) }
                }
            }
            "arguments" -> putJsonObject("arguments") {
                val pa = parent["arguments"]?.jsonObject ?: JsonObject(emptyMap())
                val ca = v.jsonObject
                for (side in listOf("game", "jvm")) putJsonArray(side) {
                    pa[side]?.jsonArray?.forEach { add(it) }
                    ca[side]?.jsonArray?.forEach { add(it) }
                }
            }
            else -> put(k, v)
        }
    }
}

internal fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
