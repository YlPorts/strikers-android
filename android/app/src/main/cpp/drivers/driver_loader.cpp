// Optional per-application Vulkan loader. Original Strikers Android integration.
// libadrenotools itself is BSD-2-Clause; see assets/licenses/adrenotools.txt.
#include <adrenotools/driver.h>
#include <android/log.h>
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <unistd.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>

namespace {
std::once_flag once;
PFN_vkGetInstanceProcAddr getProc = nullptr;
void* driverHandle = nullptr; // Keep the chosen loader mapped for the process lifetime.

void log(const char* message) {
    __android_log_print(ANDROID_LOG_INFO, "StrikersDriver", "%s", message);
    std::fprintf(stderr, "[driver] %s\n", message);
}

bool probe(PFN_vkGetInstanceProcAddr proc) {
    if (!proc) return false;
    const auto create = reinterpret_cast<PFN_vkCreateInstance>(proc(nullptr, "vkCreateInstance"));
    if (!create) return false;
    const VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO, nullptr, "Strikers driver probe",
                                1, nullptr, 0, VK_API_VERSION_1_1};
    const VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, nullptr, 0, &app, 0,
                                   nullptr, 0, nullptr};
    VkInstance instance = VK_NULL_HANDLE;
    if (create(&info, nullptr, &instance) != VK_SUCCESS || !instance) return false;
    const auto enumerate = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(proc(instance, "vkEnumeratePhysicalDevices"));
    const auto destroy = reinterpret_cast<PFN_vkDestroyInstance>(proc(instance, "vkDestroyInstance"));
    uint32_t count = 0;
    const bool available = enumerate && destroy && enumerate(instance, &count, nullptr) == VK_SUCCESS && count > 0;
    if (destroy) destroy(instance, nullptr);
    return available;
}

void initialize() {
    const char* directory = std::getenv("STRIKERS_DRIVER_DIR");
    const char* library = std::getenv("STRIKERS_DRIVER_LIBRARY");
    const char* hooks = std::getenv("STRIKERS_DRIVER_HOOKS");
    const char* temporary = std::getenv("STRIKERS_DRIVER_TMP");
    const char* id = std::getenv("STRIKERS_DRIVER_ID");
    const char* pending = std::getenv("STRIKERS_DRIVER_PENDING");
    if (directory && *directory && library && *library && hooks && *hooks && temporary && id && pending) {
        // An interrupted startup is retried with the system driver on the next launch.
        // Aurora removes the marker only after a successful first presentation.
        bool marked = false;
        if (FILE* marker = std::fopen(pending, "w")) {
            marked = std::fputs(id, marker) >= 0 && std::fflush(marker) == 0 && fsync(fileno(marker)) == 0;
            if (std::fclose(marker) != 0) marked = false;
        }
        if (marked) {
            log("Loading the selected custom driver");
            void* custom = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM,
                                                      temporary, hooks, directory, library, nullptr, nullptr);
            auto proc = custom ? reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(custom, "vkGetInstanceProcAddr")) : nullptr;
            if (probe(proc)) {
                driverHandle = custom;
                getProc = proc;
                log("Custom Vulkan driver probe succeeded");
                return;
            }
            // Do not dlclose a failed third-party driver: it may have started background work.
        }
        setenv("STRIKERS_CUSTOM_DRIVER_FAILED", "1", 1);
        log("Custom driver unavailable; falling back to the system driver");
    }
    driverHandle = dlopen("/system/lib64/libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (driverHandle) getProc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(driverHandle, "vkGetInstanceProcAddr"));
    log(getProc ? "System Vulkan driver ready" : "System Vulkan loader could not be opened");
}
}

extern "C" __attribute__((visibility("default"))) VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL
vkGetInstanceProcAddr(VkInstance instance, const char* name) {
    std::call_once(once, initialize);
    return getProc ? getProc(instance, name) : nullptr;
}
