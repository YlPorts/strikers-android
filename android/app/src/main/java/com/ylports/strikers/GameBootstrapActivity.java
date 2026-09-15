package com.ylports.strikers;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;

/**
 * Runs in the private :game process before SDLActivity exists.
 *
 * This is intentionally a normal Activity: it opens and validates the SAF descriptor,
 * exports the native environment, then starts SDL. If SDL/Aurora crashes afterwards,
 * the Java launcher in the main process stays alive.
 */
public final class GameBootstrapActivity extends Activity {
    public static final String EXTRA_GAME_URI = "com.ylports.strikers.GAME_URI";
    public static final String LAST_RUN_LOG = RunLog.FILE_NAME;

    // Keep the descriptor alive for the lifetime of the :game process. libstrikers.so
    // re-opens /proc/self/fd/N and needs the underlying SAF descriptor to remain valid.
    private static ParcelFileDescriptor gameImageFd;

    private static native void nativeInstallCrashDiagnostics(String logPath);
    private static native String nativeValidateDiscPath(String nativePath);
    private static native void nativeBeginRunLog(String logPath);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        RunLog.installJavaCrashHandler(this);
        RunLog.append(this, "bootstrap: onCreate before Activity.onCreate");
        super.onCreate(savedInstanceState);
        RunLog.append(this, "bootstrap: Activity.onCreate complete");
        showPreparing("Preparando Super Mario Strikers…");

        // libstrikers.so is large and has many C/C++ static initializers. Loading it
        // on the Activity thread can cross Android's responsiveness window on slower
        // phones. Keep the UI alive while the dynamic loader does its work.
        Thread loader = new Thread(this::prepareAndLaunch, "strikers-native-loader");
        loader.start();
    }

    private void showPreparing(String message) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> showPreparing(message));
            return;
        }

        RunLog.append(this, "bootstrap UI: " + message);
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(Color.WHITE);
        text.setTextSize(18f);
        text.setGravity(Gravity.CENTER);
        text.setBackgroundColor(Color.rgb(8, 10, 14));
        text.setPadding(dp(24), dp(24), dp(24), dp(24));
        setContentView(text);
    }

    private void prepareAndLaunch() {
        String rawUri = getIntent().getStringExtra(EXTRA_GAME_URI);
        if (rawUri == null || rawUri.isEmpty()) {
            RunLog.append(this, "bootstrap: no GAME_URI extra");
            showError("No se recibió una imagen del juego.");
            return;
        }

        try {
            RunLog.append(this, "bootstrap: opening SAF descriptor");
            closeGameImageFd();
            Uri uri = Uri.parse(rawUri);
            gameImageFd = getContentResolver().openFileDescriptor(uri, "r");
            if (gameImageFd == null) {
                throw new IOException("Android no devolvió descriptor");
            }
            RunLog.append(this, "bootstrap: descriptor opened fd=" + gameImageFd.getFd());

            // The disc reader performs random seeks. Cloud-only providers sometimes
            // expose a pipe instead; reject those before loading the native runtime.
            Os.lseek(gameImageFd.getFileDescriptor(), 0, OsConstants.SEEK_SET);
            String nativePath = "/proc/self/fd/" + gameImageFd.getFd();
            RunLog.append(this, "bootstrap: descriptor is seekable; native path ready");

            // Important: some Strikers/Aurora initialization can happen while the shared
            // library is being loaded, before SDL_main. Export every variable first.
            File runLog = RunLog.file(this);
            Os.setenv("STRIKERS_DATA", nativePath, true);
            Os.setenv("STRIKERS_FULLSCREEN", "1", true);
            Os.setenv("STRIKERS_NO_MESSAGEBOX", "1", true);
            Os.setenv("STRIKERS_CRASH_LOG", runLog.getAbsolutePath(), true);
            // Keep the desktop crash handler from replacing strikers_diag halfway through
            // libstrikers.so's static constructors. The early Android handler below owns
            // fatal signals for the entire load and writes them into the durable run log.
            Os.setenv("STRIKERS_NO_CRASH_HANDLER", "1", true);
            RunLog.append(this, "bootstrap: native environment and early crash log exported before library load");

            // Install a tiny signal handler from a separate library before loading either
            // SDL/Aurora or the game. If a C/C++ static initializer kills the :game process,
            // it appends PC/LR/SP plus /proc/self/maps to the durable run log.
            try {
                RunLog.append(this, "bootstrap: System.loadLibrary(strikers_diag) begin");
                System.loadLibrary("strikers_diag");
                nativeInstallCrashDiagnostics(runLog.getAbsolutePath());
                RunLog.append(this, "bootstrap: early native crash diagnostics armed");
            } catch (UnsatisfiedLinkError e) {
                RunLog.append(this, "bootstrap: crash diagnostic library load failed: " + safeMessage(e));
                closeGameImageFd();
                showError("No se pudo cargar el diagnóstico nativo.\n\n" + safeMessage(e));
                return;
            }

            showPreparing("Cargando SDL3…");
            try {
                RunLog.append(this, "bootstrap: System.loadLibrary(SDL3) begin");
                System.loadLibrary("SDL3");
                RunLog.append(this, "bootstrap: System.loadLibrary(SDL3) OK");
            } catch (UnsatisfiedLinkError e) {
                RunLog.append(this, "bootstrap: SDL3 load failed: " + safeMessage(e));
                closeGameImageFd();
                showError("No se pudo cargar SDL3.\n\n" + safeMessage(e));
                return;
            }

            showPreparing("Cargando núcleo de Super Mario Strikers…");
            try {
                RunLog.append(this, "bootstrap: System.loadLibrary(strikers) begin");
                System.loadLibrary("strikers");
                RunLog.append(this, "bootstrap: System.loadLibrary(strikers) OK");
            } catch (UnsatisfiedLinkError e) {
                RunLog.append(this, "bootstrap: Strikers native library load failed: " + safeMessage(e));
                closeGameImageFd();
                showError("No se pudo cargar el núcleo nativo ARM64.\n\n" + safeMessage(e));
                return;
            }

            showPreparing("Verificando imagen de Super Mario Strikers…");
            RunLog.append(this, "bootstrap: validating disc with native reader");
            String validationError = nativeValidateDiscPath(nativePath);
            if (validationError != null && !validationError.isEmpty()) {
                RunLog.append(this, "bootstrap: disc validation failed: " + validationError.replace('\n', ' '));
                closeGameImageFd();
                showError(validationError);
                return;
            }
            RunLog.append(this, "bootstrap: disc validation OK");

            RunLog.append(this, "bootstrap: attaching native stderr to durable log");
            nativeBeginRunLog(runLog.getAbsolutePath());
            RunLog.append(this, "bootstrap: native stderr attached");

            Intent nativeGame = new Intent();
            nativeGame.setClassName(getPackageName(), getPackageName() + ".StrikersActivity");
            runOnUiThread(() -> {
                try {
                    RunLog.append(this, "bootstrap: starting StrikersActivity");
                    startActivity(nativeGame);
                    RunLog.append(this, "bootstrap: startActivity returned; finishing bootstrap");
                    finish();
                } catch (Exception e) {
                    RunLog.append(this, "bootstrap: startActivity failed: "
                            + e.getClass().getSimpleName() + ": " + safeMessage(e));
                    showError("No se pudo abrir la actividad nativa.\n\n"
                            + e.getClass().getSimpleName() + ": " + safeMessage(e));
                }
            });
        } catch (ErrnoException e) {
            RunLog.append(this, "bootstrap: descriptor is not seekable: " + safeMessage(e));
            closeGameImageFd();
            showError("La imagen no permite acceso aleatorio. Muévela al almacenamiento interno del teléfono e inténtalo de nuevo.");
        } catch (Exception e) {
            RunLog.append(this, "bootstrap: exception " + e.getClass().getSimpleName() + ": " + safeMessage(e));
            closeGameImageFd();
            showError("No se pudo preparar la imagen del juego.\n\n" + e.getClass().getSimpleName() + ": " + safeMessage(e));
        }
    }

    private void showError(String message) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> showError(message));
            return;
        }

        RunLog.append(this, "bootstrap: showing error screen");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setBackgroundColor(Color.rgb(13, 15, 19));

        TextView title = new TextView(this);
        title.setText("No se pudo iniciar el juego");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView details = new TextView(this);
        details.setText(message);
        details.setTextColor(Color.rgb(200, 205, 215));
        details.setTextSize(14f);
        details.setGravity(Gravity.CENTER);
        details.setPadding(0, dp(16), 0, dp(20));
        root.addView(details);

        Button back = new Button(this);
        back.setText("Volver");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        root.addView(back);

        setContentView(root);
    }

    private static String safeMessage(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? "sin detalles" : message;
    }

    private static void closeGameImageFd() {
        if (gameImageFd == null) {
            return;
        }
        try {
            gameImageFd.close();
        } catch (IOException ignored) {
        }
        gameImageFd = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
