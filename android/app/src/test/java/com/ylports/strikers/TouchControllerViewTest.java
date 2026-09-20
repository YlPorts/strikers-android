package com.ylports.strikers;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, application = Application.class)
public class TouchControllerViewTest {
    private Activity activity;
    private TouchControllerView view;
    private int[] state;
    private int sends;

    @Before
    public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences("strikers_touch", Context.MODE_PRIVATE).edit().clear().commit();
        activity.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        createView();
    }

    private void createView() {
        view = new TouchControllerView(activity, (buttons, x, y, cx, cy, l, r) -> {
            state = new int[]{buttons, x, y, cx, cy, l, r};
            sends++;
        });
        activity.setContentView(view);
        view.layout(0, 0, 1920, 1080);
    }

    @Test
    public void simultaneousStickAndButtonReleaseIndependently() {
        touch(MotionEvent.ACTION_DOWN, new int[]{3}, new float[]{250}, new float[]{800});
        touch(MotionEvent.ACTION_MOVE, new int[]{3}, new float[]{350}, new float[]{800});
        assertTrue(state[1] > 0);
        touch(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[]{3, 8}, new float[]{350, x(4)}, new float[]{800, y(4)});
        assertEquals(0x100, state[0]);
        assertTrue(state[1] > 0);
        touch(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new int[]{3, 8}, new float[]{350, x(4)}, new float[]{800, y(4)});
        assertEquals(0, state[0]);
        assertTrue(state[1] > 0);
        touch(MotionEvent.ACTION_UP, new int[]{3}, new float[]{350}, new float[]{800});
        assertNeutral();
    }

    @Test
    public void cancelFocusLossResizeAndDetachReleaseInput() {
        pressA();
        touch(MotionEvent.ACTION_CANCEL, new int[]{0}, new float[]{x(4)}, new float[]{y(4)});
        assertNeutral();
        pressA();
        view.onWindowFocusChanged(false);
        assertNeutral();
        pressA();
        view.layout(0, 0, 1280, 720);
        assertNeutral();
        pressA();
        view.onDetachedFromWindow();
        assertNeutral();
    }

    @Test
    public void unchangedMovesDoNotSendNativeUpdates() {
        pressA();
        int before = sends;
        for (int i = 0; i < 100; i++) {
            touch(MotionEvent.ACTION_MOVE, new int[]{0}, new float[]{x(4)}, new float[]{y(4)});
        }
        assertEquals(before, sends);
    }

    @Test
    public void customButtonsStayInsideSafeAreaAcrossScreenSizes() {
        activity.getSharedPreferences("strikers_touch", Context.MODE_PRIVATE).edit()
                .putBoolean("touch_custom_positions", true)
                .putFloat("touch_pos_a_x", 0.965f).putFloat("touch_pos_a_y", 0.955f)
                .putFloat("touch_pos_l_x", 0.035f).putFloat("touch_pos_l_y", 0.045f)
                .putFloat("touch_scale", 1.4f).apply();
        view.reloadPreferences();
        for (int[] size : new int[][]{{1920, 1080}, {2400, 1080}, {1280, 720}, {1024, 768}}) {
            view.layout(0, 0, size[0], size[1]);
            view.applySafeInsets(safeInsets(90, 30, 100, 48));
            for (int role = 1; role < 12; role++) {
                float radius = ReflectionHelpers.callInstanceMethod(view, "visibleRadius",
                        ReflectionHelpers.ClassParameter.from(int.class, role));
                assertTrue(x(role) - radius >= 90);
                assertTrue(x(role) + radius <= size[0] - 100);
                assertTrue(y(role) - radius >= 30);
                assertTrue(y(role) + radius <= size[1] - 48);
            }
            pressA();
            view.releaseAll();
        }
    }

    @Test
    public void insetChangeReleasesHeldButtons() {
        pressA();
        view.applySafeInsets(safeInsets(80, 0, 0, 48));
        assertNeutral();
    }

    @Test
    public void settingsTimeoutCannotHideEditorSaveButton() {
        openSettings();
        Dialog dialog = ShadowDialog.getLatestDialog();
        Button move = findButton(dialog.getWindow().getDecorView(), "Mover botones en pantalla");
        assertNotNull(move);
        move.performClick();
        view.setSettingsControlVisible(false);
        assertTrue(view.isSettingsControlVisible());
        tapSettings();
        assertFalse((Boolean) ReflectionHelpers.getField(view, "editMode"));
    }

    @Test
    public void disposingSettingsClosesDialogAndReleasesInput() {
        openSettings();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        view.dispose();
        assertFalse(dialog.isShowing());
        assertNeutral();
    }

    @Test
    public void migrationPreservesLayoutAndDoesNotRepeatFromStaleLauncher() {
        activity.getSharedPreferences("strikers_touch", Context.MODE_PRIVATE).edit().clear().commit();
        activity.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                .putFloat("touch_scale", 1.2f).putBoolean("touch_custom_positions", true)
                .putFloat("touch_pos_a_x", 0.75f).apply();
        createView();
        assertEquals(1.2f, (Float) ReflectionHelpers.getField(view, "controlScale"), 0.001f);
        assertEquals(0.75f * 1920, x(4), 0.01f);
        activity.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                .putFloat("touch_scale", 0.7f).apply();
        createView();
        assertEquals(1.2f, (Float) ReflectionHelpers.getField(view, "controlScale"), 0.001f);
    }

    private void pressA() {
        touch(MotionEvent.ACTION_DOWN, new int[]{0}, new float[]{x(4)}, new float[]{y(4)});
        assertEquals(0x100, state[0]);
    }

    private void assertNeutral() {
        assertArrayEquals(new int[7], state);
    }

    private float x(int role) {
        return ReflectionHelpers.callInstanceMethod(view, "xForRole",
                ReflectionHelpers.ClassParameter.from(int.class, role));
    }

    private float y(int role) {
        return ReflectionHelpers.callInstanceMethod(view, "yForRole",
                ReflectionHelpers.ClassParameter.from(int.class, role));
    }

    private void openSettings() {
        view.setSettingsControlVisible(true);
        tapSettings();
        assertNotNull(ShadowDialog.getLatestDialog());
    }

    private void tapSettings() {
        touch(MotionEvent.ACTION_DOWN, new int[]{0},
                new float[]{ReflectionHelpers.getField(view, "settingsCx")},
                new float[]{ReflectionHelpers.getField(view, "settingsCy")});
    }

    private static Button findButton(View root, String label) {
        if (root instanceof Button && label.contentEquals(((Button) root).getText())) return (Button) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static WindowInsets safeInsets(int left, int top, int right, int bottom) {
        if (Build.VERSION.SDK_INT >= 30) {
            return new WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.displayCutout(), Insets.of(left, top, right, 0))
                    .setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, bottom))
                    .build();
        }
        return new WindowInsets(new Rect(left, top, right, bottom));
    }

    private void touch(int action, int[] ids, float[] xs, float[] ys) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = ids[i];
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = xs[i]; coords[i].y = ys[i]; coords[i].pressure = 1;
        }
        MotionEvent event = MotionEvent.obtain(0, 10, action, ids.length, properties,
                coords, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        view.onTouchEvent(event);
        event.recycle();
    }
}
