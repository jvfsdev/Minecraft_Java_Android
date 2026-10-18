package dev.prismdroid.game

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import org.libsdl.app.SDLActivity
import java.io.File

/**
 * Hosts the game. SDLActivity creates the surface and calls SDL_main() in libprismdroid.so,
 * which boots the JVM in this (:game) process. Minecraft then drives SDL3 + Vulkan itself.
 */
class GameActivity : SDLActivity() {

    override fun getLibraries() = arrayOf("SDL3", "prismdroid")

    override fun getArguments(): Array<String> = arrayOf(
        intent.getStringExtra(EXTRA_LAUNCH_FILE)!!,
        intent.getStringExtra(EXTRA_LOG_FILE)!!,
        intent.getStringExtra(EXTRA_WORK_DIR)!!,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        (mLayout as? ViewGroup)?.addView(TouchControls(this, File(intent.getStringExtra(EXTRA_WORK_DIR)!!)), ViewGroup.LayoutParams(-1, -1))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        const val EXTRA_LAUNCH_FILE = "launch_file"
        const val EXTRA_LOG_FILE = "log_file"
        const val EXTRA_WORK_DIR = "work_dir"

        fun intent(ctx: Context, launchFile: File, logFile: File, workDir: File) =
            Intent(ctx, GameActivity::class.java)
                .putExtra(EXTRA_LAUNCH_FILE, launchFile.path)
                .putExtra(EXTRA_LOG_FILE, logFile.path)
                .putExtra(EXTRA_WORK_DIR, workDir.path)
    }
}

