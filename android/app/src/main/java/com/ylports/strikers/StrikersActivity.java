package com.ylports.strikers;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewGroup;

import dev.encounter.aurora.AuroraSurface;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

/** Runs the native Strikers port through SDL3 and Aurora. */
public final class StrikersActivity extends SDLActivity
        implements InputManager.InputDeviceListener {
    private TouchControllerView touchController;
    private InputManager inputManager;
    private boolean physicalGamepadConnected;

    static native void nativeSetTouchState(
            int buttons,
            int stickX,
            int stickY,
            int substickX,
            int substickY,
            int triggerLeft,
            int triggerRight);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        RunLog.installJavaCrashHandler(this);
        RunLog.append(this, "SDL activity: onCreate BEFORE SDLActivity.onCreate");
        super.onCreate(savedInstanceState);
        RunLog.append(this, "SDL activity: onCreate AFTER SDLActivity.onCreate");

        touchController = new TouchControllerView(this);
        addContentView(touchController, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        touchController.releaseAll();

        inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (inputManager != null) {
            inputManager.registerInputDeviceListener(this, null);
        }
        updateTouchOverlayVisibility();
        RunLog.append(this, "SDL activity: compact customizable touch overlay installed");
    }

    @Override
    protected void onStart() {
        RunLog.append(this, "SDL activity: onStart BEFORE super");
        super.onStart();
        RunLog.append(this, "SDL activity: onStart AFTER super");
    }

    @Override
    protected void onResume() {
        RunLog.append(this, "SDL activity: onResume BEFORE super");
        super.onResume();
        if (touchController != null) {
            touchController.reloadPreferences();
        }
        updateTouchOverlayVisibility();
        RunLog.append(this, "SDL activity: onResume AFTER super");
    }

    @Override
    protected void onPause() {
        RunLog.append(this, "SDL activity: onPause BEFORE super");
        if (touchController != null) {
            touchController.releaseAll();
        }
        super.onPause();
        RunLog.append(this, "SDL activity: onPause AFTER super");
    }

    @Override
    protected void onStop() {
        RunLog.append(this, "SDL activity: onStop BEFORE super");
        if (touchController != null) {
            touchController.releaseAll();
        }
        super.onStop();
        RunLog.append(this, "SDL activity: onStop AFTER super");
    }

    @Override
    protected void onDestroy() {
        RunLog.append(this, "SDL activity: onDestroy BEFORE super");
        if (inputManager != null) {
            inputManager.unregisterInputDeviceListener(this);
            inputManager = null;
        }
        if (touchController != null) {
            touchController.releaseAll();
        }
        super.onDestroy();
        RunLog.append(this, "SDL activity: onDestroy AFTER super");
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
            touchController.releaseAll();
            touchController.setVisibility(View.GONE);
        } else {
            touchController.setVisibility(View.VISIBLE);
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
        RunLog.append(this, "SDL activity: getLibraries -> SDL3, strikers");
        return new String[] { "SDL3", "strikers" };
    }

    @Override
    protected SDLSurface createSDLSurface(Context context) {
        RunLog.append(context, "SDL activity: createSDLSurface -> AuroraSurface");
        return new AuroraSurface(context);
    }
}
