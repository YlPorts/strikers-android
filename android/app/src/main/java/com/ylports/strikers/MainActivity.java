package com.ylports.strikers;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayDeque;

/**
 * Deliberately tiny Java-only launcher.
 *
 * Keep this activity free of SDL, Aurora and native-library references so a native
 * loader failure can never prevent the launcher from opening. The actual game is
 * started in the private :game process by GameBootstrapActivity.
 */
public final class MainActivity extends Activity {
    private static final int PICK_GAME_IMAGE = 1001;
    private static final String PREFS = "strikers_android";
    private static final String PREF_GAME_URI = "game_image_uri";

    private TextView statusView;
    private boolean gameLaunchPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLauncherView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null) {
            updateStatus();
            if (gameLaunchPending) {
                gameLaunchPending = false;
                appendLastRunLog();
            }
        }
    }

    private View buildLauncherView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(24), dp(28), dp(24));
        root.setBackgroundColor(Color.rgb(13, 15, 19));

        TextView title = new TextView(this);
        title.setText("STRIKERS ANDROID");
        title.setTextColor(Color.WHITE);
        title.setTextSize(27f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("Native ARM64 · SDL3 / Aurora");
        subtitle.setTextColor(Color.rgb(145, 155, 170));
        subtitle.setTextSize(13f);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(8), 0, dp(18));
        root.addView(subtitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(205, 210, 220));
        statusView.setTextSize(15f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, 0, 0, dp(22));
        updateStatus();
        root.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button chooseGame = new Button(this);
        chooseGame.setText("Seleccionar ISO / GCM / CISO / GCZ");
        chooseGame.setAllCaps(false);
        chooseGame.setOnClickListener(v -> chooseGameImage());
        root.addView(chooseGame, buttonParams());

        Button playGame = new Button(this);
        playGame.setText("Jugar");
        playGame.setAllCaps(false);
        playGame.setOnClickListener(v -> launchGame());
        LinearLayout.LayoutParams playParams = buttonParams();
        playParams.topMargin = dp(12);
        root.addView(playGame, playParams);

        TextView note = new TextView(this);
        note.setText("Si el proceso del juego vuelve a cerrarse, aquí aparecerán las últimas líneas del arranque nativo.");
        note.setTextColor(Color.rgb(125, 135, 150));
        note.setTextSize(12f);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private LinearLayout.LayoutParams buttonParams() {
        int width = getResources().getDisplayMetrics().widthPixels - dp(56);
        width = Math.max(dp(220), Math.min(dp(440), width));
        return new LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void updateStatus() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_GAME_URI, null);
        statusView.setText(saved == null
                ? "Launcher listo\nImagen del juego: sin seleccionar"
                : "Launcher listo\nImagen del juego: seleccionada");
    }

    private void appendLastRunLog() {
        File log = new File(getFilesDir(), GameBootstrapActivity.LAST_RUN_LOG);
        if (!log.isFile() || log.length() == 0) {
            return;
        }

        ArrayDeque<String> tail = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(log))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == 14) {
                    tail.removeFirst();
                }
                tail.addLast(line);
            }
        } catch (IOException e) {
            statusView.append("\n\nNo se pudo leer last-run.log: " + e.getClass().getSimpleName());
            return;
        }

        if (tail.isEmpty()) {
            return;
        }

        StringBuilder text = new StringBuilder("\n\nÚltimo arranque nativo:\n");
        for (String line : tail) {
            text.append(line).append('\n');
        }
        statusView.append(text.toString().trim());
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
            // The one-shot grant is still forwarded to the private game activity.
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PREF_GAME_URI, image.toString())
                .apply();
        updateStatus();
    }

    private void launchGame() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_GAME_URI, null);
        if (saved == null) {
            Toast.makeText(this, "Selecciona primero tu imagen de Super Mario Strikers.", Toast.LENGTH_SHORT).show();
            return;
        }

        File oldLog = new File(getFilesDir(), GameBootstrapActivity.LAST_RUN_LOG);
        if (oldLog.exists()) {
            oldLog.delete();
        }

        Intent game = new Intent();
        game.setClassName(getPackageName(), getPackageName() + ".GameBootstrapActivity");
        game.putExtra(GameBootstrapActivity.EXTRA_GAME_URI, saved);
        game.setData(Uri.parse(saved));
        game.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            gameLaunchPending = true;
            startActivity(game);
        } catch (Exception e) {
            gameLaunchPending = false;
            Toast.makeText(this, "No se pudo iniciar el proceso del juego: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
