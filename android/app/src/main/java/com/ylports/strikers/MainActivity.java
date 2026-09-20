package com.ylports.strikers;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/** Clean launcher for the Android release build. */
public final class MainActivity extends Activity {
    private static final int PICK_GAME_IMAGE = 1001;

    static final String PREFS = "strikers_android";
    static final String PREF_GAME_URI = "game_image_uri";
    static final String PREF_LANGUAGE = "game_language";
    static final String PREF_RESOLUTION_ROWS = "render_rows";
    static final String PREF_AUTO_HIDE_TOUCH = "auto_hide_touch_with_gamepad";

    private static final String[] LANGUAGE_LABELS = {
            "English", "Español", "Français", "Deutsch", "Italiano"
    };
    private static final String[] LANGUAGE_VALUES = {
            "english", "spanish", "french", "german", "italian"
    };

    private static final String[] RESOLUTION_LABELS = {
            "448p · rendimiento",
            "720p · recomendado",
            "900p · equilibrado",
            "1080p · calidad",
            "Auto · pantalla"
    };
    private static final int[] RESOLUTION_ROWS = {448, 720, 900, 1080, 0};

    private Spinner languageSpinner;
    private Spinner resolutionSpinner;
    private Button chooseGameButton;
    private Button playGameButton;
    private CheckBox autoHideTouch;
    private boolean launchPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(PREF_GAME_URI, null);
        if (saved != null && !canOpenSavedImage(saved)) {
            prefs.edit().remove(PREF_GAME_URI).apply();
        }
        setContentView(buildLauncherView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        launchPending = false;
        updateLauncherControls();
    }

    private View buildLauncherView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(13, 15, 19));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(38), dp(28), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Strikers Android");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30f);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(26));
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        addSectionLabel(root, "Idioma");
        languageSpinner = new Spinner(this);
        ArrayAdapter<String> languageAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, LANGUAGE_LABELS);
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(languageAdapter);
        root.addView(languageSpinner, selectorParams());

        addSectionLabel(root, "Resolución interna");
        resolutionSpinner = new Spinner(this);
        ArrayAdapter<String> resolutionAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, RESOLUTION_LABELS);
        resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolutionSpinner.setAdapter(resolutionAdapter);
        root.addView(resolutionSpinner, selectorParams());

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        languageSpinner.setSelection(languageIndex(
                prefs.getString(PREF_LANGUAGE, "english")));
        resolutionSpinner.setSelection(resolutionIndex(
                prefs.getInt(PREF_RESOLUTION_ROWS, 720)));

        autoHideTouch = new CheckBox(this);
        autoHideTouch.setText("Ocultar botones táctiles al conectar un mando");
        autoHideTouch.setTextColor(Color.WHITE);
        autoHideTouch.setChecked(prefs.getBoolean(PREF_AUTO_HIDE_TOUCH, false));
        root.addView(autoHideTouch, selectorParams());

        chooseGameButton = new Button(this);
        chooseGameButton.setAllCaps(false);
        chooseGameButton.setOnClickListener(v -> chooseGameImage());
        LinearLayout.LayoutParams chooseParams = buttonParams();
        chooseParams.topMargin = dp(24);
        root.addView(chooseGameButton, chooseParams);

        playGameButton = new Button(this);
        playGameButton.setText("Jugar");
        playGameButton.setAllCaps(false);
        playGameButton.setOnClickListener(v -> launchGame());
        LinearLayout.LayoutParams playParams = buttonParams();
        playParams.topMargin = dp(10);
        root.addView(playGameButton, playParams);

        updateLauncherControls();
        return scroll;
    }

    private void addSectionLabel(LinearLayout root, String value) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextColor(Color.WHITE);
        label.setTextSize(14f);
        label.setPadding(0, dp(12), 0, dp(4));
        root.addView(label, new LinearLayout.LayoutParams(
                selectorWidth(), LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private LinearLayout.LayoutParams selectorParams() {
        return new LinearLayout.LayoutParams(selectorWidth(), LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int selectorWidth() {
        int width = getResources().getDisplayMetrics().widthPixels - dp(56);
        return Math.max(dp(220), Math.min(dp(440), width));
    }

    private LinearLayout.LayoutParams buttonParams() {
        return new LinearLayout.LayoutParams(selectorWidth(), LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void updateLauncherControls() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_GAME_URI, null);
        boolean hasGame = saved != null;
        if (chooseGameButton != null) {
            chooseGameButton.setText(hasGame ? "Cambiar ROM" : "Seleccionar ROM");
        }
        if (playGameButton != null) {
            playGameButton.setEnabled(hasGame && !launchPending);
        }
    }

    private boolean canOpenSavedImage(String saved) {
        try (ParcelFileDescriptor descriptor =
                     getContentResolver().openFileDescriptor(Uri.parse(saved), "r")) {
            return descriptor != null;
        } catch (Exception e) {
            RunLog.append(this, "launcher: saved image is no longer accessible: "
                    + e.getClass().getSimpleName());
            return false;
        }
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
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_GAME_URI, image.toString())
                .apply();
        updateLauncherControls();
    }

    private boolean launchGame() {
        if (launchPending) return false;
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(PREF_GAME_URI, null);
        if (saved == null) {
            Toast.makeText(this, "Selecciona una ROM primero.", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!canOpenSavedImage(saved)) {
            prefs.edit().remove(PREF_GAME_URI).apply();
            updateLauncherControls();
            Toast.makeText(this, "Selecciona la ROM de nuevo.", Toast.LENGTH_LONG).show();
            return false;
        }

        int languageIndex = languageSpinner != null ? languageSpinner.getSelectedItemPosition() : 0;
        int resolutionIndex = resolutionSpinner != null ? resolutionSpinner.getSelectedItemPosition() : 1;
        languageIndex = Math.max(0, Math.min(LANGUAGE_VALUES.length - 1, languageIndex));
        resolutionIndex = Math.max(0, Math.min(RESOLUTION_ROWS.length - 1, resolutionIndex));

        String language = LANGUAGE_VALUES[languageIndex];
        int rows = RESOLUTION_ROWS[resolutionIndex];
        prefs.edit()
                .putString(PREF_LANGUAGE, language)
                .putInt(PREF_RESOLUTION_ROWS, rows)
                .putBoolean(PREF_AUTO_HIDE_TOUCH, autoHideTouch.isChecked())
                .apply();

        RunLog.reset(this, "launcher: Jugar; language=" + language
                + " render_rows=" + (rows == 0 ? "auto" : rows));

        Intent game = new Intent();
        game.setClassName(getPackageName(), getPackageName() + ".GameBootstrapActivity");
        game.putExtra(GameBootstrapActivity.EXTRA_GAME_URI, saved);
        game.putExtra(GameBootstrapActivity.EXTRA_LANGUAGE, language);
        game.putExtra(GameBootstrapActivity.EXTRA_RENDER_ROWS, rows);
        game.putExtra(GameBootstrapActivity.EXTRA_AUTO_HIDE_TOUCH, autoHideTouch.isChecked());
        game.setData(Uri.parse(saved));
        game.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            launchPending = true;
            updateLauncherControls();
            startActivity(game);
            return true;
        } catch (Exception e) {
            launchPending = false;
            updateLauncherControls();
            RunLog.append(this, "launcher: startActivity failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            Toast.makeText(this, "No se pudo iniciar el juego.", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private static int languageIndex(String value) {
        for (int i = 0; i < LANGUAGE_VALUES.length; i++) {
            if (LANGUAGE_VALUES[i].equalsIgnoreCase(value)) {
                return i;
            }
        }
        return 0;
    }

    private static int resolutionIndex(int rows) {
        for (int i = 0; i < RESOLUTION_ROWS.length; i++) {
            if (RESOLUTION_ROWS[i] == rows) {
                return i;
            }
        }
        return 1;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
