package com.ylports.strikers;

import android.content.Context;
import android.content.res.Configuration;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import java.lang.reflect.Field;

import dev.encounter.aurora.AuroraSurface;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

/** Runs the native Strikers port through SDL3 and Aurora. */
public final class StrikersActivity extends SDLActivity
        implements InputManager.InputDeviceListener {
    private static final long SETTINGS_AUTO_HIDE_MS = 6000L;
    private static final long OVERLAY_RECOVERY_SHORT_MS = 120L;
    private static final long OVERLAY_RECOVERY_LONG_MS = 700L;

    private TouchControllerView touchController;
    private InputManager inputManager;
    private boolean physicalGamepadConnected;
    private Handler uiHandler;
    private Field settingsRadiusField;
    private float settingsRadiusNormal = -1f;
    private boolean settingsControlHidden;

    static native void nativeSetTouchState(
            int buttons,
            int stickX,
            int stickY,
            int substickX,
            int substickY,
            int triggerLeft,
            int triggerRight);

    private final Runnable hideSettingsControlRunnable = this::hideSettingsControl;
    private final Runnable overlayRecoveryRunnable = () -> {
        applyImmersiveMode();
        ensureTouchOverlayAttached();
        updateTouchOverlayVisibility();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        RunLog.installJavaCrashHandler(this);
        RunLog.append(this, "SDL activity: onCreate BEFORE SDLActivity.onCreate");
        super.onCreate(savedInstanceState);
        RunLog.append(this, "SDL activity: onCreate AFTER SDLActivity.onCreate");

        uiHandler = new Handler(Looper.getMainLooper());
        applyImmersiveMode();

        touchController = new TouchControllerView(this);
        touchController.setVisibility(View.VISIBLE);
        ensureTouchOverlayAttached();
        touchController.releaseAll();
        installSettingsAutoHide();

        inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (inputManager != null) {
            inputManager.registerInputDeviceListener(this, null);
        }

        updateTouchOverlayVisibility();
        scheduleOverlayRecovery();
        RunLog.append(this, "SDL activity: resilient touch overlay installed");
    }

    @Override
    protected void onStart() {
        super.onStart();
        scheduleOverlayRecovery();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveMode();
        ensureTouchOverlayAttached();
        if (touchController != null) {
            touchController.reloadPreferences();
        }
        updateTouchOverlayVisibility();
        scheduleOverlayRecovery();
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        scheduleOverlayRecovery();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            scheduleOverlayRecovery();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyImmersiveMode();
        ensureTouchOverlayAttached();
        if (touchController != null) {
            touchController.reloadPreferences();
            touchController.requestLayout();
            touchController.postInvalidateOnAnimation();
        }
        scheduleOverlayRecovery();
    }

    @Override
    protected void onPause() {
        if (touchController != null) {
            touchController.releaseAll();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (touchController != null) {
            touchController.releaseAll();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (uiHandler != null) {
            uiHandler.removeCallbacks(hideSettingsControlRunnable);
            uiHandler.removeCallbacks(overlayRecoveryRunnable);
        }
        if (inputManager != null) {
            inputManager.unregisterInputDeviceListener(this);
            inputManager = null;
        }
        if (touchController != null) {
            touchController.releaseAll();
        }
        super.onDestroy();
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        runOnUiThread(this::updateTouchOverlayVisibility);
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        runOnUiThread(this::updateTouchOverlayVisibility);
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        runOnUiThread(this::updateTouchOverlayVisibility);
    }

    private void scheduleOverlayRecovery() {
        if (uiHandler == null) {
            return;
        }
        uiHandler.removeCallbacks(overlayRecoveryRunnable);
        uiHandler.post(overlayRecoveryRunnable);
        uiHandler.postDelayed(overlayRecoveryRunnable, OVERLAY_RECOVERY_SHORT_MS);
        uiHandler.postDelayed(overlayRecoveryRunnable, OVERLAY_RECOVERY_LONG_MS);
    }

    /**
     * SDL may rebuild or replace its content hierarchy while the native surface is being
     * created. Keep the touch view attached to the current android.R.id.content root and
     * explicitly above the SurfaceView so controls cannot disappear behind the renderer.
     */
    private void ensureTouchOverlayAttached() {
        if (touchController == null) {
            return;
        }

        ViewGroup contentRoot = findViewById(android.R.id.content);
        if (contentRoot == null) {
            return;
        }

        ViewParent currentParent = touchController.getParent();
        if (currentParent != contentRoot) {
            if (currentParent instanceof ViewGroup) {
                ((ViewGroup) currentParent).removeView(touchController);
            }
            contentRoot.addView(touchController, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            RunLog.append(this, "SDL activity: touch overlay attached to current content root");
        }

        touchController.setFitsSystemWindows(false);
        touchController.setTranslationZ(10000f);
        touchController.bringToFront();
        touchController.requestLayout();
        contentRoot.invalidate();
    }

    /**
     * Keep Android system bars out of the game on both legacy (API 26-29) and modern
     * Android. Modern devices use WindowInsetsController; older devices use the classic
     * immersive-sticky flags. The mode is intentionally re-applied when focus returns.
     */
    @SuppressWarnings("deprecation")
    private void applyImmersiveMode() {
        if (getWindow() == null) {
            return;
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            if (attrs.layoutInDisplayCutoutMode
                    != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
                attrs.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(attrs);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void installSettingsAutoHide() {
        try {
            settingsRadiusField = TouchControllerView.class.getDeclaredField("settingsRadius");
            settingsRadiusField.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            settingsRadiusField = null;
            RunLog.append(this, "touch settings auto-hide unavailable: " + e.getClass().getSimpleName());
            return;
        }

        touchController.post(() -> {
            captureSettingsRadius();
            showSettingsControlFor(SETTINGS_AUTO_HIDE_MS);
        });

        touchController.setOnTouchListener((view, event) -> {
            if (!physicalGamepadConnected
                    && settingsControlHidden
                    && event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && event.getX() <= view.getWidth() * 0.20f
                    && event.getY() <= view.getHeight() * 0.22f) {
                showSettingsControlFor(SETTINGS_AUTO_HIDE_MS);
            }
            return false;
        });
    }

    private void captureSettingsRadius() {
        if (settingsRadiusField == null || touchController == null) {
            return;
        }
        try {
            float current = settingsRadiusField.getFloat(touchController);
            if (current > 0f) {
                settingsRadiusNormal = current;
            }
        } catch (IllegalAccessException ignored) {
        }
    }

    private void showSettingsControlFor(long delayMs) {
        if (settingsRadiusField == null || touchController == null || uiHandler == null) {
            return;
        }
        try {
            float current = settingsRadiusField.getFloat(touchController);
            if (current > 0f) {
                settingsRadiusNormal = current;
            }
            if (settingsRadiusNormal > 0f) {
                settingsRadiusField.setFloat(touchController, settingsRadiusNormal);
                settingsControlHidden = false;
                touchController.postInvalidateOnAnimation();
            }
        } catch (IllegalAccessException ignored) {
        }
        uiHandler.removeCallbacks(hideSettingsControlRunnable);
        if (!physicalGamepadConnected) {
            uiHandler.postDelayed(hideSettingsControlRunnable, delayMs);
        }
    }

    private void hideSettingsControl() {
        if (settingsRadiusField == null || touchController == null || physicalGamepadConnected) {
            return;
        }
        try {
            float current = settingsRadiusField.getFloat(touchController);
            if (current > 0f) {
                settingsRadiusNormal = current;
            }
            settingsRadiusField.setFloat(touchController, 0f);
            settingsControlHidden = true;
            touchController.postInvalidateOnAnimation();
        } catch (IllegalAccessException ignored) {
        }
    }

    private void updateTouchOverlayVisibility() {
        if (touchController == null) {
            return;
        }

        ensureTouchOverlayAttached();
        boolean connected = hasPhysicalGamepad();
        if (connected != physicalGamepadConnected) {
            physicalGamepadConnected = connected;
            RunLog.append(this, connected
                    ? "SDL activity: real physical/Bluetooth gamepad detected; touch overlay hidden"
                    : "SDL activity: gamepad disconnected; touch overlay restored");
        }

        if (connected) {
            if (uiHandler != null) {
                uiHandler.removeCallbacks(hideSettingsControlRunnable);
            }
            touchController.releaseAll();
            touchController.setVisibility(View.GONE);
        } else {
            touchController.setVisibility(View.VISIBLE);
            touchController.setAlpha(1f);
            touchController.setEnabled(true);
            touchController.bringToFront();
            captureSettingsRadius();
            showSettingsControlFor(SETTINGS_AUTO_HIDE_MS);
            touchController.requestLayout();
            touchController.postInvalidateOnAnimation();
        }
    }

    private boolean hasPhysicalGamepad() {
        int[] ids = InputDevice.getDeviceIds();
        for (int id : ids) {
            InputDevice device = InputDevice.getDevice(id);
            if (isRealGamepad(device)) {
                return true;
            }
        }
        return false;
    }

    /**
     * USB, built-in physical and Bluetooth controllers are all reported as non-virtual
     * Android InputDevices. Keyboards, mice, touchscreens and software/virtual devices do
     * not hide the touch overlay. A joystick-only device must expose real X/Y axes.
     */
    private boolean isRealGamepad(InputDevice device) {
        if (device == null || device.isVirtual()) {
            return false;
        }

        int sources = device.getSources();
        boolean gamepad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        if (gamepad) {
            return true;
        }

        boolean joystick = (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (!joystick) {
            return false;
        }

        return device.getMotionRange(MotionEvent.AXIS_X, InputDevice.SOURCE_JOYSTICK) != null
                && device.getMotionRange(MotionEvent.AXIS_Y, InputDevice.SOURCE_JOYSTICK) != null;
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "strikers" };
    }

    @Override
    protected SDLSurface createSDLSurface(Context context) {
        AuroraSurface surface = new AuroraSurface(context);
        surface.setZOrderOnTop(false);
        surface.setZOrderMediaOverlay(false);
        return surface;
    }
}
