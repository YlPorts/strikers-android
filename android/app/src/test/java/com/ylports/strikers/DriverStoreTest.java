package com.ylports.strikers;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, application = Application.class)
public class DriverStoreTest {
    private Context context;
    private DriverStore store;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        store = new DriverStore(context);
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private byte[] elf() {
        byte[] data = new byte[64];
        data[0] = 0x7f; data[1] = 'E'; data[2] = 'L'; data[3] = 'F';
        data[4] = 2; data[5] = 1; data[6] = 1; data[16] = 3; data[18] = (byte) 183;
        return data;
    }
    private Map<String, byte[]> files(String prefix, int api) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(prefix + "meta.json", ("{\"name\":\"Test driver\",\"version\":\"1\",\"minApi\":" + api
                + ",\"libraryName\":\"driver.so\"}").getBytes(StandardCharsets.UTF_8));
        files.put(prefix + "driver.so", elf());
        return files;
    }
    private DriverStore.Driver install(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return store.importZip(new ByteArrayInputStream(bytes.toByteArray()));
    }
    private void reject(Map<String, byte[]> files) throws Exception {
        try { install(files); fail("Invalid driver archive accepted"); }
        catch (IOException expected) { }
    }

    @Test public void importPreservesSystemSelectionAndReloadsMetadata() throws Exception {
        DriverStore.Driver imported = install(files("", 26));
        assertEquals("", store.selectedId());
        DriverStore.Driver reloaded = new DriverStore(context).find(imported.id);
        assertNotNull(reloaded);
        assertEquals("Test driver · 1", reloaded.label());
        assertTrue(reloaded.directory.getCanonicalPath().startsWith(context.getFilesDir().getCanonicalPath()));
        assertTrue(new File(reloaded.directory, "driver.so").isFile());
    }

    @Test public void enclosingDirectoryAndDeletingDriverWork() throws Exception {
        DriverStore.Driver imported = install(files("turnip/", 26));
        assertNotNull(store.find(imported.id));
        store.remove(imported.id);
        assertNull(store.find(imported.id));
        assertFalse(imported.directory.exists());
    }

    @Test public void traversalCannotOverwriteFilesOutsideInstallation() throws Exception {
        Map<String, byte[]> files = files("", 26);
        files.put("../../outside", new byte[]{1});
        reject(files);
        assertFalse(new File(context.getFilesDir(), "outside").exists());
        assertTrue(store.installed().isEmpty());
    }

    @Test public void badArchitectureAndTooNewAndroidPreserveExistingDriver() throws Exception {
        DriverStore.Driver good = install(files("", 26));
        Map<String, byte[]> invalid = files("", 26);
        invalid.get("driver.so")[18] = 62; // x86-64, not ARM64
        reject(invalid);
        reject(files("", Build.VERSION.SDK_INT + 1));
        assertNotNull(store.find(good.id));
        assertEquals(1, store.installed().size());
    }

    @Test public void metadataCannotChooseLibraryOutsideDriverDirectory() throws Exception {
        Map<String, byte[]> invalid = files("", 26);
        invalid.put("meta.json", "{\"name\":\"Bad\",\"minApi\":26,\"libraryName\":\"../driver.so\"}".getBytes(StandardCharsets.UTF_8));
        reject(invalid);
        assertNull(store.find("../escape"));
    }

    @Test public void entryLimitRejectsArchiveAndCleansStaging() throws Exception {
        Map<String, byte[]> invalid = files("", 26);
        for (int i = 0; i < 256; i++) invalid.put("extra" + i, new byte[0]);
        reject(invalid);
        assertEquals(0, new File(context.getFilesDir(), "gpu-drivers").list().length);
    }

    @Test public void unsupportedDevicesCannotActivateCustomDrivers() throws Exception {
        assertFalse(DriverStore.supportsCustomDrivers(26, true));
        assertFalse(DriverStore.supportsCustomDrivers(35, false));
        assertTrue(DriverStore.supportsCustomDrivers(28, true));
        DriverStore.Driver driver = install(files("", 26));
        try { store.select(driver.id); fail("Host has no KGSL device"); }
        catch (IOException expected) { }
        assertEquals("", store.selectedId());
        store.select("");
    }

    @Test public void systemSelectionClearsFailedStartupWithoutRemovingSaves() throws Exception {
        DriverStore.Driver driver = install(files("", 26));
        File save = new File(context.getFilesDir(), "existing-save.gci");
        Files.write(save.toPath(), new byte[]{7, 8, 9});
        store.markPending(driver.id);
        assertTrue(store.failedPreviously(driver.id));
        assertFalse(store.failedPreviously(""));
        store.select("");
        assertFalse(store.failedPreviously(driver.id));
        assertArrayEquals(new byte[]{7, 8, 9}, Files.readAllBytes(save.toPath()));
        assertNotNull(store.find(driver.id));
    }
}
