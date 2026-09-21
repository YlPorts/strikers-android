package dev.encounter.aurora;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import com.ylports.strikers.RunLog;
import com.ylports.strikers.StrikersActivity;

import org.libsdl.app.SDLSurface;

/**
 * SDL surface with the extra readiness handshake expected by Aurora on Android.
 * The native implementation lives inside libstrikers.so through Aurora's window.cpp.
 */
public class AuroraSurface extends SDLSurface {
    private static native void nativeSetSurfaceReady(boolean ready);

    public AuroraSurface(Context context) {
        super(context);
        RunLog.append(context, "AuroraSurface: constructor");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        RunLog.append(getContext(), "AuroraSurface: surfaceCreated begin");
        nativeSetSurfaceReady(false);
        super.surfaceCreated(holder);
        requestGameFrameRate(holder);
        RunLog.append(getContext(), "AuroraSurface: surfaceCreated end");
    }

    private void requestGameFrameRate(SurfaceHolder holder) {
        float targetFps = getContext() instanceof StrikersActivity
                ? ((StrikersActivity) getContext()).getTargetFrameRate() : 60f;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                // A hint to the compositor, separate from the native FPS cap.
                // DEFAULT is for games; FIXED_SOURCE is for video. Only seamless
                // mode switches are allowed by this two-argument API.
                Surface surface = holder.getSurface();
                if (surface != null && surface.isValid()) {
                    surface.setFrameRate(targetFps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
                }
            } else if (getContext() instanceof Activity) {
                // Before API 30 request only a rate the current resolution supports.
                Activity activity = (Activity) getContext();
                Display display = activity.getWindowManager().getDefaultDisplay();
                Display.Mode current = display.getMode();
                float closest = 0f;
                for (Display.Mode mode : display.getSupportedModes()) {
                    if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                            && mode.getPhysicalHeight() == current.getPhysicalHeight()
                            && Math.abs(mode.getRefreshRate() - targetFps) < 1f
                            && (closest == 0f || Math.abs(mode.getRefreshRate() - targetFps)
                                    < Math.abs(closest - targetFps))) {
                        closest = mode.getRefreshRate();
                    }
                }
                if (closest > 0f) {
                    WindowManager.LayoutParams attrs = activity.getWindow().getAttributes();
                    attrs.preferredRefreshRate = closest;
                    activity.getWindow().setAttributes(attrs);
                }
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Some vendors reject refresh hints during surface transitions.
            // The native limiter still applies, and surface creation must continue.
            RunLog.append(getContext(), "Refresh hint unavailable: " + e.getMessage());
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        RunLog.append(getContext(), "AuroraSurface: surfaceDestroyed begin");
        nativeSetSurfaceReady(false);
        super.surfaceDestroyed(holder);
        RunLog.append(getContext(), "AuroraSurface: surfaceDestroyed end");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        RunLog.append(getContext(), "AuroraSurface: surfaceChanged begin " + width + "x" + height + " format=" + format);
        nativeSetSurfaceReady(false);
        super.surfaceChanged(holder, format, width, height);
        RunLog.append(getContext(), "AuroraSurface: SDL ready=" + mIsSurfaceReady);
        nativeSetSurfaceReady(mIsSurfaceReady);
        if (getContext() instanceof StrikersActivity) {
            ((StrikersActivity) getContext()).recoverTouchOverlayAfterSurfaceChange();
        }
        RunLog.append(getContext(), "AuroraSurface: surfaceChanged end");
    }
}
