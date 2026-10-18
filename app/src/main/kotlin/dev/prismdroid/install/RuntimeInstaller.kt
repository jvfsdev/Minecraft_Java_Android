package dev.prismdroid.install

import android.content.Context
import dev.prismdroid.PrismDroidApp
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.File

/** Unpacks the bundled Android build of OpenJDK 25 (assets/runtime) into the app's data dir. */
object RuntimeInstaller {
    private const val ASSET = "runtime/jre-25-arm64.tar.xz"

    fun isBundled(ctx: Context) = runCatching { ctx.assets.open(ASSET).close(); true }.getOrDefault(false)

    fun ensureJre(ctx: Context, onStage: (String) -> Unit = {}): File {
        val p = PrismDroidApp.paths
        val stamp = File(p.jre25, ".installed")
        // Re-extract only when the bundled archive changes, not on every app update.
        val want = ctx.assets.openFd(ASSET).use { it.length.toString() }
        if (stamp.isFile && stamp.readText() == want) return p.jre25
        onStage("Installing Java 25 runtime")
        p.jre25.deleteRecursively()
        p.runtimes.mkdirs()
        TarArchiveInputStream(XZInputStream(ctx.assets.open(ASSET).buffered())).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val out = File(p.runtimes, e.name).canonicalFile
                require(out.path.startsWith(p.runtimes.canonicalPath)) { "bad entry ${e.name}" }
                when {
                    e.isDirectory -> out.mkdirs()
                    e.isSymbolicLink -> { out.parentFile?.mkdirs(); android.system.Os.symlink(e.linkName, out.path) }
                    else -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                        if (e.mode and 0b001_000_000 != 0) out.setExecutable(true)
                    }
                }
            }
        }
        stamp.writeText(want)
        return p.jre25
    }
}
