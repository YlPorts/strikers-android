package com.ylports.strikers;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, application = Application.class)
public class MainActivityTest {
    private Button button(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); ++i) {
                Button found = button(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void collapsedSettingsKeepSavePickerAccessibleAndLaunchAtSelected1080p() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File image = new File(context.getCacheDir(), "test-game.iso");
        Files.write(image.toPath(), new byte[]{1, 2, 3, 4});
        String tree = "content://strikers.saves.test/tree/selected";
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit().clear()
                .putString(MainActivity.PREF_GAME_URI, Uri.fromFile(image).toString())
                .putInt(MainActivity.PREF_RESOLUTION_ROWS, 1080)
                .putInt(MainActivity.PREF_TARGET_FPS, 120)
                .putString(MainActivity.PREF_LANGUAGE, "spanish")
                .putString(MainActivity.PREF_SAVE_FOLDER, tree).commit();
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup().visible();
        try {
            MainActivity activity = controller.get();
            View root = activity.getWindow().getDecorView();
            Button folder = button(root, "Elegir carpeta");
            assertNotNull(folder);
            assertFalse(folder.isShown());
            button(root, "Ajustes").performClick();
            assertTrue(folder.isShown());
            folder.performClick();
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, shadowOf(activity).getNextStartedActivity().getAction());
            button(root, "Cerrar ajustes").performClick();
            button(root, "Jugar").performClick();
            Intent game = shadowOf(activity).getNextStartedActivity();
            assertNotNull(game);
            assertEquals(GameBootstrapActivity.class.getName(), game.getComponent().getClassName());
            assertEquals(GameBootstrapActivity.LAN_ROLE_OFF,
                    game.getIntExtra(GameBootstrapActivity.EXTRA_LAN_ROLE, -1));
            assertEquals(1080, game.getIntExtra(GameBootstrapActivity.EXTRA_RENDER_ROWS, 0));
            assertEquals(120, game.getIntExtra(GameBootstrapActivity.EXTRA_TARGET_FPS, 0));
            assertEquals("spanish", game.getStringExtra(GameBootstrapActivity.EXTRA_LANGUAGE));
            assertEquals(tree, game.getStringExtra(GameBootstrapActivity.EXTRA_SAVE_FOLDER));
        } finally {
            controller.pause().stop().destroy();
        }
    }
}
