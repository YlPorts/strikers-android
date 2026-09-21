package com.ylports.strikers;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recovers the fault header even from the large, incomplete maps dumps made by 1.5.7. */
final class DiagnosticLog {
    static final int MAX_SCAN_BYTES = 16 * 1024 * 1024;
    static final int MAX_TEXT_CHARS = 24 * 1024;
    private static final int MAX_LINE_CHARS = 4096;
    private static final Pattern MAP = Pattern.compile("^([0-9a-f]+)-([0-9a-f]+) [rwxps-]{4} .*");
    private static final Pattern REGISTER = Pattern.compile("^(pc|lr|fault)=0x([0-9a-f]+)$");

    private final StringBuilder tail = new StringBuilder();
    private final StringBuilder fault = new StringBuilder();
    private final StringBuilder maps = new StringBuilder();
    private final long[] addresses = new long[3];
    private String beforeFault = "";
    private boolean hasFault;
    private boolean inMaps;
    private boolean skippedPrefix;

    private DiagnosticLog() { }

    static String read(File file) {
        if (file == null || !file.isFile()) return "";
        DiagnosticLog log = new DiagnosticLog();
        try (FileInputStream stream = new FileInputStream(file)) {
            long start = Math.max(0, stream.getChannel().size() - MAX_SCAN_BYTES);
            stream.getChannel().position(start);
            log.skippedPrefix = start > 0;
            // Do not chase a file that another process is still appending to.
            int remaining = MAX_SCAN_BYTES;
            boolean skipPartialLine = start > 0;
            Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            char[] buffer = new char[4096];
            StringBuilder line = new StringBuilder();
            boolean oversized = false;
            while (remaining > 0) {
                int count = reader.read(buffer, 0, Math.min(buffer.length, remaining));
                if (count < 0) break;
                remaining -= count;
                for (int i = 0; i < count; i++) {
                    char c = buffer[i];
                    if (c == '\n') {
                        if (!skipPartialLine) log.accept(line.toString() + (oversized ? " [linea recortada]" : ""));
                        skipPartialLine = false;
                        oversized = false;
                        line.setLength(0);
                    } else if (!skipPartialLine && line.length() < MAX_LINE_CHARS) {
                        if (c != '\r') line.append(c);
                    } else {
                        oversized = true;
                    }
                }
            }
            if (!skipPartialLine && line.length() > 0) log.accept(line.toString());
        } catch (IOException ignored) { }
        return log.text();
    }

    private void accept(String line) {
        if (line.contains("*** STRIKERS NATIVE CRASH ***")) {
            hasFault = true;
            inMaps = false;
            fault.setLength(0);
            maps.setLength(0);
            for (int i = 0; i < addresses.length; i++) addresses[i] = 0;
            beforeFault = tail.substring(Math.max(0, tail.length() - 6000));
            tail.setLength(0);
            fault.append(line).append('\n');
            return;
        }
        if (line.startsWith("--- /proc/self/maps") || line.startsWith("--- relevant mappings")) {
            inMaps = true;
            return;
        }
        if (line.startsWith("--- end maps") || line.startsWith("*** END NATIVE CRASH")) {
            inMaps = false;
            return;
        }
        Matcher mapping = MAP.matcher(line);
        if (mapping.matches()) {
            if (hasFault && maps.length() + line.length() < 8 * 1024 && relevant(mapping, line))
                maps.append(line).append('\n');
            return;
        }
        if (hasFault && line.matches("^(signal|signal_number|si_code|pid|tid|monotonic_ms|fault|pc|lr|sp|fp/x29|x[0-7])=.*")) {
            if (fault.length() + line.length() < 4096) fault.append(line).append('\n');
            Matcher register = REGISTER.matcher(line);
            if (register.matches()) {
                try {
                    int index = register.group(1).equals("pc") ? 0 : register.group(1).equals("lr") ? 1 : 2;
                    addresses[index] = Long.parseUnsignedLong(register.group(2), 16);
                } catch (NumberFormatException ignored) { }
            }
            return;
        }
        // Memory samples and Java lifecycle lines can interleave a native dump.
        if (inMaps && !line.startsWith("[memdiag]") && !line.matches("^\\d+ms .*")) return;
        if (line.startsWith("note=Android debuggerd/")) return; // obsolete 1.5.7 claim
        tail.append(line).append('\n');
        if (tail.length() > MAX_TEXT_CHARS) tail.delete(0, tail.length() - MAX_TEXT_CHARS);
    }

    private boolean relevant(Matcher mapping, String line) {
        if (line.contains("/libstrikers.so") || line.contains("/libSDL3.so") || line.contains("/libstrikers_diag.so")) return true;
        try {
            long start = Long.parseUnsignedLong(mapping.group(1), 16);
            long end = Long.parseUnsignedLong(mapping.group(2), 16);
            for (long address : addresses) {
                if (address != 0 && Long.compareUnsigned(address, start) >= 0 && Long.compareUnsigned(address, end) < 0) return true;
            }
        } catch (NumberFormatException ignored) { }
        return false;
    }

    private String text() {
        StringBuilder out = new StringBuilder();
        if (skippedPrefix) out.append("[Se analizaron los ultimos 16 MiB del registro.]\n");
        if (hasFault) {
            out.append(fault);
            out.append("--- mapas relevantes ---\n").append(maps);
            if (inMaps) out.append("[El volcado original de mapas quedo incompleto.]\n");
            out.append("--- antes del fallo ---\n").append(beforeFault);
        }
        out.append(tail);
        return out.toString();
    }
}
