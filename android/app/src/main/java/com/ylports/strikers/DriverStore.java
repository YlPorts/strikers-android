package com.ylports.strikers;

import android.content.Context;
import android.os.Build;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Private, bounded driver installation. Importing a ZIP never executes its libraries. */
final class DriverStore {
    static final String PREF_DRIVER = "gpu_driver";
    static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private final Context context;
    private final File root;

    static final class Driver {
        final String id, name, version, libraryName;
        final File directory;
        final int minApi;
        Driver(String id, File directory, JSONObject meta) throws Exception {
            this.id = id;
            this.directory = directory;
            name = meta.getString("name").trim();
            version = meta.optString("version", "").trim();
            libraryName = meta.getString("libraryName");
            minApi = meta.optInt("minApi", 28);
            if (name.isEmpty() || name.length() > 160 || version.length() > 160
                    || !libraryName.matches("[A-Za-z0-9_.+-]+\\.so")
                    || minApi < 0 || minApi > Build.VERSION.SDK_INT) {
                throw new IOException("El driver no es compatible con esta versión de Android.");
            }
            validateElf(new File(directory, libraryName));
        }
        String label() { return name + (version.isEmpty() ? "" : " · " + version); }
    }

    DriverStore(Context context) {
        this.context = context.getApplicationContext();
        root = new File(this.context.getFilesDir(), "gpu-drivers");
    }

    static boolean customDriversSupported() {
        return supportsCustomDrivers(Build.VERSION.SDK_INT, new File("/dev/kgsl-3d0").exists());
    }

    static boolean supportsCustomDrivers(int api, boolean hasKgsl) {
        return api >= 28 && hasKgsl;
    }

    File pendingFile() { return new File(context.getFilesDir(), "gpu-driver-pending"); }

    void markPending(String id) throws IOException {
        if (find(id) == null) throw new IOException("El driver elegido ya no está instalado.");
        try (FileOutputStream out = new FileOutputStream(pendingFile())) {
            out.write(id.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }

    String selectedId() {
        return context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString(PREF_DRIVER, "");
    }

    boolean failedPreviously(String id) {
        if (id == null || id.isEmpty() || !pendingFile().isFile()) return false;
        try { return id.equals(readSmall(pendingFile()).trim()); }
        catch (IOException e) { return true; }
    }

    void select(String id) throws IOException {
        if (!id.isEmpty() && (!customDriversSupported() || find(id) == null)) {
            throw new IOException("Este móvil usará el driver del sistema.");
        }
        // Choosing the driver again is an explicit retry after a failed startup.
        if (pendingFile().exists() && !pendingFile().delete()) {
            throw new IOException("No se pudo cambiar el driver.");
        }
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_DRIVER, id).apply();
    }

    Driver find(String id) {
        if (id == null || !id.matches("[0-9a-f-]{36}")) return null;
        try { return readDriver(id, new File(root, id)); }
        catch (Exception e) { return null; }
    }

    List<Driver> installed() {
        List<Driver> drivers = new ArrayList<>();
        File[] files = root.listFiles();
        if (files != null) for (File file : files) {
            Driver driver = find(file.getName());
            if (driver != null) drivers.add(driver);
        }
        drivers.sort(Comparator.comparing(d -> d.name));
        return drivers;
    }

    Driver importZip(InputStream input) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("No se pudo crear la carpeta de drivers.");
        String id = UUID.randomUUID().toString();
        File staging = new File(root, ".import-" + id);
        if (!staging.mkdir()) throw new IOException("No se pudo preparar la importación.");
        boolean committed = false;
        try {
            String prefix = staging.getCanonicalPath() + File.separator;
            Set<String> entries = new HashSet<>();
            long total = 0;
            try (ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry entry;
                byte[] buffer = new byte[32768];
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entries.size() >= 256 || name.isEmpty() || name.length() > 512
                            || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
                        throw new IOException("El ZIP contiene rutas no válidas.");
                    }
                    for (String part : name.split("/")) {
                        if (part.equals(".") || part.equals("..")) throw new IOException("El ZIP contiene rutas no válidas.");
                    }
                    File target = new File(staging, name);
                    String canonical = target.getCanonicalPath();
                    if (!canonical.startsWith(prefix) || !entries.add(canonical)) {
                        throw new IOException("El ZIP contiene rutas repetidas o no válidas.");
                    }
                    if (entry.isDirectory()) {
                        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("No se pudo extraer el ZIP.");
                        continue;
                    }
                    File parent = target.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("No se pudo extraer el ZIP.");
                    long size = 0;
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            size += count;
                            total += count;
                            if (size > MAX_FILE_BYTES || total > MAX_BYTES) throw new IOException("El ZIP del driver es demasiado grande.");
                            out.write(buffer, 0, count);
                        }
                        out.getFD().sync();
                    }
                    if (!target.setReadOnly()) throw new IOException("No se pudo proteger el driver importado.");
                }
            }
            // Standard Turnip archives are flat; a single enclosing directory is also accepted.
            File contents = staging;
            if (!new File(contents, "meta.json").isFile()) {
                File[] children = staging.listFiles();
                if (children != null && children.length == 1 && children[0].isDirectory()) contents = children[0];
            }
            readDriver(id, contents);
            File destination = new File(root, id);
            if (!contents.renameTo(destination)) throw new IOException("No se pudo instalar el driver.");
            committed = true;
            return readDriver(id, destination);
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("El ZIP debe contener meta.json y un driver ARM64 válido.", e); }
        finally {
            deleteTree(staging);
            if (!committed) deleteTree(new File(root, id));
        }
    }

    void remove(String id) throws IOException {
        Driver driver = find(id);
        if (driver == null) return;
        if (id.equals(selectedId())) select("");
        deleteTree(driver.directory);
        if (driver.directory.exists()) throw new IOException("No se pudo borrar el driver.");
    }

    private static Driver readDriver(String id, File directory) throws Exception {
        return new Driver(id, directory, new JSONObject(readSmall(new File(directory, "meta.json"))));
    }

    private static String readSmall(File file) throws IOException {
        if (file.length() > 65536) throw new IOException("Los metadatos del driver son demasiado grandes.");
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096];
            int count;
            while ((count = in.read(bytes)) != -1) {
                if (out.size() + count > 65536) throw new IOException("Metadatos no válidos.");
                out.write(bytes, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void validateElf(File library) throws IOException {
        byte[] header = new byte[64];
        try (InputStream in = new FileInputStream(library)) {
            int offset = 0, count;
            while (offset < header.length && (count = in.read(header, offset, header.length - offset)) > 0) offset += count;
            if (offset != 64 || header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
                    || header[4] != 2 || header[5] != 1 || header[6] != 1 || header[16] != 3 || header[17] != 0
                    || (header[18] & 255) != 183 || header[19] != 0) {
                throw new IOException("El driver debe ser una biblioteca ARM64 para Android.");
            }
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
