package dev.encounter.aurora;

import android.content.Context;
import android.view.SurfaceHolder;

import com.ylports.strikers.RunLog;

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
        RunLog.append(getContext(), "AuroraSurface: surfaceCreated end");
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
        RunLog.append(getContext(), "AuroraSurface: surfaceChanged end");
    }
}
