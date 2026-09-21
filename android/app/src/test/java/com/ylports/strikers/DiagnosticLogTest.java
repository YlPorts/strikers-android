package com.ylports.strikers;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DiagnosticLogTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    private String parse(String text) throws Exception {
        File file = directory.newFile();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return DiagnosticLog.read(file);
    }

    @Test public void recoversNativeHeaderBeforeLegacyMapDumpLargerThanTheOldTail() throws Exception {
        String text = parse("[session] match=3 cleanup begin\n[error] diagnostic before signal\n"
                + "*** STRIKERS NATIVE CRASH ***\nsignal=SIGSEGV\nsi_code=1\npc=0x701234\nlr=0x901234\nfault=0x4\n"
                + "--- /proc/self/maps ---\n"
                + "700000-800000 r-xp 00010000 fe:43 11 /data/app/game/libstrikers.so\n"
                + "900000-a00000 r-xp 00020000 fe:08 12 /system/lib64/libc.so\n"
                + "100000-200000 rw-p 00000000 00:00 0 [anon:heap]\n".repeat(25000)
                + "785605"); // exactly the kind of partial final map in the device report
        assertTrue(text.contains("signal=SIGSEGV"));
        assertTrue(text.contains("pc=0x701234"));
        assertTrue(text.contains("libstrikers.so"));
        assertTrue(text.contains("libc.so"));
        assertTrue(text.contains("match=3 cleanup begin"));
        assertTrue(text.contains("quedo incompleto"));
        assertFalse(text.contains("[anon:heap]"));
        assertTrue(text.length() < 4000);
    }

    @Test public void keepsFaultEvenWhenMemorySamplerContinuesAfterIt() throws Exception {
        String text = parse("*** STRIKERS NATIVE CRASH ***\nsignal=SIGABRT\npc=0x123\n"
                + "*** END NATIVE CRASH ***\n" + "[memdiag] rss=800000KB\n".repeat(5000));
        assertTrue(text.contains("signal=SIGABRT"));
        assertTrue(text.contains("pc=0x123"));
        assertFalse(text.contains("quedo incompleto"));
        assertTrue(text.length() < DiagnosticLog.MAX_TEXT_CHARS + 1024);
    }

    @Test public void retainsLatestFaultAndItsOwnMappings() throws Exception {
        String text = parse("*** STRIKERS NATIVE CRASH ***\nsignal=SIGABRT\npc=0x1000\n"
                + "*** END NATIVE CRASH ***\n*** STRIKERS NATIVE CRASH ***\nsignal=SIGSEGV\npc=0x3000\n"
                + "--- relevant mappings (PC/LR/fault/game) ---\n"
                + "1000-2000 r-xp 00000000 00:00 1 /old.so\n"
                + "3000-4000 r-xp 00000000 00:00 2 /current.so\n*** END NATIVE CRASH ***\n");
        assertTrue(text.contains("signal=SIGSEGV"));
        assertTrue(text.contains("/current.so"));
        assertFalse(text.contains("SIGABRT"));
        assertFalse(text.contains("/old.so"));
    }

    @Test public void boundsIndividualLinesAndRetainsFollowingError() throws Exception {
        String text = parse("x".repeat(1_000_000) + "\nJAVA CRASH on main: sample failure\n");
        assertTrue(text.contains("JAVA CRASH on main"));
        assertTrue(text.contains("linea recortada"));
        assertTrue(text.length() < 8192);
    }

    @Test public void acceptsUnsignedAddressesAndFaultAddressMappings() throws Exception {
        String text = parse("*** STRIKERS NATIVE CRASH ***\npc=0xffff000000001234\nfault=0x5fff\n"
                + "ffff000000000000-ffff000000004000 r-xp 00000000 00:00 1 /high.so\n"
                + "5000-6000 rw-p 00000000 00:00 0 [anon:fault-region]\n");
        assertTrue(text.contains("/high.so"));
        assertTrue(text.contains("[anon:fault-region]"));
    }

    @Test public void preservesUsefulNormalLogAndMissingFileIsEmpty() throws Exception {
        assertTrue(parse("123ms pid=1 launcher: ready\n[session] match=1\n").contains("match=1"));
        assertEquals("", DiagnosticLog.read(new File(directory.getRoot(), "missing")));
    }

    @Test public void hugeLogsHaveAnExplicitScanLimitWithoutHidingRecentErrors() throws Exception {
        String text = parse("x".repeat(DiagnosticLog.MAX_SCAN_BYTES + 1024)
                + "\n*** STRIKERS NATIVE CRASH ***\nsignal=SIGABRT\npc=0x1234\n");
        assertTrue(text.contains("ultimos 16 MiB"));
        assertTrue(text.contains("signal=SIGABRT"));
        assertTrue(text.contains("pc=0x1234"));
    }
}
