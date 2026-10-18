# PrismDroid

An Android launcher for **Minecraft: Java Edition 26.x**, inspired by Prism Launcher (instances,
shared libraries/assets, Fabric, Microsoft accounts). The game runs **natively** on the device:
OpenJDK 25 built for Android (bionic/arm64) runs the official `client.jar`, and LWJGL 3.4.3
renders through **SDL3 + Vulkan**, the same backend Minecraft 26.x uses on desktop.

**No Minecraft code is bundled or redistributed.** The APK contains only the launcher, the Java
runtime and open-source native libraries. After the user signs in with a Microsoft account that
owns the game, the official client, libraries and assets are downloaded directly from Mojang's
servers (`piston-meta.mojang.com`, `resources.download.minecraft.net`) on the device, with SHA-1
verification, exactly like the official launcher and Prism Launcher do.

## Architecture

```
MainActivity (Compose)            :game process
 ├─ auth/  MSA device code ──► XBL ──► XSTS ──► launcher/login ──► entitlements/profile
 ├─ meta/  piston-meta + Fabric meta, OS/arch rules, inheritsFrom merge
 ├─ install/ parallel downloads (libraries/, assets/), JRE extraction
 └─ launch/  launch.args ─────────►  GameActivity (SDL3's SDLActivity)
                                      └─ libprismdroid.so: SDL_main → JNI_CreateJavaVM (HotSpot)
                                           └─ net.minecraft.client.main.Main / Fabric KnotClient
                                                ├─ LWJGL (liblwjgl*.so, Android builds)
                                                ├─ libSDL3.so (window / input / surface)
                                                ├─ libvkshim.so → system libvulkan.so
                                                └─ OpenAL (OpenSL ES), FreeType, shaderc, SPIRV-Cross, JNA
```

## Microsoft account sign-in

Sign-in follows the official launcher's flow:

1. Microsoft OAuth 2.0 **device code** flow (public client, `consumers` tenant, scope
   `XboxLive.signin offline_access`). The user enters a code on Microsoft's own page; the app
   never sees the password.
2. Xbox Live user token → XSTS token for `rp://api.minecraftservices.com/`.
3. `POST api.minecraftservices.com/launcher/login` → Minecraft access token.
4. `GET /entitlements/license` → the game only launches if the account owns Minecraft: Java Edition.
5. `GET /minecraft/profile` → player name and UUID passed to the game, which uses them for
   singleplayer and online-mode multiplayer servers.

Tokens are stored encrypted on the device (AES-GCM, key held in the Android Keystore) and are
only ever sent to Microsoft/Mojang endpoints.

The app uses **its own Azure client ID**, supplied at build time. To build it yourself, register
an app in Microsoft Entra (personal Microsoft accounts, public client flows enabled), request
Minecraft API access at <https://aka.ms/mce-reviewappid>, then add to `~/.gradle/gradle.properties`:

```
msaClientId=<your-client-id>
```

Please do not reuse another launcher's client ID.

## Building

Requirements: macOS, Android SDK + NDK 28.2, the SDK's CMake/Ninja, a JDK 25 in `JAVA_HOME`,
and `autoconf`.

```bash
native/fetch-sources.sh        # clones pinned upstream sources and applies native/patches/
native/build-jre.sh            # OpenJDK 25 → app/src/main/assets/runtime/jre-25-arm64.tar.xz
native/build-natives.sh        # SDL3, LWJGL, OpenAL, FreeType, SPIRV-Cross, shaderc, JNA → jniLibs/
./gradlew assembleDebug
```

## Porting patches (`native/patches/`)

| Patch | Why |
|---|---|
| `jdk25u-android.patch` | bionic lacks `dlinfo`, `_CS_GNU_LIBC_VERSION`, `__xpg_strerror_r`; needs `<netinet/in.h>`; no ALSA / serviceability agent; cross-configure from a macOS host |
| `lwjgl3-android.patch` | `liburing/compat.h` compatible with the NDK UAPI headers |
| `sdl-android.patch` | send `WINDOW_RESIZED` when the window is created (Minecraft learns its size from events) |
| `shaderc-cmake.patch` | generator-expression bug in shaderc's CMake |

Runtime adjustments (see `launcher.c`, `vkshim.c`, `LaunchBuilder.kt`):

- **Preloading JDK libraries**: Android's linker ignores `DT_RPATH`.
- **`-Djdk.lang.Process.launchMechanism=FORK`**: bionic's `posix_spawn` uses `CLONE_VM|CLONE_VFORK`;
  the child resetting signal handlers overwrote the parent's `libsigchain` state and dropped
  HotSpot's `SIGSEGV` handler.
- **vkshim**: emulates `VK_KHR_push_descriptor` on drivers without it (older Mali, the emulator)
  and disables swapchain pre-rotation (the Android compositor rotates instead).
- A writable `${natives_directory}`, `--add-opens X` → `--add-opens=X` for the JNI invocation API,
  LWJGL's GL module pointed at EGL/GLES (Minecraft loads it even when rendering with Vulkan).
- **JNA**: the jar only ships glibc `libjnidispatch`; `build-natives.sh jna` builds JNA 5.17.0's
  for bionic (embedded libffi, `native/jna/fficonfig.h`).

## Touch controls (`game/TouchControls.kt`)

While the game has grabbed the mouse (in-world): floating joystick on the left (WASD; push to the
edge to sprint), drag on the right to look, tap to attack, long-press to use/place, tap the hotbar
for slots 1–9, plus Jump, Sneak (toggle), Break (hold), Use, Inventory, Q, F5 and Chat buttons.
In menus, touches go straight to SDL as an absolute mouse.

## Status

Verified on the Android Emulator (Android 16, arm64, Vulkan 1.3 via gfxstream): Minecraft 26.3 and
Fabric 0.20.0 start, render with Vulkan, play audio, load a world (integrated server) and respond
to touch and keyboard. **Not yet verified**: real sign-in and online servers (pending client ID
approval) and physical devices.

Known gaps: versions before 26.1 (OpenGL/GLFW) are not supported; first-run JRE extraction is
slow; control layout and sensitivity are not configurable yet.

Debug builds have a **Demo** button that starts the game's official demo mode, to test the
runtime without signing in.

Non-commercial project. Minecraft is a trademark of Mojang Synergies AB; this project is not
affiliated with or endorsed by Mojang or Microsoft.
