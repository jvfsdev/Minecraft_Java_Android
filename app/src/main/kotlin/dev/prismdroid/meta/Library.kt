package dev.prismdroid.meta

import kotlinx.serialization.json.*

/** A resolved, downloadable library (Maven coordinates mapped to a path under libraries/). */
data class Library(val name: String, val path: String, val url: String, val sha1: String?, val size: Long) {
    companion object {
        /** group:artifact[:classifier] without the version, used to de-duplicate. */
        fun key(name: String): String {
            val p = name.split(":")
            return if (p.size >= 4) "${p[0]}:${p[1]}:${p[3]}" else "${p[0]}:${p.getOrElse(1) { "" }}"
        }

        fun mavenPath(name: String): String {
            val parts = name.split("@")
            val ext = parts.getOrElse(1) { "jar" }
            val p = parts[0].split(":")
            val (g, a, v) = Triple(p[0], p[1], p[2])
            val cls = if (p.size > 3) "-${p[3]}" else ""
            return "${g.replace('.', '/')}/$a/$v/$a-$v$cls.$ext"
        }
    }
}

/**
 * Evaluates Mojang's rule format for *this* platform. Android is presented to the game as
 * Linux/aarch64 (that is what the JVM reports), but no Linux native artifact can run on bionic,
 * so native classifiers are filtered separately in [Platform.isUsableOnAndroid].
 */
object Platform {
    const val OS_NAME = "linux"
    const val ARCH = "arm64"

    fun rulesAllow(rules: JsonArray?, features: Map<String, Boolean> = emptyMap()): Boolean {
        if (rules == null) return true
        var allowed = false
        for (r in rules) {
            val o = r.jsonObject
            val action = o.s("action") == "allow"
            var matches = true
            o["os"]?.jsonObject?.let { os ->
                os.s("name")?.let { if (it != OS_NAME) matches = false }
                os.s("arch")?.let { if (it != ARCH && it != "aarch64") matches = false }
            }
            o["features"]?.jsonObject?.let { f ->
                for ((k, v) in f) if ((features[k] ?: false) != v.jsonPrimitive.boolean) matches = false
            }
            if (matches) allowed = action
        }
        return allowed
    }

    /** Linux natives are glibc builds: replaced by our Android builds (LWJGL) or unused (netty, jtracy). */
    fun isUsableOnAndroid(name: String): Boolean {
        val n = name.lowercase()
        if (":natives-" in n) return false
        // epoll/kqueue jars only carry glibc/macOS natives; unix-common also has required classes.
        if (n.startsWith("io.netty:netty-transport-native-epoll") || n.startsWith("io.netty:netty-transport-native-kqueue")) return false
        if (n.startsWith("com.mojang:jtracy-natives")) return false
        if (n.startsWith("ca.weblite:java-objc-bridge")) return false
        return true
    }

    fun libraries(version: JsonObject): List<Library> {
        val out = LinkedHashMap<String, Library>()
        for (e in version["libraries"]?.jsonArray ?: JsonArray(emptyList())) {
            val o = e.jsonObject
            val name = o.s("name") ?: continue
            if (!rulesAllow(o["rules"]?.jsonArray) || !isUsableOnAndroid(name)) continue
            val art = o["downloads"]?.jsonObject?.get("artifact")?.jsonObject
            val lib = if (art != null) {
                Library(name, art.s("path") ?: Library.mavenPath(name), art.s("url")!!, art.s("sha1"),
                    art["size"]?.jsonPrimitive?.longOrNull ?: 0)
            } else {
                // Fabric style: {name, url: <maven repo>, sha1?}
                val repo = (o.s("url") ?: "https://libraries.minecraft.net/").trimEnd('/') + "/"
                val path = Library.mavenPath(name)
                Library(name, path, repo + path, o.s("sha1"), o["size"]?.jsonPrimitive?.longOrNull ?: 0)
            }
            out.putIfAbsent(Library.key(name), lib)
        }
        return out.values.toList()
    }

    /** True when the game uses SDL3 + Vulkan (Minecraft 26.x+), the stack this launcher ships natives for. */
    fun isSupported(version: JsonObject): Boolean {
        val names = version["libraries"]?.jsonArray?.mapNotNull { it.jsonObject.s("name") } ?: return false
        return names.any { it.startsWith("org.lwjgl:lwjgl-sdl:") } && names.any { it.startsWith("org.lwjgl:lwjgl-vulkan:") }
    }
}
