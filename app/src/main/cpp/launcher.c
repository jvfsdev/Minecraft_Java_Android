/*
 * PrismDroid native launcher.
 *
 * SDLActivity (SDL3's Android glue) loads libSDL3.so and this library, then calls SDL_main()
 * on its own thread. We start HotSpot (OpenJDK 25 built for bionic) *inside this process* with
 * JNI_CreateJavaVM and run Minecraft's main class. LWJGL's SDL binding then dlopen()s the very
 * same libSDL3.so, so the game drives the window/surface SDLActivity created, and renders with
 * Vulkan through SDL_Vulkan_CreateSurface.
 *
 * Launch description: argv[1] is the path of launch.args written by LaunchBuilder.kt.
 */
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>

#define TAG "PrismDroid"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef jint (JNICALL *CreateJavaVM_t)(JavaVM **, void **, void *);

static FILE *g_log;

/* ---- stdout/stderr → logcat + instance log file ---- */
static void *pipe_reader(void *arg) {
    int fd = (int)(intptr_t)arg;
    char buf[4096];
    size_t len = 0;
    for (;;) {
        ssize_t n = read(fd, buf + len, sizeof(buf) - 1 - len);
        if (n <= 0) break;
        len += (size_t)n;
        buf[len] = 0;
        char *start = buf, *nl;
        while ((nl = strchr(start, '\n'))) {
            *nl = 0;
            __android_log_write(ANDROID_LOG_INFO, "Minecraft", start);
            if (g_log) { fputs(start, g_log); fputc('\n', g_log); fflush(g_log); }
            start = nl + 1;
        }
        len = strlen(start);
        memmove(buf, start, len);
        if (len == sizeof(buf) - 1) {  /* overlong line: flush as-is */
            __android_log_write(ANDROID_LOG_INFO, "Minecraft", buf);
            len = 0;
        }
    }
    return NULL;
}

static void redirect_stdio(void) {
    int fds[2];
    if (pipe(fds) != 0) return;
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    dup2(fds[1], STDOUT_FILENO);
    dup2(fds[1], STDERR_FILENO);
    pthread_t t;
    pthread_create(&t, NULL, pipe_reader, (void *)(intptr_t)fds[0]);
    pthread_detach(t);
}

/* ---- launch.args parsing ---- */
typedef struct {
    char *java_home, *main_class;
    char **jvm; int njvm;
    char **game; int ngame;
} Launch;

static char *read_file(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *s = malloc((size_t)n + 1);
    if (fread(s, 1, (size_t)n, f) != (size_t)n) { fclose(f); free(s); return NULL; }
    s[n] = 0;
    fclose(f);
    return s;
}

static int parse_launch(const char *path, Launch *L) {
    char *text = read_file(path);
    if (!text) { LOGE("cannot read %s: %s", path, strerror(errno)); return -1; }
    int cap = 64, n = 0;
    char **lines = malloc(sizeof(char *) * cap);
    for (char *save = NULL, *tok = strtok_r(text, "\n", &save); tok; tok = strtok_r(NULL, "\n", &save)) {
        if (n == cap) lines = realloc(lines, sizeof(char *) * (cap *= 2));
        lines[n++] = tok;
    }
    if (n < 2) return -1;
    L->java_home = lines[0];
    L->main_class = lines[1];
    int sep = 2;
    while (sep < n && strcmp(lines[sep], "--") != 0) sep++;
    L->jvm = lines + 2; L->njvm = sep - 2;
    L->game = lines + sep + 1; L->ngame = sep < n ? n - sep - 1 : 0;
    return 0;
}

/* ---- JVM ---- */
static Launch g_launch;
static int g_exit_code = 1;

static void *jvm_main(void *unused) {
    (void)unused;
    Launch *L = &g_launch;
    char path[4096];

    /* libjvm depends on nothing outside bionic, but libjava etc. are looked up via java.home. */
    snprintf(path, sizeof(path), "%s/lib/server/libjvm.so", L->java_home);
    void *jvm_lib = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!jvm_lib) { LOGE("dlopen libjvm: %s", dlerror()); return NULL; }
    /* Android's linker ignores DT_RPATH ($ORIGIN), so a NEEDED "libnet.so" would not be found
     * next to libnio.so. Once a library is loaded by absolute path its soname satisfies later
     * NEEDED entries, so preload the JDK libraries in dependency order. (libfontmanager and the
     * JDK's own libfreetype are skipped on purpose: LWJGL loads its own libfreetype.so.) */
    static const char *preload[] = {
        "libjava.so", "libjimage.so", "libzip.so", "libnet.so", "libnio.so", "libextnet.so",
        "libverify.so", "libsyslookup.so", "libmanagement.so", "libmanagement_ext.so", "libprefs.so",
        "libmlib_image.so", "libawt.so", "libawt_headless.so",
    };
    for (size_t i = 0; i < sizeof(preload) / sizeof(*preload); i++) {
        snprintf(path, sizeof(path), "%s/lib/%s", L->java_home, preload[i]);
        if (access(path, F_OK) == 0 && !dlopen(path, RTLD_NOW | RTLD_GLOBAL))
            LOGE("preload %s: %s", preload[i], dlerror());
    }

    CreateJavaVM_t create = (CreateJavaVM_t)dlsym(jvm_lib, "JNI_CreateJavaVM");
    if (!create) { LOGE("JNI_CreateJavaVM not found"); return NULL; }

    JavaVMOption *opts = calloc((size_t)L->njvm + 2, sizeof(JavaVMOption));
    int no = 0;
    char home_opt[4200];
    snprintf(home_opt, sizeof(home_opt), "-Djava.home=%s", L->java_home);
    opts[no++].optionString = home_opt;
    for (int i = 0; i < L->njvm; i++) {
        /* -Xss etc. are fine; launcher-only flags like --enable-native-access pass through as -- options */
        opts[no++].optionString = L->jvm[i];
    }

    /* HotSpot relies on SIGSEGV/SIGBUS for implicit null checks and safepoint polls. ART's
     * libsigchain owns those signals; give it a neutral user action first so that HotSpot's
     * sigaction() calls become the chained user handlers (same approach as PojavLauncher). */
    for (int sig = 1; sig < NSIG; sig++) {
        if (sig == SIGKILL || sig == SIGSTOP) continue;
        struct sigaction sa;
        memset(&sa, 0, sizeof(sa));
        sigemptyset(&sa.sa_mask);
        sa.sa_handler = (sig == SIGSEGV) ? SIG_IGN : SIG_DFL;
        sigaction(sig, &sa, NULL);
    }

    JavaVMInitArgs args = { .version = 0x00010008 /* JNI 1.8; NDK jni.h is ART's */, .nOptions = no, .options = opts, .ignoreUnrecognized = JNI_FALSE };
    JavaVM *vm; JNIEnv *env;
    LOGI("starting JVM (%d options), main class %s", no, L->main_class);
    if (create(&vm, (void **)&env, &args) != JNI_OK) { LOGE("JNI_CreateJavaVM failed"); return NULL; }
    {
        struct sigaction cur;
        Dl_info info;
        sigaction(SIGSEGV, NULL, &cur);
        void *h = (void *)cur.sa_sigaction;
        LOGI("SIGSEGV handler after JVM init: %p (%s)", h,
             dladdr(h, &info) && info.dli_fname ? info.dli_fname : "?");
    }

    char cls_name[512];
    snprintf(cls_name, sizeof(cls_name), "%s", L->main_class);
    for (char *c = cls_name; *c; c++) if (*c == '.') *c = '/';
    jclass main_cls = (*env)->FindClass(env, cls_name);
    if (!main_cls) { (*env)->ExceptionDescribe(env); goto done; }
    jmethodID main_m = (*env)->GetStaticMethodID(env, main_cls, "main", "([Ljava/lang/String;)V");
    if (!main_m) { (*env)->ExceptionDescribe(env); goto done; }

    jclass str_cls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray jargs = (*env)->NewObjectArray(env, L->ngame, str_cls, NULL);
    for (int i = 0; i < L->ngame; i++)
        (*env)->SetObjectArrayElement(env, jargs, i, (*env)->NewStringUTF(env, L->game[i]));

    (*env)->CallStaticVoidMethod(env, main_cls, main_m, jargs);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); g_exit_code = 1; }
    else g_exit_code = 0;

done:
    /* Like the java launcher: wait for non-daemon threads (the render thread) to finish. */
    (*vm)->DetachCurrentThread(vm);
    (*vm)->DestroyJavaVM(vm);
    return NULL;
}

int SDL_main(int argc, char *argv[]) {
    if (argc < 2) { LOGE("usage: <launch.args> [log file] [work dir]"); return 1; }
    if (argc >= 3) g_log = fopen(argv[2], "w");
    redirect_stdio();
    /* Minecraft's window is resizable, which makes SDL follow the sensor; keep it landscape. */
    setenv("SDL_ORIENTATIONS", "LandscapeLeft LandscapeRight", 1);
    /* SDL and LWJGL both load Vulkan through our shim (see vkshim.c), found next to us. */
    {
        Dl_info self;
        if (dladdr((void *)SDL_main, &self) && self.dli_fname) {
            char shim[4096];
            snprintf(shim, sizeof(shim), "%s", self.dli_fname);
            char *slash = strrchr(shim, '/');
            if (slash) { strcpy(slash + 1, "libvkshim.so"); setenv("SDL_VULKAN_LIBRARY", shim, 1); }
        }
    }
    if (parse_launch(argv[1], &g_launch) != 0) { LOGE("bad launch file"); return 1; }
    /* log4j and friends resolve logs/, crash-reports/ against the working directory */
    if (chdir(argc >= 4 ? argv[3] : g_launch.java_home) != 0) LOGE("chdir: %s", strerror(errno));

    /* HotSpot wants a big primordial-like stack; SDL's thread stack is small. */
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 16 * 1024 * 1024);
    pthread_t t;
    pthread_create(&t, &attr, jvm_main, NULL);
    pthread_join(t, NULL);
    LOGI("game exited with %d", g_exit_code);
    if (g_log) fclose(g_log);
    _exit(g_exit_code);  /* the :game process ends with the game */
}
