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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Java-only launcher and Android settings screen.
 *
 * The ROM picker is only needed on first run (or when the user explicitly changes
 * the ROM). Language and internal render resolution are chosen here before each
 * launch, while the native game remains isolated in the private :game process.
 */
public final class MainActivity extends Activity {
    private static final int PICK_GAME_IMAGE = 1001;

    static final String PREFS = "strikers_android";
    static final String PREF_GAME_URI = "game_image_uri";
    static final String PREF_LANGUAGE = "game_language";
    static final String PREF_RESOLUTION_ROWS = "render_rows";

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
            "Auto · pantalla (máx. 1080p)"
    };
    // 0 means automatic: use the device's short side, capped at 1080 rows.
    private static final int[] RESOLUTION_ROWS = {448, 720, 900, 1080, 0};

    private TextView statusView;
    private Spinner languageSpinner;
    private Spinner resolutionSpinner;
    private Button chooseGameButton;
    private Button playGameButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(PREF_GAME_URI, null);
        if (saved != null && !canOpenSavedImage(saved)) {
            prefs.edit().remove(PREF_GAME_URI).apply();
        }

        // Unlike the previous automatic launch, keep this small settings screen
        // visible so resolution/language can be changed before entering the game.
        // The Android document picker itself is still only first-run/fallback.
        setContentView(buildLauncherView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null) {
            updateLauncherControls();
            appendLastRunLog();
        }
    }

    private View buildLauncherView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(13, 15, 19));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(24), dp(28), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("STRIKERS ANDROID");
        title.setTextColor(Color.WHITE);
        title.setTextSize(27f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("Native ARM64 · Vulkan/WebGPU · SDL3 / Aurora");
        subtitle.setTextColor(Color.rgb(145, 155, 170));
        subtitle.setTextSize(13f);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(8), 0, dp(14));
        root.addView(subtitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(205, 210, 220));
        statusView.setTextSize(14f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, 0, 0, dp(14));
        root.addView(statusView, new LinearLayout.LayoutParams(
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

        chooseGameButton = new Button(this);
        chooseGameButton.setAllCaps(false);
        chooseGameButton.setOnClickListener(v -> chooseGameImage());
        LinearLayout.LayoutParams chooseParams = buttonParams();
        chooseParams.topMargin = dp(18);
        root.addView(chooseGameButton, chooseParams);

        playGameButton = new Button(this);
        playGameButton.setText("Jugar");
        playGameButton.setAllCaps(false);
        playGameButton.setOnClickListener(v -> launchGame());
        LinearLayout.LayoutParams playParams = buttonParams();
        playParams.topMargin = dp(10);
        root.addView(playGameButton, playParams);

        TextView note = new TextView(this);
        note.setText("720p es el valor recomendado para evitar que el teléfono renderice el juego innecesariamente a 1080p. "
                + "El idioma del sistema solo cambia el disco europeo (G4QP01). La ROM queda recordada; usa Cambiar ROM solo cuando quieras reemplazarla.");
        note.setTextColor(Color.rgb(125, 135, 150));
        note.setTextSize(12f);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        updateLauncherControls();
        return scroll;
    }

    private void addSectionLabel(LinearLayout root, String value) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextColor(Color.WHITE);
        label.setTextSize(14f);
        label.setPadding(0, dp(10), 0, dp(4));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                selectorWidth(), LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(label, params);
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

        if (statusView != null) {
            int rows = selectedResolutionRows();
            String resolution = rows == 0 ? "Auto" : rows + "p";
            statusView.setText(hasGame
                    ? "ROM: lista · Resolución: " + resolution
                    : "Primera configuración: selecciona tu imagen del juego");
        }
        if (chooseGameButton != null) {
            chooseGameButton.setText(hasGame
                    ? "Cambiar ROM"
                    : "Seleccionar ISO / GCM / CISO / GCZ");
        }
        if (playGameButton != null) {
            playGameButton.setEnabled(hasGame);
        }
    }

    private void appendLastRunLog() {
        File log = RunLog.file(this);
        if (!log.isFile() || log.length() == 0) {
            return;
        }

        ArrayDeque<String> important = new ArrayDeque<>();
        boolean rendererStarted = false;
        boolean gameActivityStarted = false;
        try (BufferedReader reader = new BufferedReader(new FileReader(log))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("[port] render target")
                        || line.contains("CARD API Initialized")) {
                    rendererStarted = true;
                }
                if (line.contains("SDL activity: onCreate AFTER")) {
                    gameActivityStarted = true;
                }

                if (isRealFailureLine(line)) {
                    if (important.size() == 6) {
                        important.removeFirst();
                    }
                    important.addLast(line);
                }
            }
        } catch (IOException ignored) {
            return;
        }

        if (!important.isEmpty()) {
            StringBuilder text = new StringBuilder("\n\nÚltimo arranque: fallo detectado:\n");
            for (String line : important) {
                text.append(line).append('\n');
            }
            statusView.append(text.toString().trim());
        } else if (rendererStarted) {
            statusView.append("\n\nÚltimo arranque: renderer activo.");
        } else if (gameActivityStarted) {
            statusView.append("\n\nÚltimo arranque: núcleo iniciado sin fallo fatal registrado.");
        }
    }

    private boolean isRealFailureLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("unhandled xf")
                || lower.contains("unhandled bp")
                || lower.contains("surface texture is error, dropping surface")
                || lower.contains("skipping present; window not presentable")
                || lower.contains("surfacedestroyed")
                || lower.contains("onpause")
                || lower.contains("onstop")) {
            return false;
        }

        return lower.contains("sigsegv")
                || lower.contains("sigabrt")
                || lower.contains("*** strikers android: early native load crash")
                || lower.contains("disc validation failed")
                || lower.contains("load failed")
                || lower.contains("startactivity failed")
                || lower.contains("startactivity falló")
                || lower.contains("uncaught exception")
                || lower.contains("[error]")
                || lower.contains("fatal error");
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
            // The current one-shot grant is still usable for this launch.
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_GAME_URI, image.toString())
                .apply();
        updateLauncherControls();
        Toast.makeText(this, "ROM guardada. Elige idioma/resolución y pulsa Jugar.",
                Toast.LENGTH_SHORT).show();
    }

    private boolean launchGame() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(PREF_GAME_URI, null);
        if (saved == null) {
            Toast.makeText(this, "Selecciona primero tu imagen de Super Mario Strikers.",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!canOpenSavedImage(saved)) {
            prefs.edit().remove(PREF_GAME_URI).apply();
            updateLauncherControls();
            Toast.makeText(this, "Android perdió el acceso a la ROM. Selecciónala de nuevo.",
                    Toast.LENGTH_LONG).show();
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
                .apply();

        RunLog.reset(this, "launcher: Jugar; language=" + language
                + " render_rows=" + (rows == 0 ? "auto" : rows));

        Intent game = new Intent();
        game.setClassName(getPackageName(), getPackageName() + ".GameBootstrapActivity");
        game.putExtra(GameBootstrapActivity.EXTRA_GAME_URI, saved);
        game.setData(Uri.parse(saved));
        game.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(game);
            return true;
        } catch (Exception e) {
            RunLog.append(this, "launcher: startActivity falló: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            Toast.makeText(this, "No se pudo iniciar el proceso del juego: "
                    + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private int selectedResolutionRows() {
        if (resolutionSpinner == null) {
            return getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getInt(PREF_RESOLUTION_ROWS, 720);
        }
        int index = resolutionSpinner.getSelectedItemPosition();
        if (index < 0 || index >= RESOLUTION_ROWS.length) {
            return 720;
        }
        return RESOLUTION_ROWS[index];
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
        return 1; // 720p recommended default
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
