package dev.prismdroid

import android.app.Application
import java.io.File

class PrismDroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        paths = Paths(filesDir, cacheDir, File(applicationInfo.nativeLibraryDir))
    }

    companion object {
        lateinit var paths: Paths
            private set
    }
}

/** Directory layout, modelled on Prism Launcher's data dir (shared libraries/assets, one dir per instance). */
class Paths(val root: File, val cache: File, val nativeLibDir: File) {
    val libraries = File(root, "libraries")
    val assets = File(root, "assets")
    val meta = File(root, "meta")
    val instances = File(root, "instances")
    val runtimes = File(root, "runtimes")
    val accounts = File(root, "accounts.bin")
    val jre25 = File(runtimes, "jre-25")
}
