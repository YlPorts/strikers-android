package com.ylports.strikers;

import android.content.Context;
import android.system.Os;
import java.io.File;
import java.io.IOException;

/** Called only in :game, before the renderer starts. System mode never loads our loader. */
final class DriverRuntime {
    static final String EXTRA_DRIVER = "com.ylports.strikers.DRIVER";
    private static String activeId = "";

    static void prepare(Context context, String selectedId) {
        activeId = "";
        try {
            for (String key : new String[]{"STRIKERS_VULKAN_PATH", "STRIKERS_DRIVER_DIR",
                    "STRIKERS_DRIVER_LIBRARY", "STRIKERS_DRIVER_HOOKS", "STRIKERS_DRIVER_TMP",
                    "STRIKERS_DRIVER_ID", "STRIKERS_DRIVER_PENDING", "STRIKERS_CUSTOM_DRIVER_FAILED"}) {
                Os.unsetenv(key);
            }
            DriverStore store = new DriverStore(context);
            DriverStore.Driver driver = store.find(selectedId);
            if (!DriverStore.customDriversSupported() || driver == null || store.failedPreviously(selectedId)) {
                RunLog.append(context, "graphics: system driver (default, unavailable or previous startup failed)");
                return;
            }
            File runtime = new File(context.getCodeCacheDir(), "vulkan-loader");
            if (!runtime.isDirectory() && !runtime.mkdirs()) throw new IOException("loader directory");
            File loader = new File(context.getApplicationInfo().nativeLibraryDir, "libstrikers_vulkan.so");
            if (!loader.isFile()) throw new IOException("packaged loader missing");
            File alias = new File(runtime, "libvulkan.so");
            // Only alias the read-only loader shipped in our APK. Imported libraries stay private.
            java.nio.file.Files.deleteIfExists(alias.toPath());
            Os.symlink(loader.getAbsolutePath(), alias.getAbsolutePath());
            Os.setenv("STRIKERS_DRIVER_DIR", driver.directory.getAbsolutePath() + "/", true);
            Os.setenv("STRIKERS_DRIVER_LIBRARY", driver.libraryName, true);
            Os.setenv("STRIKERS_DRIVER_HOOKS", context.getApplicationInfo().nativeLibraryDir, true);
            Os.setenv("STRIKERS_DRIVER_TMP", runtime.getAbsolutePath(), true);
            Os.setenv("STRIKERS_DRIVER_ID", driver.id, true);
            Os.setenv("STRIKERS_DRIVER_PENDING", store.pendingFile().getAbsolutePath(), true);
            // Set last: a partial setup must never redirect Dawn away from the system loader.
            Os.setenv("STRIKERS_VULKAN_PATH", runtime.getAbsolutePath() + "/", true);
            activeId = driver.id;
            RunLog.append(context, "graphics: custom driver selected: " + driver.label());
        } catch (Exception e) {
            try { Os.unsetenv("STRIKERS_VULKAN_PATH"); Os.unsetenv("STRIKERS_DRIVER_ID"); }
            catch (android.system.ErrnoException ignored) { }
            RunLog.append(context, "graphics: using system driver after setup failure: " + e.getClass().getSimpleName());
        }
    }

    static void armStartupGuard(Context context) {
        if (activeId.isEmpty()) return;
        try {
            // Before dlopen, including dependency constructors; after disc validation.
            new DriverStore(context).markPending(activeId);
        } catch (Exception e) {
            activeId = "";
            try { Os.unsetenv("STRIKERS_VULKAN_PATH"); Os.unsetenv("STRIKERS_DRIVER_ID"); }
            catch (android.system.ErrnoException ignored) { }
            RunLog.append(context, "graphics: system driver; startup guard could not be prepared");
        }
    }
}
