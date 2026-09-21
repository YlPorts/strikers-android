package com.ylports.strikers;

import android.app.Application;
import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, application = Application.class)
public class SessionDiagnosticsTest {
    @Test public void longSessionKeepsRecentSamplesWithinTheFileBudget() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        SessionDiagnostics.file(context).delete();
        for (int i = 0; i < 2000; i++) {
            SessionDiagnostics.append(context, "frame=" + i + " phase=2 present_age_ms=1000");
            assertTrue(SessionDiagnostics.file(context).length() <= SessionDiagnostics.MAX_FILE_BYTES);
        }
        String text = new String(Files.readAllBytes(SessionDiagnostics.file(context).toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("frame=1999"));
        assertFalse(text.contains("frame=0 "));
        assertTrue(text.contains("clock=monotonic pid=" + android.os.Process.myPid()));
        SessionDiagnostics.append(context, "x".repeat(4096));
        assertEquals(text, new String(Files.readAllBytes(SessionDiagnostics.file(context).toPath()), StandardCharsets.UTF_8));
    }

    @Test public void deviceStateWorksWithoutThermalApiOnOlderAndroid() {
        String state = SessionDiagnostics.deviceState(RuntimeEnvironment.getApplication());
        assertTrue(state.contains("thermal_status="));
        assertTrue(state.contains("available_mb="));
        assertTrue(state.contains("battery_tenths_C="));
    }
}
