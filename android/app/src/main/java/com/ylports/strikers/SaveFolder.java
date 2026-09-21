package com.ylports.strikers;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Direct, seekable SAF access. No polling, delayed exports or dependence on a clean exit. */
final class SaveFolder {
    private static final String[] REGIONS = {"USA", "EUR", "JAP"};
    private static final String[] SLOTS = {"Card A", "Card B"};
    private final ContentResolver resolver;
    private final Uri tree;
    private final Uri root;
    private final Map<String, Uri> paths = new HashMap<>();

    SaveFolder(Context context, Uri tree) {
        if (!DocumentsContract.isTreeUri(tree)) throw new IllegalArgumentException("Selecciona una carpeta.");
        this.resolver = context.getContentResolver();
        this.tree = tree;
        this.root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        paths.put("", root);
    }

    private static String checkedPath(String path) throws IOException {
        if (path == null) throw new IOException("Ruta de partida vacía.");
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty()) return path;
        String[] parts = path.split("/", -1);
        if (parts.length > 3 || !(parts[0].equals("USA") || parts[0].equals("EUR") || parts[0].equals("JAP")))
            throw new IOException("Región de partida inválida.");
        if (parts.length > 1 && !(parts[1].equals("Card A") || parts[1].equals("Card B")))
            throw new IOException("Tarjeta de partida inválida.");
        if (parts.length == 3 && !saveName(parts[2])) throw new IOException("Nombre de partida inválido.");
        return path;
    }

    private static boolean saveName(String name) {
        return name != null && name.endsWith(".gci") && !name.contains("/") && !name.contains("\\")
                && name.indexOf('\0') < 0 && name.length() <= 255;
    }

    private List<Entry> children(Uri parent) throws IOException {
        Uri query = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
        List<Entry> entries = new ArrayList<>();
        try (Cursor cursor = resolver.query(query, new String[]{Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE}, Bundle.EMPTY, null)) {
            if (cursor == null) throw new IOException("No se pudo leer la carpeta de partidas.");
            while (cursor.moveToNext()) entries.add(new Entry(cursor.getString(1),
                    Document.MIME_TYPE_DIR.equals(cursor.getString(2)),
                    DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))));
        }
        return entries;
    }

    private Uri resolve(String path, boolean create, boolean directory) throws IOException {
        path = checkedPath(path);
        Uri known = paths.get(path);
        if (known != null) return known;
        int slash = path.lastIndexOf('/');
        Uri parent = resolve(slash < 0 ? "" : path.substring(0, slash), create, true);
        if (parent == null) return null;
        String name = path.substring(slash + 1);
        for (Entry entry : children(parent)) {
            if (name.equals(entry.name)) {
                if (directory != entry.directory) throw new IOException("La ruta contiene un archivo incompatible: " + name);
                paths.put(path, entry.uri);
                return entry.uri;
            }
        }
        if (!create) return null;
        Uri result = DocumentsContract.createDocument(resolver, parent,
                directory ? Document.MIME_TYPE_DIR : "application/octet-stream", name);
        if (result == null) throw new IOException("No se pudo crear: " + name);
        paths.put(path, result);
        return result;
    }

    synchronized String[] list(String directory) throws IOException {
        directory = checkedPath(directory);
        Uri parent = resolve(directory, false, true);
        if (parent == null) return new String[0];
        List<String> names = new ArrayList<>();
        for (Entry entry : children(parent)) {
            if (!entry.directory && saveName(entry.name)) {
                names.add(entry.name);
                paths.put(directory + "/" + entry.name, entry.uri);
            }
        }
        return names.toArray(new String[0]);
    }

    synchronized boolean mkdir(String directory) throws IOException {
        return resolve(directory, true, true) != null;
    }

    // A cached document URI is not proof that the card still exists or is accessible.
    synchronized boolean isDirectory(String directory) throws IOException {
        Uri uri = resolve(directory, false, true);
        if (uri == null) return false;
        try (Cursor cursor = resolver.query(uri, new String[]{Document.COLUMN_MIME_TYPE}, Bundle.EMPTY, null)) {
            if (cursor == null) throw new IOException("No se pudo comprobar la tarjeta.");
            return cursor.moveToFirst() && Document.MIME_TYPE_DIR.equals(cursor.getString(0));
        }
    }

    // Called by JNI. Ownership of the descriptor passes to native code.
    synchronized int open(String path, int mode) throws IOException {
        Uri uri = resolve(path, mode == 2, false);
        if (uri == null) throw new IOException("La partida no existe.");
        ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, mode == 0 ? "r" : mode == 2 ? "rwt" : "rw");
        if (descriptor == null) throw new IOException("No se pudo abrir la partida.");
        try {
            return descriptor.detachFd();
        } finally {
            descriptor.close();
        }
    }

    synchronized boolean delete(String path) throws IOException {
        Uri uri = resolve(path, false, false);
        if (uri == null) return false;
        boolean deleted = DocumentsContract.deleteDocument(resolver, uri);
        if (deleted) paths.remove(checkedPath(path));
        return deleted;
    }

    String displayName() throws IOException {
        try (Cursor cursor = resolver.query(root, new String[]{Document.COLUMN_DISPLAY_NAME}, Bundle.EMPTY, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        }
        throw new IOException("No se pudo leer el nombre de la carpeta.");
    }

    /** Reject revoked, read-only or streaming-only providers before the game can write a save. */
    void validate() throws Exception {
        Uri probe = DocumentsContract.createDocument(resolver, root, "application/octet-stream",
                ".strikers-check-" + UUID.randomUUID());
        if (probe == null) throw new IOException("La carpeta no permite guardar archivos.");
        try (ParcelFileDescriptor fd = resolver.openFileDescriptor(probe, "rw")) {
            if (fd == null) throw new IOException("No se pudo comprobar la carpeta.");
            byte[] marker = {83, 77, 83};
            if (Os.write(fd.getFileDescriptor(), marker, 0, marker.length) != marker.length)
                throw new IOException("Escritura incompleta en la carpeta.");
            Os.lseek(fd.getFileDescriptor(), 0, OsConstants.SEEK_SET);
            byte[] read = new byte[marker.length];
            if (Os.read(fd.getFileDescriptor(), read, 0, read.length) != read.length
                    || !java.util.Arrays.equals(marker, read)) throw new IOException("La carpeta no permite leer las partidas.");
            Os.fsync(fd.getFileDescriptor());
        } finally {
            DocumentsContract.deleteDocument(resolver, probe);
        }
    }

    void prepareDirectories() throws IOException {
        for (String region : REGIONS) for (String slot : SLOTS) mkdir(region + "/" + slot);
    }

    /** An existing destination wins; an empty one inherits the current saves. Never delete the source. */
    void copyIfEmpty(File internalRoot, SaveFolder previous) throws IOException {
        for (String region : REGIONS) for (String slot : SLOTS)
            if (list(region + "/" + slot).length != 0) return;
        List<String> created = new ArrayList<>();
        try {
            for (String region : REGIONS) for (String slot : SLOTS) {
                String directory = region + "/" + slot;
                String[] names = previous == null
                        ? new File(internalRoot, directory).list((dir, name) -> saveName(name)) : previous.list(directory);
                if (names == null) continue;
                for (String name : names) {
                    String path = directory + "/" + name;
                    Uri destination = resolve(path, true, false);
                    created.add(path);
                    try (InputStream input = previous == null ? new FileInputStream(new File(internalRoot, path))
                            : previous.resolver.openInputStream(previous.resolve(path, false, false));
                         OutputStream output = resolver.openOutputStream(destination, "wt")) {
                        if (input == null || output == null) throw new IOException("No se pudo copiar la partida.");
                        byte[] buffer = new byte[32768];
                        int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        output.flush();
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            // Only files created by this operation are removed on a failed migration.
            for (String path : created) {
                try { delete(path); } catch (Exception ignored) { }
            }
            throw failure;
        }
    }

    private static final class Entry {
        final String name;
        final boolean directory;
        final Uri uri;
        Entry(String name, boolean directory, Uri uri) {
            this.name = name;
            this.directory = directory;
            this.uri = uri;
        }
    }
}
