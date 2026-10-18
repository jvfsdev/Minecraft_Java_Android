package dev.prismdroid.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import org.libsdl.app.PrismDroidInput
import org.libsdl.app.SDLActivity
import java.io.File
import kotlin.math.hypot
import kotlin.math.min

/**
 * On-screen controls drawn over the SDL surface. Everything is one View that hit-tests each
 * pointer itself, so any number of fingers can move, look and press buttons at once.
 *
 * While Minecraft has grabbed the mouse (SDL relative mode = in-world, no screen open):
 *  - left side: floating joystick -> W/A/S/D (pushed to the rim while going forward: sprint)
 *  - right side: drag = relative mouse look, tap = attack, long-press = hold use/place
 *  - hotbar taps select slots 1-9; buttons for jump, sneak (toggle), break (hold), inventory,
 *    drop, chat, perspective
 * Otherwise (menus, inventory, chat) touches go straight to SDL, which maps them to an absolute
 * mouse; only Esc and the keyboard button stay on screen.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class TouchControls(ctx: Context, private val gameDir: File) : View(ctx) {

    private enum class Show { ALWAYS, IN_GAME }

    private inner class Btn(
        val label: String, val show: Show, val toggle: Boolean = false,
        val place: (w: Float, h: Float) -> RectF,
        val press: (Boolean) -> Unit,
    ) {
        var rect = RectF()
        var down = false        // currently pressed (or latched, for toggles)
        fun visible() = show == Show.ALWAYS || inGame
    }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val buttons = listOf(
        // top-left
        Btn("Esc", Show.ALWAYS, place = { _, _ -> box(8f, 8f, 52f, 40f) }, press = { key(KeyEvent.KEYCODE_ESCAPE, it) }),
        Btn("⌨", Show.ALWAYS, place = { _, _ -> box(68f, 8f, 52f, 40f) },
            press = { if (it) SDLActivity.showTextInput(0, 0, 0, 1, 1) }),
        Btn("Chat", Show.IN_GAME, place = { _, _ -> box(128f, 8f, 52f, 40f) }, press = { down ->
            key(KeyEvent.KEYCODE_T, down)
            if (!down) postDelayed({ SDLActivity.showTextInput(0, 0, 0, 1, 1) }, 150)
        }),
        // top-right, below the toast/demo-timer area
        Btn("F5", Show.IN_GAME, place = { w, _ -> boxR(w, 8f, 60f, 52f, 40f) }, press = { key(KeyEvent.KEYCODE_F5, it) }),
        Btn("Q", Show.IN_GAME, place = { w, _ -> boxR(w, 68f, 60f, 52f, 40f) }, press = { key(KeyEvent.KEYCODE_Q, it) }),
        Btn("Inv", Show.IN_GAME, place = { w, _ -> boxR(w, 128f, 60f, 52f, 40f) }, press = { key(KeyEvent.KEYCODE_E, it) }),
        // bottom-right cluster
        Btn("Jump", Show.IN_GAME, place = { w, h -> RectF(w - dp(96f), h - dp(96f), w - dp(16f), h - dp(16f)) },
            press = { key(KeyEvent.KEYCODE_SPACE, it) }),
        Btn("Sneak", Show.IN_GAME, toggle = true, place = { w, h -> RectF(w - dp(176f), h - dp(80f), w - dp(108f), h - dp(24f)) },
            press = { key(KeyEvent.KEYCODE_SHIFT_LEFT, it) }),
        Btn("Break", Show.IN_GAME, place = { w, h -> RectF(w - dp(96f), h - dp(176f), w - dp(28f), h - dp(108f)) },
            press = { mouseButton(BUTTON_LEFT, it) }),
        Btn("Use", Show.IN_GAME, place = { w, h -> RectF(w - dp(176f), h - dp(160f), w - dp(108f), h - dp(92f)) },
            press = { mouseButton(BUTTON_RIGHT, it) }),
    )
    private fun box(x: Float, y: Float, w: Float, h: Float) = RectF(dp(x), dp(y), dp(x + w), dp(y + h))
    private fun boxR(width: Float, xr: Float, y: Float, w: Float, h: Float) = RectF(width - dp(xr + w), dp(y), width - dp(xr), dp(y + h))

    // --- state -------------------------------------------------------------------------------
    private var inGame = false
    private var buttonState = 0                       // SDL/Android mouse button mask
    private val owner = HashMap<Int, Any>()           // pointer id -> Btn | Joystick | Look | HOTBAR

    private inner class Joystick(val cx: Float, val cy: Float) {
        var kx = cx; var ky = cy
        val held = HashSet<Int>()
        fun move(x: Float, y: Float) {
            val r = dp(JOY_RADIUS)
            var dx = x - cx; var dy = y - cy
            val len = hypot(dx, dy)
            if (len > r) { dx *= r / len; dy *= r / len }
            kx = cx + dx; ky = cy + dy
            val nx = dx / r; val ny = dy / r
            val want = HashSet<Int>()
            if (len > dp(8f)) {
                if (ny < -0.38f) want += KeyEvent.KEYCODE_W
                if (ny > 0.38f) want += KeyEvent.KEYCODE_S
                if (nx < -0.38f) want += KeyEvent.KEYCODE_A
                if (nx > 0.38f) want += KeyEvent.KEYCODE_D
                if (ny < -0.85f && len >= r * 0.97f) want += KeyEvent.KEYCODE_CTRL_LEFT
            }
            (held - want).forEach { key(it, false) }
            (want - held).forEach { key(it, true) }
            held.clear(); held += want
        }
        fun release() { held.forEach { key(it, false) }; held.clear() }
    }

    private inner class Look(var x: Float, var y: Float) {
        val t0 = SystemClock.uptimeMillis()
        var travel = 0f
        var using = false
        val longPress = Runnable {
            if (travel < dp(TAP_SLOP)) {
                using = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                mouseButton(BUTTON_RIGHT, true)
            }
        }
    }

    private var joystick: Joystick? = null

    private val poll = object : Runnable {
        override fun run() {
            val g = runCatching { PrismDroidInput.mouseGrabbed() }.getOrDefault(false)
            if (g != inGame) {
                inGame = g
                if (g) PrismDroidInput.hideKeyboard() else releaseGameplay()
                invalidate()
            }
            postDelayed(this, 100)
        }
    }

    private val ui = Handler(Looper.getMainLooper())

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(poll) }
    override fun onDetachedFromWindow() { removeCallbacks(poll); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        buttons.forEach { it.rect = it.place(w.toFloat(), h.toFloat()) }
    }

    // --- touch ---------------------------------------------------------------------------------
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val claimed = down(e.getPointerId(i), e.getX(i), e.getY(i))
                // Returning false for the first finger lets the touch fall through to SDL (menus).
                if (!claimed && e.actionMasked == MotionEvent.ACTION_DOWN) return false
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex), cancel = false)
            MotionEvent.ACTION_CANCEL -> owner.keys.toList().forEach { up(it, cancel = true) }
        }
        invalidate()
        return true
    }

    private fun down(id: Int, x: Float, y: Float): Boolean {
        buttons.firstOrNull { it.visible() && it.rect.contains(x, y) }?.let { b ->
            if (b.toggle) { b.down = !b.down; b.press(b.down) } else { b.down = true; b.press(true) }
            owner[id] = b
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            return true
        }
        if (!inGame) return false
        val slot = hotbarSlot(x, y)
        if (slot >= 0) {
            key(KeyEvent.KEYCODE_1 + slot, true); key(KeyEvent.KEYCODE_1 + slot, false)
            owner[id] = HOTBAR
            return true
        }
        if (x < width * 0.4f && joystick == null) {
            owner[id] = Joystick(x, y).also { joystick = it }
            return true
        }
        owner[id] = Look(x, y).also { ui.postDelayed(it.longPress, LONG_PRESS_MS) }
        return true
    }

    private fun move(id: Int, x: Float, y: Float) {
        when (val o = owner[id]) {
            is Joystick -> o.move(x, y)
            is Look -> {
                val dx = x - o.x; val dy = y - o.y
                o.x = x; o.y = y
                o.travel += hypot(dx, dy)
                if (o.travel >= dp(TAP_SLOP)) ui.removeCallbacks(o.longPress)
                if ((dx != 0f || dy != 0f) && inGame)
                    SDLActivity.onNativeMouse(buttonState, ACTION_MOVE, dx * LOOK_SCALE, dy * LOOK_SCALE, true)
            }
        }
    }

    private fun up(id: Int, cancel: Boolean) {
        when (val o = owner.remove(id)) {
            is Btn -> if (!o.toggle) { o.down = false; o.press(false) }
            is Joystick -> { o.release(); joystick = null }
            is Look -> {
                ui.removeCallbacks(o.longPress)
                if (o.using) mouseButton(BUTTON_RIGHT, false)
                else if (!cancel && inGame && o.travel < dp(TAP_SLOP) && SystemClock.uptimeMillis() - o.t0 < LONG_PRESS_MS) {
                    // tap = attack: a short click, released a frame or two later so the game sees it
                    mouseButton(BUTTON_LEFT, true)
                    ui.postDelayed({ mouseButton(BUTTON_LEFT, false) }, 60)
                }
            }
        }
    }

    /** Leaving the world (menu opened): drop held movement/mouse so nothing sticks. Sneak stays latched. */
    private fun releaseGameplay() {
        owner.entries.removeAll { (_, o) ->
            when (o) {
                is Joystick -> { o.release(); joystick = null; true }
                is Look -> { ui.removeCallbacks(o.longPress); if (o.using) mouseButton(BUTTON_RIGHT, false); true }
                is Btn -> if (o.show == Show.IN_GAME && !o.toggle) { o.down = false; o.press(false); true } else false
                else -> true
            }
        }
        if (buttonState != 0) { buttonState = 0; SDLActivity.onNativeMouse(0, ACTION_UP, 0f, 0f, true) }
    }

    // --- hotbar geometry (mirrors Minecraft's GUI scale) ---------------------------------------
    private var guiScaleOption = -1
    private fun guiScale(): Int {
        if (guiScaleOption < 0) guiScaleOption = runCatching {
            File(gameDir, "options.txt").readLines().firstOrNull { it.startsWith("guiScale:") }
                ?.substringAfter(':')?.trim()?.toInt() ?: 0
        }.getOrDefault(0)
        // Window.calculateScale: largest scale keeping >= 320x240 GUI pixels, capped by the option
        var s = 1
        while (s != guiScaleOption && s < width && s < height && width / (s + 1) >= 320 && height / (s + 1) >= 240) s++
        return s
    }

    private fun hotbarRect(): RectF {
        val s = guiScale()
        val w = 182f * s; val h = 22f * s
        return RectF((width - w) / 2f, height - h, (width + w) / 2f, height.toFloat())
    }

    private fun hotbarSlot(x: Float, y: Float): Int {
        val r = hotbarRect()
        if (!r.contains(x, y)) return -1
        return ((x - r.left) / (r.width() / 9f)).toInt().coerceIn(0, 8)
    }

    // --- drawing --------------------------------------------------------------------------------
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = 0x88FFFFFF.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEEFFFFFF.toInt(); textAlign = Paint.Align.CENTER; textSize = dp(15f) }

    override fun onDraw(c: Canvas) {
        for (b in buttons) {
            if (!b.visible()) continue
            fill.color = if (b.down) 0x99FFFFFF.toInt() else 0x55000000
            val r = min(b.rect.width(), b.rect.height()) / 4f
            c.drawRoundRect(b.rect, r, r, fill)
            c.drawRoundRect(b.rect, r, r, stroke)
            c.drawText(b.label, b.rect.centerX(), b.rect.centerY() - (text.ascent() + text.descent()) / 2f, text)
        }
        joystick?.let { j ->
            fill.color = 0x33000000
            c.drawCircle(j.cx, j.cy, dp(JOY_RADIUS), fill)
            c.drawCircle(j.cx, j.cy, dp(JOY_RADIUS), stroke)
            fill.color = if (KeyEvent.KEYCODE_CTRL_LEFT in j.held) 0xBBFFD54F.toInt() else 0x88FFFFFF.toInt()
            c.drawCircle(j.kx, j.ky, dp(26f), fill)
        }
    }

    // --- SDL injection --------------------------------------------------------------------------
    private fun key(code: Int, down: Boolean) {
        if (down) SDLActivity.onNativeKeyDown(code) else SDLActivity.onNativeKeyUp(code)
    }

    /** SDL's Android_OnMouse diffs a button *state mask* against the previous one. */
    private fun mouseButton(bit: Int, down: Boolean) {
        val next = if (down) buttonState or bit else buttonState and bit.inv()
        if (next == buttonState) return
        buttonState = next
        SDLActivity.onNativeMouse(next, if (down) ACTION_DOWN else ACTION_UP, 0f, 0f, true)
    }

    private companion object {
        val HOTBAR = Any()
        const val BUTTON_LEFT = 1      // MotionEvent.BUTTON_PRIMARY
        const val BUTTON_RIGHT = 2     // MotionEvent.BUTTON_SECONDARY
        const val ACTION_DOWN = 0; const val ACTION_UP = 1; const val ACTION_MOVE = 2
        const val JOY_RADIUS = 64f     // dp
        const val TAP_SLOP = 12f       // dp
        const val LONG_PRESS_MS = 350L
        const val LOOK_SCALE = 1.0f    // touch px -> mouse counts
    }
}
