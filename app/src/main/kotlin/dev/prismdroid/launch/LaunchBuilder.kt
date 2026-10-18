package dev.prismdroid.launch

import dev.prismdroid.BuildConfig
import dev.prismdroid.PrismDroidApp
import dev.prismdroid.auth.Account
import dev.prismdroid.install.Installer
import dev.prismdroid.install.Instance
import dev.prismdroid.meta.Platform
import dev.prismdroid.meta.s
import kotlinx.serialization.json.*
import java.io.File

/**
 * Turns a resolved version JSON into the JVM + game command line, like Prism's LaunchTask,
 * and writes it to `launch.args` for the native launcher (libprismdroid.so) to read.
 *
 * File format (UTF-8, one item per line):
 *   <JAVA_HOME>
 *   <main class>
 *   <JVM option>...
 *   --
 *   <game argument>...
 */
object LaunchBuilder {
    fun build(inst: Instance, acc: Account, server: String?, demo: Boolean = false): File {
        val p = PrismDroidApp.paths
        val version = Json.parseToJsonElement(inst.resolvedVersion.readText()).jsonObject
        val jre = p.jre25
        val gameDir = inst.gameDir.apply { mkdirs() }
        forceVulkan(File(gameDir, "options.txt"))

        val libs = Platform.libraries(version).map { File(p.libraries, it.path) }
        val classpath = (libs + Installer.clientJar(inst.config.mcVersion)).joinToString(":") { it.path }
        // ${natives_directory}: Mojang points java.library.path, jna/lwjgl/netty extract dirs here,
        // so it must be writable (the APK's lib dir is not). Our .so files come from nativeLibDir.
        val nativesDir = File(p.root, "natives").apply { listOf("java", "jna", "lwjgl", "netty").forEach { File(this, it).mkdirs() } }.path

        val vars = mapOf(
            "auth_player_name" to acc.name,
            "version_name" to inst.config.mcVersion,
            "game_directory" to gameDir.path,
            "assets_root" to p.assets.path,
            "game_assets" to p.assets.path,
            "assets_index_name" to version["assetIndex"]!!.jsonObject.s("id")!!,
            "auth_uuid" to acc.uuid,
            "auth_access_token" to acc.mcAccessToken,
            "auth_session" to acc.mcAccessToken,
            "clientid" to BuildConfig.MSA_CLIENT_ID,
            "auth_xuid" to acc.xuid,
            "user_type" to "msa",
            "user_properties" to "{}",
            "version_type" to (version.s("type") ?: "release"),
            "natives_directory" to nativesDir,
            "launcher_name" to "PrismDroid",
            "launcher_version" to BuildConfig.VERSION_NAME,
            "classpath" to classpath,
            "classpath_separator" to ":",
            "library_directory" to p.libraries.path,
            "quickPlayMultiplayer" to (server ?: ""),
            "quickPlayPath" to File(gameDir, "quickPlay/log.json").path,
        )
        val features = mapOf("is_quick_play_multiplayer" to (server != null), "is_demo_user" to demo)

        val jvm = mutableListOf<String>()
        jvm += "-Xms${minOf(1024, inst.config.memoryMb)}m"
        jvm += "-Xmx${inst.config.memoryMb}m"
        val vJvm = version["arguments"]?.jsonObject?.get("jvm")?.jsonArray
        if (vJvm != null) jvm += expand(vJvm, vars, features)
        else jvm += listOf("-Djava.library.path=$nativesDir", "-cp", classpath)
        jvm += androidJvmArgs()   // after the version's args: later -D values win
        version["logging"]?.jsonObject?.get("client")?.jsonObject?.let { lg ->
            val f = File(p.assets, "log_configs/${lg["file"]!!.jsonObject.s("id")}")
            jvm += lg.s("argument")!!.replace("\${path}", f.path)
        }
        jvm += inst.config.extraJvmArgs.split(Regex("\\s+")).filter { it.isNotBlank() }

        val game = mutableListOf<String>()
        version["arguments"]?.jsonObject?.get("game")?.jsonArray?.let { game += expand(it, vars, features) }
        version.s("minecraftArguments")?.let { legacy -> game += legacy.split(" ").map { subst(it, vars) } }
        if (server != null && "--quickPlayMultiplayer" !in game) game += listOf("--quickPlayMultiplayer", server)

        val lines = buildList {
            add(jre.path); add(version.s("mainClass")!!)
            addAll(toJniOptions(jvm.filter { it != "-cp" && it != classpath }))
            add("-Djava.class.path=$classpath")
            add("--"); addAll(game)
        }
        require(lines.none { '\n' in it }) { "argument contains a newline" }
        inst.launchFile.writeText(lines.joinToString("\n"))
        return inst.launchFile
    }

    private fun androidJvmArgs(): List<String> {
        val p = PrismDroidApp.paths
        val nat = p.nativeLibDir.path
        val args = listOf(
            "-Djava.io.tmpdir=${p.cache.path}",
            "-Duser.home=${p.root.path}",
            "-XX:-UsePerfData",                       // no /tmp/hsperfdata on Android
            // bionic posix_spawn() is CLONE_VM|CLONE_VFORK: the child resetting signals would
            // overwrite libsigchain state in *our* memory and drop HotSpot's SIGSEGV handler.
            "-Djdk.lang.Process.launchMechanism=FORK",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=50",
            "-Dorg.lwjgl.librarypath=$nat",
            "-Dorg.lwjgl.sdl.libname=$nat/libSDL3.so", // same instance SDLActivity already loaded
            "-Dorg.lwjgl.vulkan.libname=$nat/libvkshim.so", // wraps libvulkan.so (push descriptor emulation)
            // MC also initialises LWJGL's GL module; on Android that resolves through EGL/GLES.
            "-Dorg.lwjgl.opengl.libname=libGLESv2.so",
            "-Dorg.lwjgl.egl.libname=libEGL.so",
            "-Dorg.lwjgl.openal.libname=$nat/libopenal.so",
            "-Dorg.lwjgl.freetype.libname=$nat/libfreetype.so",
            "-Dorg.lwjgl.shaderc.libname=$nat/libshaderc.so",
            "-Dorg.lwjgl.spvc.libname=$nat/libspirv-cross.so",
            "-Dorg.lwjgl.system.allocator=system",
            "-Dorg.lwjgl.util.Debug=${BuildConfig.DEBUG}",
            "-Dorg.lwjgl.util.DebugLoader=${BuildConfig.DEBUG}",
            "-Dorg.lwjgl.util.NoHashChecks=true",      // our natives are Android builds of the same 3.4.3 sources
            "-Dio.netty.transport.noNative=true",
            "-Djava.awt.headless=true",
            "-Dfile.encoding=UTF-8",
            "-Dprismdroid=true",
            // JNA's jar only has glibc builds of libjnidispatch; ours (native/build-natives.sh jna) is in the APK.
            "-Djna.boot.library.path=$nat",
            "-Djna.nosys=true",
            "-Djna.nounpack=true",
        )
        return args
    }

    /**
     * The `java` launcher accepts `--add-opens X` as two tokens; JNI_CreateJavaVM only takes the
     * single-token `--add-opens=X` form, so join them (and map -p to --module-path=).
     */
    private fun toJniOptions(args: List<String>): List<String> {
        val joinable = setOf("--add-exports", "--add-opens", "--add-reads", "--add-modules",
            "--enable-native-access", "--module-path", "--limit-modules", "--upgrade-module-path", "-p")
        val out = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a in joinable && i + 1 < args.size) {
                out += (if (a == "-p") "--module-path" else a) + "=" + args[i + 1]; i += 2
            } else { out += a; i++ }
        }
        return out
    }

    private fun subst(s: String, vars: Map<String, String>) =
        Regex("\\$\\{([^}]+)\\}").replace(s) { m -> vars[m.groupValues[1]] ?: m.value }

    private fun expand(arr: JsonArray, vars: Map<String, String>, features: Map<String, Boolean>): List<String> {
        val out = mutableListOf<String>()
        for (e in arr) {
            if (e is JsonPrimitive) { out += subst(e.content, vars); continue }
            val o = e.jsonObject
            if (!Platform.rulesAllow(o["rules"]?.jsonArray, features)) continue
            when (val v = o["value"]) {
                is JsonPrimitive -> out += subst(v.content, vars)
                is JsonArray -> v.forEach { out += subst(it.jsonPrimitive.content, vars) }
                else -> {}
            }
        }
        // Drop flags whose value expanded to nothing (e.g. an unset quick play path).
        return out.filter { !it.contains("\${") }
    }

    /** Android has no desktop OpenGL: the game must use its Vulkan backend. */
    private fun forceVulkan(options: File) {
        val lines = if (options.isFile) options.readLines().toMutableList() else mutableListOf()
        val i = lines.indexOfFirst { it.startsWith("preferredGraphicsBackend:") }
        val entry = "preferredGraphicsBackend:\"vulkan\""
        if (i >= 0) lines[i] = entry else lines += entry
        options.writeText(lines.joinToString("\n") + "\n")
    }
}
