package org.libsdl.app;

/**
 * PrismDroid's window into SDL's package-private input state (not part of SDL; build-natives.sh
 * only overwrites SDL's own files here).
 */
public final class PrismDroidInput {
    private PrismDroidInput() {}

    /** True while the game holds SDL relative mouse mode, i.e. Minecraft grabbed the mouse (in-world, no screen open). */
    public static boolean mouseGrabbed() {
        return SDLActivity.getMotionListener().inRelativeMode();
    }

    /** Closes the on-screen keyboard (e.g. it stays up after chat is closed with Esc). */
    public static void hideKeyboard() {
        SDLActivity.sendMessage(SDLActivity.COMMAND_TEXTEDIT_HIDE, 0);
    }
}
