package com.ylports.strikers;

import android.app.Application;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, application = Application.class)
public class SaveFolderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Context context;
    private FakeDocuments provider;
    private SaveFolder selected;
    private File internal;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        provider = new FakeDocuments();
        provider.disk = temporary.newFolder("provider");
        new File(provider.disk, "selected").mkdirs();
        new File(provider.disk, "previous").mkdirs();
        ProviderInfo info = new ProviderInfo();
        info.authority = "strikers.saves.test";
        info.exported = true;
        info.grantUriPermissions = true;
        info.readPermission = "android.permission.MANAGE_DOCUMENTS";
        info.writePermission = "android.permission.MANAGE_DOCUMENTS";
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        selected = folder("selected");
        internal = temporary.newFolder("internal");
    }

    private SaveFolder folder(String name) {
        return new SaveFolder(context, DocumentsContract.buildTreeDocumentUri("strikers.saves.test", name));
    }

    private void write(File root, String path, byte[] data) throws IOException {
        File file = new File(root, path);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), data);
    }

    @Test public void emptyFolderCopiesInternalProgressAndKeepsOriginal() throws Exception {
        byte[] save = new byte[16448];
        Arrays.fill(save, (byte) 37);
        write(internal, "USA/Card A/01-G4QE-Strikers.gci", save);
        selected.copyIfEmpty(internal, null);
        selected.prepareDirectories();
        assertArrayEquals(save, Files.readAllBytes(new File(provider.disk,
                "selected/USA/Card A/01-G4QE-Strikers.gci").toPath()));
        assertArrayEquals(save, Files.readAllBytes(new File(internal,
                "USA/Card A/01-G4QE-Strikers.gci").toPath()));
        assertEquals(1, selected.list("USA/Card A").length);
        assertEquals(0, selected.list("EUR/Card B").length);
    }

    @Test public void selectedProgressIsNeverOverwrittenByInternalProgress() throws Exception {
        write(internal, "USA/Card A/progress.gci", new byte[]{1, 2, 3});
        write(provider.disk, "selected/USA/Card A/progress.gci", new byte[]{7, 8, 9});
        selected.copyIfEmpty(internal, null);
        assertArrayEquals(new byte[]{7, 8, 9}, Files.readAllBytes(new File(provider.disk,
                "selected/USA/Card A/progress.gci").toPath()));
    }

    @Test public void switchingToEmptyFolderCopiesActiveFolderRatherThanOlderInternalSave() throws Exception {
        write(internal, "USA/Card A/progress.gci", new byte[]{1});
        write(provider.disk, "previous/USA/Card A/progress.gci", new byte[]{9});
        selected.copyIfEmpty(internal, folder("previous"));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(new File(provider.disk,
                "selected/USA/Card A/progress.gci").toPath()));
        assertTrue(new File(provider.disk, "previous/USA/Card A/progress.gci").exists());
    }

    @Test public void failedCopyRollsBackDestinationAndKeepsSourceForRetry() throws Exception {
        write(internal, "USA/Card A/progress.gci", new byte[]{4, 5});
        provider.denyWrites = true;
        assertThrows(IOException.class, () -> selected.copyIfEmpty(internal, null));
        assertEquals(0, selected.list("USA/Card A").length);
        assertArrayEquals(new byte[]{4, 5}, Files.readAllBytes(new File(internal,
                "USA/Card A/progress.gci").toPath()));
        provider.denyWrites = false;
        selected.copyIfEmpty(internal, null);
        assertEquals(1, selected.list("USA/Card A").length);
    }

    @Test public void deletionIsScopedAndDoesNotTruncateUnrelatedFiles() throws Exception {
        write(provider.disk, "selected/USA/Card A/first.gci", new byte[]{1});
        write(provider.disk, "selected/USA/Card A/second.gci", new byte[]{2, 3});
        assertTrue(selected.delete("USA/Card A/first.gci"));
        assertFalse(selected.delete("USA/Card A/first.gci"));
        assertArrayEquals(new byte[]{2, 3}, Files.readAllBytes(new File(provider.disk,
                "selected/USA/Card A/second.gci").toPath()));
    }

    @Test public void pathsCannotEscapeSelectedCardsAndNonSaveFilesAreIgnored() throws Exception {
        write(provider.disk, "selected/USA/Card A/progress.gci", new byte[]{1});
        write(provider.disk, "selected/USA/Card A/photo.jpg", new byte[]{2});
        assertArrayEquals(new String[]{"progress.gci"}, selected.list("USA/Card A"));
        assertThrows(IOException.class, () -> selected.mkdir("../outside"));
        assertThrows(IOException.class, () -> selected.open("USA/Card A/../../other.gci", 2));
        assertThrows(IOException.class, () -> selected.delete("USA/Card A/photo.jpg"));
    }

    @Test public void newSessionReopensTheSameFolderAndRevokedAccessFails() throws Exception {
        write(provider.disk, "selected/USA/Card A/progress.gci", new byte[]{1});
        assertEquals(1, folder("selected").list("USA/Card A").length);
        provider.revoked = true;
        assertThrows(SecurityException.class, () -> folder("selected").list("USA/Card A"));
    }

    @Test public void readOnlyFolderFailsValidationAndRemovesItsProbe() throws Exception {
        provider.denyWrites = true;
        assertThrows(IOException.class, () -> selected.validate());
        assertEquals(0, new File(provider.disk, "selected").list().length);
    }

    public static final class FakeDocuments extends DocumentsProvider {
        File disk;
        boolean denyWrites, revoked;
        private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE};
        @Override public boolean onCreate() { return true; }
        private File file(String id) throws FileNotFoundException {
            if (revoked) throw new SecurityException("Revoked");
            File result = new File(disk, id);
            try {
                if (!result.getCanonicalPath().startsWith(disk.getCanonicalPath() + File.separator))
                    throw new FileNotFoundException("Outside tree");
            } catch (IOException e) { throw new FileNotFoundException(e.toString()); }
            return result;
        }
        private void row(MatrixCursor cursor, String id, File file) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : cursor.getColumnNames()) {
                switch (column) {
                    case Document.COLUMN_DOCUMENT_ID: row.add(id); break;
                    case Document.COLUMN_DISPLAY_NAME: row.add(file.getName()); break;
                    case Document.COLUMN_MIME_TYPE: row.add(file.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream"); break;
                    case Document.COLUMN_FLAGS: row.add(Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_DIR_SUPPORTS_CREATE); break;
                    case Document.COLUMN_SIZE: row.add(file.length()); break;
                    default: row.add(null);
                }
            }
        }
        @Override public Cursor queryRoots(String[] projection) { return new MatrixCursor(new String[]{"root_id"}); }
        @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
            MatrixCursor result = new MatrixCursor(projection == null ? COLUMNS : projection);
            File file = file(id);
            if (file.exists()) row(result, id, file);
            return result;
        }
        @Override public Cursor queryChildDocuments(String id, String[] projection, String order) throws FileNotFoundException {
            MatrixCursor result = new MatrixCursor(projection == null ? COLUMNS : projection);
            File[] files = file(id).listFiles();
            if (files == null) throw new FileNotFoundException(id);
            for (File child : files) row(result, id + "/" + child.getName(), child);
            return result;
        }
        @Override public boolean isChildDocument(String parent, String child) { return child.startsWith(parent + "/"); }
        @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
            String id = parent + "/" + name;
            try {
                File target = file(id);
                if (target.exists()) throw new IOException("Already exists");
                if (Document.MIME_TYPE_DIR.equals(mime)) {
                    if (!target.mkdir()) throw new IOException("mkdir failed");
                } else if (!target.createNewFile()) throw new IOException("create failed");
                return id;
            } catch (IOException e) { throw new FileNotFoundException(e.toString()); }
        }
        @Override public void deleteDocument(String id) throws FileNotFoundException {
            if (!file(id).delete()) throw new FileNotFoundException(id);
        }
        @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
            if (denyWrites && mode.contains("w")) throw new FileNotFoundException("Write denied");
            return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
        }
    }
}
