package com.ylports.strikers;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;

import dev.encounter.aurora.AuroraSurface;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

/** Runs the native Strikers port through SDL3 and Aurora. */
public final class StrikersActivity extends SDLActivity
        implements InputManager.InputDeviceListener {
    private static final long SETTINGS_AUTO_HIDE_MS = 6000L;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        RunLog.installJavaCrashHandler(this);
        RunLog.append(this, "SDL activity: onCreate BEFORE SDLActivity.onCreate");
        super.onCreate(savedInstanceState);
        RunLog.append(this, "SDL activity: onCreate AFTER SDLActivity.onCreate");

        uiHandler = new Handler(Looper.getMainLooper());
        touchController = new TouchControllerView(this);
        addContentView(touchController, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        touchController.releaseAll();
        installSettingsAutoHide();

        inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (inputManager != null) {
            inputManager.registerInputDeviceListener(this, null);
        }
        updateTouchOverlayVisibility();
        RunLog.append(this, "SDL activity: compact customizable touch overlay installed");
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (touchController != null) {
            touchController.reloadPreferences();
        }
        updateTouchOverlayVisibility();
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

        boolean connected = hasPhysicalGamepad();
        if (connected != physicalGamepadConnected) {
            physicalGamepadConnected = connected;
            RunLog.append(this, connected
                    ? "SDL activity: physical gamepad connected; touch overlay hidden"
                    : "SDL activity: physical gamepad disconnected; touch overlay restored");
        }

        if (connected) {
            if (uiHandler != null) {
                uiHandler.removeCallbacks(hideSettingsControlRunnable);
            }
            touchController.releaseAll();
            touchController.setVisibility(View.GONE);
        } else {
            touchController.setVisibility(View.VISIBLE);
            captureSettingsRadius();
            showSettingsControlFor(SETTINGS_AUTO_HIDE_MS);
            touchController.postInvalidateOnAnimation();
        }
    }

    private boolean hasPhysicalGamepad() {
        int[] ids = InputDevice.getDeviceIds();
        for (int id : ids) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) {
                continue;
            }
            int sources = device.getSources();
            boolean gamepad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
            boolean joystick = (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
            if (gamepad || joystick) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "strikers" };
    }

    @Override
    protected SDLSurface createSDLSurface(Context context) {
        return new AuroraSurface(context);
    }
}
