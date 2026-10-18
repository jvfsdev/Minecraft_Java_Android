/*
 * libvkshim.so — a thin Vulkan loader wrapper used by the game process.
 *
 * Minecraft 26.x's Vulkan backend requires VK_KHR_push_descriptor. Most recent Android GPUs
 * expose it, but some drivers (older Mali, the Android Emulator's gfxstream) do not. When the
 * device lacks it, this shim advertises the extension and emulates vkCmdPushDescriptorSetKHR
 * with ordinary descriptor sets allocated from per-command-buffer pools:
 *
 *   push(cmd, layout, set, writes)  ==>  allocate set from cmd's pool, vkUpdateDescriptorSets,
 *                                        vkCmdBindDescriptorSets
 *
 * Pools are reset when the command buffer is begun/reset again (the app guarantees it finished
 * executing by then). When the driver supports the extension natively, everything passes
 * straight through to the system libvulkan.so.
 *
 * Both SDL (SDL_VULKAN_LIBRARY) and LWJGL (org.lwjgl.vulkan.libname) load this library, so they
 * see the same vkGetInstanceProcAddr.
 */
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

#define TAG "PrismDroid-vkshim"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define EXPORT __attribute__((visibility("default")))

/* ---------------- tiny pointer-keyed hash map ---------------- */
typedef struct { uint64_t key; void *val; } Slot;
typedef struct { Slot *slots; size_t cap, len; } Map;

static size_t hash64(uint64_t k) { k ^= k >> 33; k *= 0xff51afd7ed558ccdULL; k ^= k >> 33; return (size_t)k; }

static void map_put(Map *m, uint64_t key, void *val);
static void map_grow(Map *m) {
    Map n = { calloc(m->cap ? m->cap * 2 : 64, sizeof(Slot)), m->cap ? m->cap * 2 : 64, 0 };
    for (size_t i = 0; i < m->cap; i++) if (m->slots[i].key) map_put(&n, m->slots[i].key, m->slots[i].val);
    free(m->slots); *m = n;
}
static void map_put(Map *m, uint64_t key, void *val) {
    if ((m->len + 1) * 2 > m->cap) map_grow(m);
    for (size_t i = hash64(key) & (m->cap - 1);; i = (i + 1) & (m->cap - 1)) {
        if (!m->slots[i].key) { m->slots[i].key = key; m->slots[i].val = val; m->len++; return; }
        if (m->slots[i].key == key) { m->slots[i].val = val; return; }
    }
}
static void *map_get(Map *m, uint64_t key) {
    if (!m->cap) return NULL;
    for (size_t i = hash64(key) & (m->cap - 1); m->slots[i].key; i = (i + 1) & (m->cap - 1))
        if (m->slots[i].key == key) return m->slots[i].val;
    return NULL;
}
static void *map_del(Map *m, uint64_t key) {
    void *v = map_get(m, key);
    if (!v) return NULL;
    /* rebuild without the key (deletions are rare: layout/command-buffer destruction) */
    Map n = { calloc(m->cap, sizeof(Slot)), m->cap, 0 };
    for (size_t i = 0; i < m->cap; i++)
        if (m->slots[i].key && m->slots[i].key != key) map_put(&n, m->slots[i].key, m->slots[i].val);
    free(m->slots); *m = n;
    return v;
}
#define K(h) ((uint64_t)(uintptr_t)(h))

/* ---------------- state ---------------- */
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static void *g_lib;
static PFN_vkGetInstanceProcAddr real_GIPA;
static PFN_vkGetDeviceProcAddr real_GDPA;
static PFN_vkEnumerateDeviceExtensionProperties real_EnumDevExt;
static PFN_vkCreateDevice real_CreateDevice;
static PFN_vkGetPhysicalDeviceProperties2 real_GetPDProps2, real_GetPDProps2KHR;

static int g_emulate;            /* set when the created device needed emulation */
static VkDevice g_device;
static PFN_vkDestroyDevice real_DestroyDevice;
static PFN_vkCreateDescriptorSetLayout real_CreateDSL;
static PFN_vkCreatePipelineLayout real_CreatePL;
static PFN_vkDestroyPipelineLayout real_DestroyPL;
static PFN_vkAllocateCommandBuffers real_AllocCB;
static PFN_vkFreeCommandBuffers real_FreeCB;
static PFN_vkBeginCommandBuffer real_BeginCB;
static PFN_vkResetCommandBuffer real_ResetCB;
static PFN_vkResetCommandPool real_ResetCP;
static PFN_vkDestroyCommandPool real_DestroyCP;
static PFN_vkCreateDescriptorPool real_CreateDP;
static PFN_vkDestroyDescriptorPool real_DestroyDP;
static PFN_vkResetDescriptorPool real_ResetDP;
static PFN_vkAllocateDescriptorSets real_AllocDS;
static PFN_vkUpdateDescriptorSets real_UpdateDS;
static PFN_vkCmdBindDescriptorSets real_CmdBindDS;

typedef struct { uint32_t count; VkDescriptorSetLayout sets[]; } PipeLayout;
typedef struct { VkCommandPool cmdPool; VkDescriptorPool *pools; uint32_t npools, cur; } CmdState;
static Map g_layouts;   /* VkPipelineLayout -> PipeLayout* */
static Map g_cmds;      /* VkCommandBuffer  -> CmdState*   */

static void load_real(void) {
    if (g_lib) return;
    g_lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    real_GIPA = g_lib ? (PFN_vkGetInstanceProcAddr)dlsym(g_lib, "vkGetInstanceProcAddr") : NULL;
}

/* ---------------- push descriptor emulation ---------------- */
static VkDescriptorPool new_pool(void) {
    static const VkDescriptorType types[] = {
        VK_DESCRIPTOR_TYPE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
        VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
        VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER,
        VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
        VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT,
    };
    VkDescriptorPoolSize sizes[sizeof(types) / sizeof(*types)];
    for (size_t i = 0; i < sizeof(types) / sizeof(*types); i++) { sizes[i].type = types[i]; sizes[i].descriptorCount = 4096; }
    VkDescriptorPoolCreateInfo ci = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, .maxSets = 1024,
        .poolSizeCount = sizeof(types) / sizeof(*types), .pPoolSizes = sizes,
    };
    VkDescriptorPool p = VK_NULL_HANDLE;
    real_CreateDP(g_device, &ci, NULL, &p);
    return p;
}

static void cmd_reset_pools(CmdState *s) {
    for (uint32_t i = 0; i < s->npools; i++) real_ResetDP(g_device, s->pools[i], 0);
    s->cur = 0;
}

static void cmd_free(CmdState *s) {
    for (uint32_t i = 0; i < s->npools; i++) real_DestroyDP(g_device, s->pools[i], NULL);
    free(s->pools); free(s);
}

static VkDescriptorSet cmd_alloc_set(CmdState *s, VkDescriptorSetLayout layout) {
    for (;;) {
        if (s->cur == s->npools) {
            s->pools = realloc(s->pools, sizeof(VkDescriptorPool) * (s->npools + 1));
            s->pools[s->npools++] = new_pool();
        }
        VkDescriptorSetAllocateInfo ai = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .descriptorPool = s->pools[s->cur], .descriptorSetCount = 1, .pSetLayouts = &layout,
        };
        VkDescriptorSet ds;
        VkResult r = real_AllocDS(g_device, &ai, &ds);
        if (r == VK_SUCCESS) return ds;
        if (r != VK_ERROR_OUT_OF_POOL_MEMORY && r != VK_ERROR_FRAGMENTED_POOL) return VK_NULL_HANDLE;
        s->cur++;
    }
}

static VKAPI_ATTR void VKAPI_CALL shim_CmdPushDescriptorSetKHR(
        VkCommandBuffer cmd, VkPipelineBindPoint bindPoint, VkPipelineLayout layout, uint32_t set,
        uint32_t writeCount, const VkWriteDescriptorSet *writes) {
    pthread_mutex_lock(&g_lock);
    PipeLayout *pl = map_get(&g_layouts, K(layout));
    CmdState *s = map_get(&g_cmds, K(cmd));
    VkDescriptorSet ds = (pl && s && set < pl->count) ? cmd_alloc_set(s, pl->sets[set]) : VK_NULL_HANDLE;
    pthread_mutex_unlock(&g_lock);
    if (!ds) { LOGI("push descriptor emulation failed (layout %p set %u)", (void *)layout, set); return; }

    VkWriteDescriptorSet local[16];
    VkWriteDescriptorSet *w = writeCount <= 16 ? local : malloc(sizeof(*w) * writeCount);
    for (uint32_t i = 0; i < writeCount; i++) { w[i] = writes[i]; w[i].dstSet = ds; }
    real_UpdateDS(g_device, writeCount, w, 0, NULL);
    if (w != local) free(w);
    real_CmdBindDS(cmd, bindPoint, layout, set, 1, &ds, 0, NULL);
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_CreateDescriptorSetLayout(
        VkDevice dev, const VkDescriptorSetLayoutCreateInfo *ci, const VkAllocationCallbacks *a, VkDescriptorSetLayout *out) {
    VkDescriptorSetLayoutCreateInfo c = *ci;
    c.flags &= ~VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR;
    return real_CreateDSL(dev, &c, a, out);
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_CreatePipelineLayout(
        VkDevice dev, const VkPipelineLayoutCreateInfo *ci, const VkAllocationCallbacks *a, VkPipelineLayout *out) {
    VkResult r = real_CreatePL(dev, ci, a, out);
    if (r == VK_SUCCESS) {
        PipeLayout *pl = malloc(sizeof(PipeLayout) + sizeof(VkDescriptorSetLayout) * ci->setLayoutCount);
        pl->count = ci->setLayoutCount;
        memcpy(pl->sets, ci->pSetLayouts, sizeof(VkDescriptorSetLayout) * ci->setLayoutCount);
        pthread_mutex_lock(&g_lock); map_put(&g_layouts, K(*out), pl); pthread_mutex_unlock(&g_lock);
    }
    return r;
}

static VKAPI_ATTR void VKAPI_CALL shim_DestroyPipelineLayout(VkDevice dev, VkPipelineLayout l, const VkAllocationCallbacks *a) {
    pthread_mutex_lock(&g_lock); free(map_del(&g_layouts, K(l))); pthread_mutex_unlock(&g_lock);
    real_DestroyPL(dev, l, a);
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_AllocateCommandBuffers(
        VkDevice dev, const VkCommandBufferAllocateInfo *ai, VkCommandBuffer *out) {
    VkResult r = real_AllocCB(dev, ai, out);
    if (r == VK_SUCCESS) {
        pthread_mutex_lock(&g_lock);
        for (uint32_t i = 0; i < ai->commandBufferCount; i++) {
            CmdState *s = calloc(1, sizeof(CmdState));
            s->cmdPool = ai->commandPool;
            map_put(&g_cmds, K(out[i]), s);
        }
        pthread_mutex_unlock(&g_lock);
    }
    return r;
}

static VKAPI_ATTR void VKAPI_CALL shim_FreeCommandBuffers(VkDevice dev, VkCommandPool pool, uint32_t n, const VkCommandBuffer *cbs) {
    pthread_mutex_lock(&g_lock);
    for (uint32_t i = 0; i < n; i++) { CmdState *s = map_del(&g_cmds, K(cbs[i])); if (s) cmd_free(s); }
    pthread_mutex_unlock(&g_lock);
    real_FreeCB(dev, pool, n, cbs);
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_BeginCommandBuffer(VkCommandBuffer cmd, const VkCommandBufferBeginInfo *bi) {
    pthread_mutex_lock(&g_lock);
    CmdState *s = map_get(&g_cmds, K(cmd));
    if (s) cmd_reset_pools(s);
    pthread_mutex_unlock(&g_lock);
    return real_BeginCB(cmd, bi);
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_ResetCommandBuffer(VkCommandBuffer cmd, VkCommandBufferResetFlags f) {
    pthread_mutex_lock(&g_lock);
    CmdState *s = map_get(&g_cmds, K(cmd));
    if (s) cmd_reset_pools(s);
    pthread_mutex_unlock(&g_lock);
    return real_ResetCB(cmd, f);
}

static void for_pool(VkCommandPool pool, int destroy) {
    for (size_t i = 0; i < g_cmds.cap; i++) {
        Slot *sl = &g_cmds.slots[i];
        if (!sl->key) continue;
        CmdState *s = sl->val;
        if (s->cmdPool != pool) continue;
        if (destroy) { uint64_t k = sl->key; cmd_free(s); map_del(&g_cmds, k); i = (size_t)-1; }
        else cmd_reset_pools(s);
    }
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_ResetCommandPool(VkDevice dev, VkCommandPool pool, VkCommandPoolResetFlags f) {
    pthread_mutex_lock(&g_lock); for_pool(pool, 0); pthread_mutex_unlock(&g_lock);
    return real_ResetCP(dev, pool, f);
}

static VKAPI_ATTR void VKAPI_CALL shim_DestroyCommandPool(VkDevice dev, VkCommandPool pool, const VkAllocationCallbacks *a) {
    pthread_mutex_lock(&g_lock); for_pool(pool, 1); pthread_mutex_unlock(&g_lock);
    real_DestroyCP(dev, pool, a);
}

static VKAPI_ATTR void VKAPI_CALL shim_DestroyDevice(VkDevice dev, const VkAllocationCallbacks *a) {
    pthread_mutex_lock(&g_lock);
    if (dev == g_device) {
        for (size_t i = 0; i < g_cmds.cap; i++) if (g_cmds.slots[i].key) cmd_free(g_cmds.slots[i].val);
        for (size_t i = 0; i < g_layouts.cap; i++) if (g_layouts.slots[i].key) free(g_layouts.slots[i].val);
        free(g_cmds.slots); free(g_layouts.slots);
        memset(&g_cmds, 0, sizeof(g_cmds)); memset(&g_layouts, 0, sizeof(g_layouts));
        g_device = VK_NULL_HANDLE; g_emulate = 0;
    }
    pthread_mutex_unlock(&g_lock);
    real_DestroyDevice(dev, a);
}

/* ---------------- surface pre-rotation ----------------
 * Android surfaces report currentTransform = ROTATE_90/270 in landscape and expect the app to
 * pre-rotate. Minecraft (a desktop renderer) does not, so we present the surface as
 * IDENTITY-oriented with landscape extents and let the compositor rotate. */
static PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR real_GetSurfCaps;
static PFN_vkCreateSwapchainKHR real_CreateSwapchain;

static VKAPI_ATTR VkResult VKAPI_CALL shim_GetPhysicalDeviceSurfaceCapabilitiesKHR(
        VkPhysicalDevice pd, VkSurfaceKHR surf, VkSurfaceCapabilitiesKHR *caps) {
    VkResult r = real_GetSurfCaps(pd, surf, caps);
    if (r == VK_SUCCESS && (caps->currentTransform & (VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR |
                                                       VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR))) {
        uint32_t t;
        if (caps->currentExtent.width != 0xFFFFFFFFu) {
            t = caps->currentExtent.width; caps->currentExtent.width = caps->currentExtent.height; caps->currentExtent.height = t;
        }
        t = caps->minImageExtent.width; caps->minImageExtent.width = caps->minImageExtent.height; caps->minImageExtent.height = t;
        t = caps->maxImageExtent.width; caps->maxImageExtent.width = caps->maxImageExtent.height; caps->maxImageExtent.height = t;
    }
    if (r == VK_SUCCESS && (caps->supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR))
        caps->currentTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_CreateSwapchainKHR(
        VkDevice dev, const VkSwapchainCreateInfoKHR *ci, const VkAllocationCallbacks *a, VkSwapchainKHR *out) {
    VkSwapchainCreateInfoKHR c = *ci;
    c.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    return real_CreateSwapchain(dev, &c, a, out);
}

/* ---------------- device creation / capability reporting ---------------- */
static int has_native_push(VkPhysicalDevice pd) {
    uint32_t n = 0;
    real_EnumDevExt(pd, NULL, &n, NULL);
    VkExtensionProperties *p = malloc(sizeof(*p) * (n ? n : 1));
    real_EnumDevExt(pd, NULL, &n, p);
    int found = 0;
    for (uint32_t i = 0; i < n; i++) if (!strcmp(p[i].extensionName, VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME)) found = 1;
    free(p);
    return found;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_EnumerateDeviceExtensionProperties(
        VkPhysicalDevice pd, const char *layer, uint32_t *count, VkExtensionProperties *props) {
    if (layer || has_native_push(pd)) return real_EnumDevExt(pd, layer, count, props);
    uint32_t n = 0;
    real_EnumDevExt(pd, NULL, &n, NULL);
    if (!props) { *count = n + 1; return VK_SUCCESS; }
    VkExtensionProperties *all = malloc(sizeof(*all) * (n + 1));
    real_EnumDevExt(pd, NULL, &n, all);
    memset(&all[n], 0, sizeof(all[n]));
    strcpy(all[n].extensionName, VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME);
    all[n].specVersion = 2;
    uint32_t copy = *count < n + 1 ? *count : n + 1;
    memcpy(props, all, sizeof(*all) * copy);
    free(all);
    *count = copy;
    return copy < n + 1 ? VK_INCOMPLETE : VK_SUCCESS;
}

static void patch_props(VkPhysicalDevice pd, VkPhysicalDeviceProperties2 *p) {
    if (has_native_push(pd)) return;
    for (VkBaseOutStructure *s = (VkBaseOutStructure *)p->pNext; s; s = s->pNext)
        if (s->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PUSH_DESCRIPTOR_PROPERTIES_KHR)
            ((VkPhysicalDevicePushDescriptorPropertiesKHR *)s)->maxPushDescriptors = 32;
}
static VKAPI_ATTR void VKAPI_CALL shim_GetPhysicalDeviceProperties2(VkPhysicalDevice pd, VkPhysicalDeviceProperties2 *p) {
    real_GetPDProps2(pd, p); patch_props(pd, p);
}
static VKAPI_ATTR void VKAPI_CALL shim_GetPhysicalDeviceProperties2KHR(VkPhysicalDevice pd, VkPhysicalDeviceProperties2 *p) {
    real_GetPDProps2KHR(pd, p); patch_props(pd, p);
}

#define DEV(name) real_##name = (PFN_vk##name)real_GDPA(dev, "vk" #name)
static VKAPI_ATTR VkResult VKAPI_CALL shim_CreateDevice(
        VkPhysicalDevice pd, const VkDeviceCreateInfo *ci, const VkAllocationCallbacks *a, VkDevice *out) {
    int emulate = !has_native_push(pd);
    VkDeviceCreateInfo c = *ci;
    const char **names = NULL;
    if (emulate) {
        names = malloc(sizeof(char *) * (ci->enabledExtensionCount + 1));
        c.enabledExtensionCount = 0;
        for (uint32_t i = 0; i < ci->enabledExtensionCount; i++)
            if (strcmp(ci->ppEnabledExtensionNames[i], VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME))
                names[c.enabledExtensionCount++] = ci->ppEnabledExtensionNames[i];
        c.ppEnabledExtensionNames = names;
    }
    VkResult r = real_CreateDevice(pd, &c, a, out);
    free(names);
    if (r != VK_SUCCESS) return r;
    VkDevice dev = *out;
    pthread_mutex_lock(&g_lock);
    g_device = dev; g_emulate = emulate;
    DEV(DestroyDevice);
    real_CreateSwapchain = (PFN_vkCreateSwapchainKHR)real_GDPA(dev, "vkCreateSwapchainKHR");
    real_CreateDSL = (PFN_vkCreateDescriptorSetLayout)real_GDPA(dev, "vkCreateDescriptorSetLayout");
    real_CreatePL = (PFN_vkCreatePipelineLayout)real_GDPA(dev, "vkCreatePipelineLayout");
    real_DestroyPL = (PFN_vkDestroyPipelineLayout)real_GDPA(dev, "vkDestroyPipelineLayout");
    real_AllocCB = (PFN_vkAllocateCommandBuffers)real_GDPA(dev, "vkAllocateCommandBuffers");
    real_FreeCB = (PFN_vkFreeCommandBuffers)real_GDPA(dev, "vkFreeCommandBuffers");
    real_BeginCB = (PFN_vkBeginCommandBuffer)real_GDPA(dev, "vkBeginCommandBuffer");
    real_ResetCB = (PFN_vkResetCommandBuffer)real_GDPA(dev, "vkResetCommandBuffer");
    real_ResetCP = (PFN_vkResetCommandPool)real_GDPA(dev, "vkResetCommandPool");
    real_DestroyCP = (PFN_vkDestroyCommandPool)real_GDPA(dev, "vkDestroyCommandPool");
    real_CreateDP = (PFN_vkCreateDescriptorPool)real_GDPA(dev, "vkCreateDescriptorPool");
    real_DestroyDP = (PFN_vkDestroyDescriptorPool)real_GDPA(dev, "vkDestroyDescriptorPool");
    real_ResetDP = (PFN_vkResetDescriptorPool)real_GDPA(dev, "vkResetDescriptorPool");
    real_AllocDS = (PFN_vkAllocateDescriptorSets)real_GDPA(dev, "vkAllocateDescriptorSets");
    real_UpdateDS = (PFN_vkUpdateDescriptorSets)real_GDPA(dev, "vkUpdateDescriptorSets");
    real_CmdBindDS = (PFN_vkCmdBindDescriptorSets)real_GDPA(dev, "vkCmdBindDescriptorSets");
    pthread_mutex_unlock(&g_lock);
    LOGI("device created; VK_KHR_push_descriptor %s", emulate ? "EMULATED" : "native");
    return r;
}

/* ---------------- dispatch ---------------- */
typedef struct { const char *name; PFN_vkVoidFunction fn; } Hook;
#define H(n) { "vk" #n, (PFN_vkVoidFunction)shim_##n }
static const Hook device_hooks[] = {
    H(CmdPushDescriptorSetKHR), H(CreateDescriptorSetLayout), H(CreatePipelineLayout), H(DestroyPipelineLayout),
    H(AllocateCommandBuffers), H(FreeCommandBuffers), H(BeginCommandBuffer), H(ResetCommandBuffer),
    H(ResetCommandPool), H(DestroyCommandPool), H(DestroyDevice),
};

static PFN_vkVoidFunction device_hook(const char *name) {
    if (!strcmp(name, "vkCreateSwapchainKHR") && real_CreateSwapchain) return (PFN_vkVoidFunction)shim_CreateSwapchainKHR;
    if (!g_emulate) return strcmp(name, "vkDestroyDevice") ? NULL : (PFN_vkVoidFunction)shim_DestroyDevice;
    for (size_t i = 0; i < sizeof(device_hooks) / sizeof(*device_hooks); i++)
        if (!strcmp(name, device_hooks[i].name)) return device_hooks[i].fn;
    return NULL;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL shim_GetDeviceProcAddr(VkDevice dev, const char *name);

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance inst, const char *name) {
    load_real();
    if (!real_GIPA) return NULL;
    if (!strcmp(name, "vkGetInstanceProcAddr")) return (PFN_vkVoidFunction)vkGetInstanceProcAddr;
    if (!inst) return real_GIPA(inst, name);

    if (!real_CreateDevice) {
        real_GDPA = (PFN_vkGetDeviceProcAddr)real_GIPA(inst, "vkGetDeviceProcAddr");
        real_EnumDevExt = (PFN_vkEnumerateDeviceExtensionProperties)real_GIPA(inst, "vkEnumerateDeviceExtensionProperties");
        real_CreateDevice = (PFN_vkCreateDevice)real_GIPA(inst, "vkCreateDevice");
        real_GetPDProps2 = (PFN_vkGetPhysicalDeviceProperties2)real_GIPA(inst, "vkGetPhysicalDeviceProperties2");
        real_GetPDProps2KHR = (PFN_vkGetPhysicalDeviceProperties2)real_GIPA(inst, "vkGetPhysicalDeviceProperties2KHR");
        real_GetSurfCaps = (PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR)real_GIPA(inst, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
    }
    if (!strcmp(name, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR") && real_GetSurfCaps)
        return (PFN_vkVoidFunction)shim_GetPhysicalDeviceSurfaceCapabilitiesKHR;
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)shim_GetDeviceProcAddr;
    if (!strcmp(name, "vkCreateDevice")) return (PFN_vkVoidFunction)shim_CreateDevice;
    if (!strcmp(name, "vkEnumerateDeviceExtensionProperties")) return (PFN_vkVoidFunction)shim_EnumerateDeviceExtensionProperties;
    if (!strcmp(name, "vkGetPhysicalDeviceProperties2") && real_GetPDProps2) return (PFN_vkVoidFunction)shim_GetPhysicalDeviceProperties2;
    if (!strcmp(name, "vkGetPhysicalDeviceProperties2KHR") && real_GetPDProps2KHR) return (PFN_vkVoidFunction)shim_GetPhysicalDeviceProperties2KHR;
    PFN_vkVoidFunction h = device_hook(name);
    return h ? h : real_GIPA(inst, name);
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL shim_GetDeviceProcAddr(VkDevice dev, const char *name) {
    if (!strcmp(name, "vkGetDeviceProcAddr")) return (PFN_vkVoidFunction)shim_GetDeviceProcAddr;
    PFN_vkVoidFunction h = device_hook(name);
    if (h) return h;
    PFN_vkVoidFunction r = real_GDPA(dev, name);
    /* the driver has no vkCmdPushDescriptorSetKHR when we emulate; device_hook covered it */
    return r;
}

EXPORT VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice dev, const char *name) {
    load_real();
    if (!real_GDPA) real_GDPA = (PFN_vkGetDeviceProcAddr)dlsym(g_lib, "vkGetDeviceProcAddr");
    return shim_GetDeviceProcAddr(dev, name);
}
