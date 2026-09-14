package com.ylports.strikers;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

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

    // Keep the descriptor alive for the lifetime of the :game process. libstrikers.so
    // re-opens /proc/self/fd/N and needs the underlying SAF descriptor to remain valid.
    private static ParcelFileDescriptor gameImageFd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showPreparing();
        prepareAndLaunch();
    }

    private void showPreparing() {
        TextView text = new TextView(this);
        text.setText("Preparando Super Mario Strikers…");
        text.setTextColor(Color.WHITE);
        text.setTextSize(18f);
        text.setGravity(Gravity.CENTER);
        text.setBackgroundColor(Color.rgb(8, 10, 14));
        setContentView(text);
    }

    private void prepareAndLaunch() {
        String rawUri = getIntent().getStringExtra(EXTRA_GAME_URI);
        if (rawUri == null || rawUri.isEmpty()) {
            showError("No se recibió una imagen del juego.");
            return;
        }

        try {
            closeGameImageFd();
            Uri uri = Uri.parse(rawUri);
            gameImageFd = getContentResolver().openFileDescriptor(uri, "r");
            if (gameImageFd == null) {
                throw new IOException("Android no devolvió descriptor");
            }

            // The disc reader performs random seeks. Cloud-only providers sometimes
            // expose a pipe instead; reject those before loading a 32 MB native library.
            Os.lseek(gameImageFd.getFileDescriptor(), 0, OsConstants.SEEK_SET);

            String nativePath = "/proc/self/fd/" + gameImageFd.getFd();
            Os.setenv("STRIKERS_DATA", nativePath, true);
            Os.setenv("STRIKERS_FULLSCREEN", "1", true);

            Intent nativeGame = new Intent();
            nativeGame.setClassName(getPackageName(), getPackageName() + ".StrikersActivity");
            startActivity(nativeGame);
            finish();
        } catch (ErrnoException e) {
            closeGameImageFd();
            showError("La imagen no permite acceso aleatorio. Muévela al almacenamiento interno del teléfono e inténtalo de nuevo.");
        } catch (Exception e) {
            closeGameImageFd();
            showError("No se pudo preparar la imagen del juego.\n\n" + e.getClass().getSimpleName() + ": " + safeMessage(e));
        }
    }

    private void showError(String message) {
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

    private static String safeMessage(Exception e) {
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
