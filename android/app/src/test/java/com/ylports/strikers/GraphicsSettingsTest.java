package com.ylports.strikers;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, application = Application.class)
public class GraphicsSettingsTest {
    @Test public void existingDefaultsSurviveWithoutDriverInstallation() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        Intent game = new Intent();
        GraphicsSettings.putLaunchExtras(game, prefs);
        assertEquals("vulkan", game.getStringExtra(GraphicsSettings.BACKEND));
        assertEquals(1, game.getIntExtra(GraphicsSettings.MSAA, -1));
        assertEquals(16, game.getIntExtra(GraphicsSettings.ANISO, -1));
        assertEquals("auto", game.getStringExtra(GraphicsSettings.ASPECT));
        assertTrue(game.getBooleanExtra(GraphicsSettings.VSYNC, false));
        assertFalse(game.getBooleanExtra(GraphicsSettings.STATS, true));
    }

    @Test public void graphicsAreValidatedAndCopiedBeforeCrossProcessLaunch() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        prefs.edit().clear().putInt(GraphicsSettings.MSAA, 4).putInt(GraphicsSettings.ANISO, 2)
                .putString(GraphicsSettings.ASPECT, "4:3").putBoolean(GraphicsSettings.STATS, true).commit();
        Intent game = new Intent();
        GraphicsSettings.putLaunchExtras(game, prefs);
        prefs.edit().putInt(GraphicsSettings.MSAA, 1).commit();
        assertEquals(4, game.getIntExtra(GraphicsSettings.MSAA, -1));
        assertEquals(2, game.getIntExtra(GraphicsSettings.ANISO, -1));
        assertEquals("4:3", game.getStringExtra(GraphicsSettings.ASPECT));
        assertTrue(game.getBooleanExtra(GraphicsSettings.STATS, false));
        assertEquals(1, GraphicsSettings.msaa(8));
        assertEquals(16, GraphicsSettings.aniso(-1));
        assertEquals("auto", GraphicsSettings.aspect("bad"));
    }

    @Test public void openGlesSurvivesCrossProcessLaunchWithoutVulkanDriverSetup() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        prefs.edit().clear().putString(GraphicsSettings.BACKEND, "opengles")
                .putString(DriverStore.PREF_DRIVER, "previous-vulkan-driver").commit();
        Intent game = new Intent();
        GraphicsSettings.putLaunchExtras(game, prefs);
        // The launch snapshot wins over preferences subsequently changed by the UI.
        prefs.edit().putString(GraphicsSettings.BACKEND, "vulkan").commit();
        String backend = GraphicsSettings.launchBackend(game, prefs);
        assertEquals("opengles", backend);
        assertFalse(DriverRuntime.usesCustomDriver(backend));
        assertEquals("previous-vulkan-driver", prefs.getString(DriverStore.PREF_DRIVER, ""));
        assertTrue(DriverRuntime.usesCustomDriver("vulkan"));
    }

    @Test public void invalidOrMissingBackendUsesVulkanAndNeverDesktopOpenGl() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication().getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        prefs.edit().clear().putString(GraphicsSettings.BACKEND, "opengles").commit();
        assertEquals("opengles", GraphicsSettings.launchBackend(new Intent(), prefs));
        for (String invalid : new String[]{null, "", "opengl", "null", "bad"}) {
            Intent game = new Intent().putExtra(GraphicsSettings.BACKEND, invalid);
            assertEquals("vulkan", GraphicsSettings.launchBackend(game, prefs));
        }
    }
}
