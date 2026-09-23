package com.ylports.strikers;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
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
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.Locale;

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
    private boolean autoHideTouchWithGamepad;
    private boolean resumed;
    private Handler uiHandler;
    private TextView performanceView;
    private TextView lanStatusView;
    private SessionDiagnostics sessionDiagnostics;
    private int lanRole = GameBootstrapActivity.LAN_ROLE_OFF;
    private String lanLocalAddress;
    private boolean lanStartSucceeded;
    private int lanBadgeState = -1;
    private static native float nativePresentedFps();
    private static native String nativeRuntimeSnapshot();
    private static native boolean nativeStartLan(int role, String host, int port);
    private static native void nativeStopLan();
    private static native String nativeLanStatus();
    private final Runnable performanceRunnable = new Runnable() {
        @Override public void run() {
            if (!resumed || performanceView == null || mBrokenLibraries || isFinishing()) return;
            float fps = nativePresentedFps();
            String text = fps > 0 ? String.format(Locale.US, "%.0f FPS · %.1f ms", fps, 1000f / fps) : "0 FPS";
            Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null && battery.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
                text += String.format(Locale.US, " · Batería %.1f °C",
                        battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f);
            }
            performanceView.setText(text);
            uiHandler.postDelayed(this, 1000);
        }
    };

    public int getTargetFrameRate() {
        return getIntent().getIntExtra(GameBootstrapActivity.EXTRA_TARGET_FPS, 60) == 120
                ? 120 : 60;
    }

    static native void nativeSetTouchState(
            int buttons,
            int stickX,
            int stickY,
            int substickX,
            int substickY,
            int triggerLeft,
            int triggerRight);


    private final Runnable hideSettingsControlRunnable = this::hideSettingsControl;
    private final Runnable lanStatusRunnable = this::updateLanStatus;
    private final Runnable overlayRecoveryRunnable = () -> {
        if (!resumed || isFinishing() || isDestroyed() || mBrokenLibraries) return;
        applyImmersiveMode();
        updateTouchOverlayVisibility();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        RunLog.installJavaCrashHandler(this);
        RunLog.append(this, "SDL activity: onCreate BEFORE SDLActivity.onCreate");
        super.onCreate(savedInstanceState);
        RunLog.append(this, "SDL activity: onCreate AFTER SDLActivity.onCreate");
        // SDL displays its own load/version error. Do not call game JNI on that path.
        if (mBrokenLibraries) return;

        uiHandler = new Handler(Looper.getMainLooper());
        sessionDiagnostics = new SessionDiagnostics(this, StrikersActivity::nativeRuntimeSnapshot);
        lanRole = getIntent().getIntExtra(
                GameBootstrapActivity.EXTRA_LAN_ROLE, GameBootstrapActivity.LAN_ROLE_OFF);
        lanLocalAddress = getIntent().getStringExtra(GameBootstrapActivity.EXTRA_LAN_LOCAL_ADDRESS);
        if (lanRole != GameBootstrapActivity.LAN_ROLE_OFF) {
            String host = getIntent().getStringExtra(GameBootstrapActivity.EXTRA_LAN_ADDRESS);
            lanStartSucceeded = nativeStartLan(lanRole, host, GameBootstrapActivity.LAN_PORT);
            lanStatusView = new TextView(this);
            lanStatusView.setTextColor(Color.WHITE);
            lanStatusView.setTextSize(13);
            lanStatusView.setGravity(Gravity.CENTER);
            lanStatusView.setPadding(dp(12), dp(7), dp(12), dp(7));
            lanStatusView.setMaxWidth(getResources().getDisplayMetrics().widthPixels - dp(24));
            lanStatusView.setClickable(false);
            lanStatusView.setFocusable(false);
            lanStatusView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            lanStatusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            if (!lanStartSucceeded) {
                RunLog.append(this, "LAN: no se pudo abrir la sala o iniciar el socket");
            }
        }
        if (getIntent().getBooleanExtra(GraphicsSettings.STATS, false)) {
            performanceView = new TextView(this);
            performanceView.setTextColor(Color.WHITE);
            performanceView.setTextSize(12);
            performanceView.setBackgroundColor(0x99000000);
            performanceView.setPadding(12, 4, 12, 4);
            performanceView.setClickable(false);
            performanceView.setFocusable(false);
            performanceView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        autoHideTouchWithGamepad = getIntent().getBooleanExtra(
                GameBootstrapActivity.EXTRA_AUTO_HIDE_TOUCH, false);
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
        if (mBrokenLibraries) return;
        resumed = true;
        if (sessionDiagnostics != null) sessionDiagnostics.setResumed(true);
        if (uiHandler != null && performanceView != null) {
            uiHandler.removeCallbacks(performanceRunnable);
            uiHandler.post(performanceRunnable);
        }
        if (uiHandler != null && lanRole != GameBootstrapActivity.LAN_ROLE_OFF) {
            uiHandler.removeCallbacks(lanStatusRunnable);
            uiHandler.post(lanStatusRunnable);
        }
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
        } else if (touchController != null) {
            touchController.releaseAll();
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
        resumed = false;
        if (sessionDiagnostics != null) sessionDiagnostics.setResumed(false);
        cancelOverlayCallbacks();
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
        resumed = false;
        if (sessionDiagnostics != null) {
            sessionDiagnostics.stop();
            sessionDiagnostics = null;
        }
        if (lanStartSucceeded) nativeStopLan();
        cancelOverlayCallbacks();
        if (inputManager != null) {
            inputManager.unregisterInputDeviceListener(this);
            inputManager = null;
        }
        if (touchController != null) {
            touchController.dispose();
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
        if (uiHandler == null || !resumed || isFinishing() || isDestroyed()) {
            return;
        }
        uiHandler.removeCallbacks(overlayRecoveryRunnable);
        uiHandler.post(overlayRecoveryRunnable);
        uiHandler.postDelayed(overlayRecoveryRunnable, OVERLAY_RECOVERY_SHORT_MS);
        uiHandler.postDelayed(overlayRecoveryRunnable, OVERLAY_RECOVERY_LONG_MS);
    }

    private void cancelOverlayCallbacks() {
        if (uiHandler != null) {
            uiHandler.removeCallbacks(hideSettingsControlRunnable);
            uiHandler.removeCallbacks(overlayRecoveryRunnable);
            uiHandler.removeCallbacks(performanceRunnable);
            uiHandler.removeCallbacks(lanStatusRunnable);
        }
    }

    @Override
    public void onContentChanged() {
        super.onContentChanged();
        scheduleOverlayRecovery();
    }

    /** Called when SDL recreates or resizes the native surface. */
    public void recoverTouchOverlayAfterSurfaceChange() {
        scheduleOverlayRecovery();
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
            touchController.setFitsSystemWindows(false);
            touchController.setTranslationZ(1f);
            touchController.requestApplyInsets();
            RunLog.append(this, "SDL activity: touch overlay attached to current content root");
        }

        if (contentRoot.indexOfChild(touchController) != contentRoot.getChildCount() - 1) {
            touchController.bringToFront();
        }
        WindowInsets insets = contentRoot.getRootWindowInsets();
        if (insets != null) touchController.applySafeInsets(insets);
        if (performanceView != null && contentRoot instanceof FrameLayout) {
            if (performanceView.getParent() != contentRoot) {
                if (performanceView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) performanceView.getParent()).removeView(performanceView);
                }
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                params.bottomMargin = (int) (12 * getResources().getDisplayMetrics().density);
                contentRoot.addView(performanceView, params);
                performanceView.setTranslationZ(2f);
            }
            performanceView.bringToFront();
        }
        if (lanStatusView != null && contentRoot instanceof FrameLayout) {
            if (lanStatusView.getParent() != contentRoot) {
                if (lanStatusView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) lanStatusView.getParent()).removeView(lanStatusView);
                }
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                params.topMargin = (int) (18 * getResources().getDisplayMetrics().density);
                contentRoot.addView(lanStatusView, params);
                lanStatusView.setTranslationZ(3f);
            }
            lanStatusView.bringToFront();
        }
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
        touchController.setOnTouchListener((view, event) -> {
            if (!touchController.isSettingsControlVisible()
                    && event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && event.getX() <= view.getWidth() * 0.20f
                    && event.getY() <= view.getHeight() * 0.22f) {
                showSettingsControlFor(SETTINGS_AUTO_HIDE_MS);
            }
            return false;
        });
    }

    private void showSettingsControlFor(long delayMs) {
        if (touchController == null || uiHandler == null || !resumed) return;
        touchController.setSettingsControlVisible(true);
        uiHandler.removeCallbacks(hideSettingsControlRunnable);
        uiHandler.postDelayed(hideSettingsControlRunnable, delayMs);
    }

    private void hideSettingsControl() {
        if (touchController != null && resumed) {
            touchController.setSettingsControlVisible(false);
        }
    }

    private void updateLanStatus() {
        if (!resumed || lanStatusView == null || uiHandler == null
                || isFinishing() || isDestroyed()) return;
        String status = lanStartSucceeded ? nativeLanStatus() : "LAN: no se pudo abrir la sala";
        if (lanStartSucceeded && lanRole == GameBootstrapActivity.LAN_ROLE_HOST
                && lanLocalAddress != null) {
            status += "\n" + lanLocalAddress + ":" + GameBootstrapActivity.LAN_PORT;
        }
        lanStatusView.setText(status);
        lanStatusView.setContentDescription("Estado LAN: " + status.replace('\n', ' '));
        applyLanBadgeStyle(status);
        ensureTouchOverlayAttached();
        uiHandler.postDelayed(lanStatusRunnable, 600);
    }

    private void applyLanBadgeStyle(String status) {
        int state;
        if (status.startsWith("2/2")) {
            state = 2;
        } else if (status.contains("perdido") || status.contains("no se pudo")) {
            state = 0;
        } else {
            state = 1;
        }
        if (state == lanBadgeState) return;
        lanBadgeState = state;

        int backgroundColor = state == 2 ? 0xE01D593A
                : state == 0 ? 0xE05F2A2A : 0xE05A4818;
        int foregroundColor = state == 2 ? 0xFFE4FFF0
                : state == 0 ? 0xFFFFE6E6 : 0xFFFFF2C2;
        GradientDrawable background = new GradientDrawable();
        background.setColor(backgroundColor);
        background.setCornerRadius(dp(10));
        background.setStroke(dp(1), state == 2 ? 0xFF55A978
                : state == 0 ? 0xFFB95C5C : 0xFFAC8D43);
        lanStatusView.setBackground(background);
        lanStatusView.setTextColor(foregroundColor);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void updateTouchOverlayVisibility() {
        if (touchController == null || !resumed || isFinishing() || isDestroyed()) return;

        ensureTouchOverlayAttached();
        boolean connected = hasPhysicalGamepad();
        if (connected != physicalGamepadConnected) {
            physicalGamepadConnected = connected;
            RunLog.append(this, "SDL activity: physical gamepad connected=" + connected);
        }

        // Some phones expose internal input devices as gamepads. Keep touch usable
        // unless the player explicitly chose automatic hiding in the launcher.
        boolean hide = autoHideTouchWithGamepad && connected;
        int visibility = hide ? View.GONE : View.VISIBLE;
        if (touchController.getVisibility() != visibility) {
            touchController.releaseAll();
            touchController.setVisibility(visibility);
        }
        if (hide) {
            uiHandler.removeCallbacks(hideSettingsControlRunnable);
        } else {
            showSettingsControlFor(SETTINGS_AUTO_HIDE_MS);
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
