package com.ylports.strikers;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int PICK_GAME_TREE = 1001;
    private static final String PREFS = "strikers_android";
    private static final String PREF_GAME_TREE = "game_tree_uri";

    static {
        // The APK now packages the same full native target validated by
        // android-full-link: cmake/android -> libstrikers.so.
        System.loadLibrary("strikers");
    }

    private TextView statusView;

    private static native void nativeBootstrapInit(String filesDir);
    private static native String nativeBuildInfo();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersiveMode();

        nativeBootstrapInit(getFilesDir().getAbsolutePath());
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
        chooseGame.setText("Seleccionar carpeta extraída del juego");
        chooseGame.setAllCaps(false);
        chooseGame.setOnClickListener(v -> chooseGameTree());

        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                Math.min(dp(440), getResources().getDisplayMetrics().widthPixels - dp(64)),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(chooseGame, buttonParams);

        TextView note = new TextView(this);
        note.setText("Build de desarrollo: núcleo completo ARM64 empaquetado. Siguiente etapa: arrancar Aurora/SDL y presentar el render del juego.");
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
        StringBuilder status = new StringBuilder(nativeBuildInfo());
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_GAME_TREE, null);
        if (saved == null) {
            status.append("\nDatos del juego: sin seleccionar");
        } else {
            status.append("\nDatos del juego: seleccionados");
        }
        return status.toString();
    }

    private void chooseGameTree() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, PICK_GAME_TREE);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_GAME_TREE || resultCode != RESULT_OK || data == null) {
            return;
        }

        Uri tree = data.getData();
        if (tree == null) {
            return;
        }

        int takeFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(tree, takeFlags);
        } catch (SecurityException ignored) {
            // Some document providers grant access for the process but do not implement persistence.
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_GAME_TREE, tree.toString())
                .apply();
        statusView.setText(nativeBuildInfo() + "\nDatos del juego: seleccionados");
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
