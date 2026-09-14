package com.ylports.strikers;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

public final class MainActivity extends Activity {
    private static final int PICK_GAME_IMAGE = 1001;
    private static final String PREFS = "strikers_android";
    private static final String PREF_GAME_URI = "game_image_uri";

    // Keep the SAF descriptor alive while Strikers is running. The native port opens
    // /proc/self/fd/N as a normal seekable disc image, avoiding a 1.4 GB copy.
    private static ParcelFileDescriptor gameImageFd;

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersiveMode();
        setContentView(buildLauncherView());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    private View buildLauncherView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(32), dp(24), dp(32), dp(24));
        root.setBackgroundColor(Color.rgb(13, 15, 19));

        TextView title = new TextView(this);
        title.setText("STRIKERS ANDROID");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(190, 198, 210));
        statusView.setTextSize(15f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(14), 0, dp(24));
        statusView.setText(initialStatus());
        root.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button chooseGame = new Button(this);
        chooseGame.setText("Seleccionar ISO / GCM / CISO / GCZ");
        chooseGame.setAllCaps(false);
        chooseGame.setOnClickListener(v -> chooseGameImage());

        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                Math.min(dp(440), getResources().getDisplayMetrics().widthPixels - dp(64)),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(chooseGame, buttonParams);

        Button playGame = new Button(this);
        playGame.setText("Jugar");
        playGame.setAllCaps(false);
        playGame.setOnClickListener(v -> launchGame());
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(
                Math.min(dp(440), getResources().getDisplayMetrics().widthPixels - dp(64)),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        playParams.topMargin = dp(12);
        root.addView(playGame, playParams);

        TextView note = new TextView(this);
        note.setText("Build de desarrollo: núcleo completo + SDL3/Aurora. La imagen se usa directamente desde Android sin copiarla al almacenamiento interno.");
        note.setTextColor(Color.rgb(130, 138, 150));
        note.setTextSize(12f);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(20), 0, 0);
        root.addView(note, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private String initialStatus() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_GAME_URI, null);
        if (saved == null) {
            return "Núcleo ARM64 listo · SDL/Aurora conectado\nImagen del juego: sin seleccionar";
        }
        return "Núcleo ARM64 listo · SDL/Aurora conectado\nImagen del juego: seleccionada";
    }

    private void chooseGameImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_GAME_IMAGE);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_GAME_IMAGE || resultCode != RESULT_OK || data == null) {
            return;
        }

        Uri image = data.getData();
        if (image == null) {
            return;
        }

        int takeFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(image, takeFlags);
        } catch (SecurityException ignored) {
            // Some document providers keep the descriptor usable for this process but
            // do not implement persistable grants.
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_GAME_URI, image.toString())
                .apply();
        statusView.setText("Núcleo ARM64 listo · SDL/Aurora conectado\nImagen del juego: seleccionada");
    }

    private void launchGame() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_GAME_URI, null);
        if (saved == null) {
            Toast.makeText(this, "Selecciona primero tu imagen de Super Mario Strikers.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            closeGameImageFd();
            gameImageFd = getContentResolver().openFileDescriptor(Uri.parse(saved), "r");
            if (gameImageFd == null) {
                throw new IOException("Android no devolvió un descriptor para la imagen");
            }

            // The disc reader needs random access. Local files and normal document
            // providers are seekable; some cloud providers expose a one-way pipe.
            // Detect that here instead of letting the native reader fail later.
            Os.lseek(gameImageFd.getFileDescriptor(), 0, OsConstants.SEEK_SET);

            String nativePath = "/proc/self/fd/" + gameImageFd.getFd();
            Os.setenv("STRIKERS_DATA", nativePath, true);
            Os.setenv("STRIKERS_FULLSCREEN", "1", true);

            statusView.setText("Iniciando SDL/Aurora…");
            startActivity(new Intent(this, StrikersActivity.class));
        } catch (Exception e) {
            closeGameImageFd();
            statusView.setText("No se pudo abrir la imagen del juego\n" + e.getClass().getSimpleName());
            Toast.makeText(
                    this,
                    "No se pudo usar esa imagen. Si está en la nube, guárdala primero en el teléfono.",
                    Toast.LENGTH_LONG)
                    .show();
        }
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

    private void enterImmersiveMode() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
