package com.ylports.strikers;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.net.Inet4Address;
import java.net.InetAddress;

/** Clean launcher for the Android release build. */
public final class MainActivity extends Activity {
    private static final int PICK_GAME_IMAGE = 1001;
    static final int PICK_SAVE_FOLDER = 1002;

    static final String PREFS = "strikers_android";
    static final String PREF_GAME_URI = "game_image_uri";
    static final String PREF_LANGUAGE = "game_language";
    static final String PREF_RESOLUTION_ROWS = "render_rows";
    static final String PREF_AUTO_HIDE_TOUCH = "auto_hide_touch_with_gamepad";
    static final String PREF_TARGET_FPS = "target_fps";
    static final String PREF_SAVE_FOLDER = "save_folder_uri";
    static final String PREF_SAVE_FOLDER_NAME = "save_folder_name";

    private static final String[] LANGUAGE_LABELS = {
            "English", "Español", "Français", "Deutsch", "Italiano"
    };
    private static final String[] LANGUAGE_VALUES = {
            "english", "spanish", "french", "german", "italian"
    };

    private static final String[] RESOLUTION_LABELS = {
            "448p", "720p", "900p", "1080p", "Automática"
    };
    private static final int[] RESOLUTION_ROWS = {448, 720, 900, 1080, 0};

    private Spinner languageSpinner;
    private Spinner resolutionSpinner;
    private Spinner frameRateSpinner;
    private Button chooseGameButton;
    private Button playGameButton;
    private Button createLanButton;
    private Button joinLanButton;
    private CheckBox autoHideTouch;
    private boolean launchPending;
    private static volatile boolean folderPending;
    private static WeakReference<MainActivity> resumedLauncher = new WeakReference<>(null);
    private TextView saveFolderLabel;
    private Button saveFolderButton;
    private Button internalSavesButton;

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
        resumedLauncher = new WeakReference<>(this);
        launchPending = false;
        updateLauncherControls();
    }

    @Override protected void onPause() {
        if (resumedLauncher.get() == this) resumedLauncher.clear();
        super.onPause();
    }

    private View buildLauncherView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(13, 15, 19));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Strikers Android");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28f);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(3));
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("Juega en tu móvil o prueba una partida por Wi-Fi");
        subtitle.setTextColor(Color.rgb(166, 176, 192));
        subtitle.setTextSize(13f);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = selectorParams();
        subtitleParams.bottomMargin = dp(18);
        root.addView(subtitle, subtitleParams);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout gameCard = addCard(root);
        addSectionLabel(gameCard, "JUEGO");
        TextView gameDescription = cardDescription("Selecciona la ROM y abre el juego.");
        gameCard.addView(gameDescription, selectorParams());

        chooseGameButton = new Button(this);
        chooseGameButton.setAllCaps(false);
        chooseGameButton.setOnClickListener(v -> chooseGameImage());
        LinearLayout.LayoutParams chooseParams = buttonParams();
        chooseParams.topMargin = dp(12);
        gameCard.addView(chooseGameButton, chooseParams);

        playGameButton = new Button(this);
        playGameButton.setText("Jugar");
        playGameButton.setAllCaps(false);
        playGameButton.setOnClickListener(v -> launchGame());
        LinearLayout.LayoutParams playParams = buttonParams();
        playParams.topMargin = dp(6);
        gameCard.addView(playGameButton, playParams);

        LinearLayout lanCard = addCard(root);
        addSectionLabel(lanCard, "MULTIJUGADOR LAN · EXPERIMENTAL");
        TextView lanDescription = cardDescription(
                "Dos móviles en la misma Wi-Fi. El anfitrión será P1; quien se una, P2.");
        lanCard.addView(lanDescription, selectorParams());

        LinearLayout lanActions = new LinearLayout(this);
        lanActions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lanActionsParams = selectorParams();
        lanActionsParams.topMargin = dp(8);
        lanCard.addView(lanActions, lanActionsParams);

        createLanButton = new Button(this);
        createLanButton.setText("Crear sala");
        createLanButton.setAllCaps(false);
        createLanButton.setOnClickListener(v -> showCreateLanDialog());
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        createParams.rightMargin = dp(6);
        lanActions.addView(createLanButton, createParams);

        joinLanButton = new Button(this);
        joinLanButton.setText("Unirse");
        joinLanButton.setAllCaps(false);
        joinLanButton.setOnClickListener(v -> showJoinLanDialog());
        LinearLayout.LayoutParams joinParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        joinParams.leftMargin = dp(6);
        lanActions.addView(joinLanButton, joinParams);

        LinearLayout settingsCard = addCard(root);
        Button settingsButton = new Button(this);
        settingsButton.setText("Ajustes");
        settingsButton.setAllCaps(false);
        settingsCard.addView(settingsButton, buttonParams());

        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setGravity(Gravity.CENTER_HORIZONTAL);
        settings.setVisibility(View.GONE);
        LinearLayout.LayoutParams settingsParams = selectorParams();
        settingsParams.topMargin = dp(8);
        settingsCard.addView(settings, settingsParams);
        settingsButton.setOnClickListener(v -> {
            boolean expanded = settings.getVisibility() != View.VISIBLE;
            settings.setVisibility(expanded ? View.VISIBLE : View.GONE);
            settingsButton.setText(expanded ? "Cerrar ajustes" : "Ajustes");
        });

        addSectionLabel(settings, "RENDIMIENTO");
        resolutionSpinner = new Spinner(this);
        ArrayAdapter<String> resolutionAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, RESOLUTION_LABELS);
        resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolutionSpinner.setAdapter(resolutionAdapter);
        settings.addView(resolutionSpinner, selectorParams());
        resolutionSpinner.setSelection(resolutionIndex(
                prefs.getInt(PREF_RESOLUTION_ROWS, 720)));

        addSectionLabel(settings, "LÍMITE DE FPS");
        frameRateSpinner = new Spinner(this);
        ArrayAdapter<String> frameRateAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"60 FPS", "120 FPS"});
        frameRateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        frameRateSpinner.setAdapter(frameRateAdapter);
        frameRateSpinner.setSelection(prefs.getInt(PREF_TARGET_FPS, 60) == 120 ? 1 : 0);
        settings.addView(frameRateSpinner, selectorParams());

        addSectionLabel(settings, "Idioma");
        languageSpinner = new Spinner(this);
        ArrayAdapter<String> languageAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, LANGUAGE_LABELS);
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(languageAdapter);
        settings.addView(languageSpinner, selectorParams());

        languageSpinner.setSelection(languageIndex(prefs.getString(PREF_LANGUAGE, "english")));

        autoHideTouch = new CheckBox(this);
        autoHideTouch.setText("Ocultar controles al usar un mando");
        autoHideTouch.setTextColor(Color.WHITE);
        autoHideTouch.setChecked(prefs.getBoolean(PREF_AUTO_HIDE_TOUCH, false));
        settings.addView(autoHideTouch, selectorParams());

        Button graphicsButton = new Button(this);
        graphicsButton.setText("Gráficos");
        graphicsButton.setAllCaps(false);
        graphicsButton.setOnClickListener(v -> GraphicsSettings.show(this));
        settings.addView(graphicsButton, buttonParams());
        Button driversButton = new Button(this);
        driversButton.setText("Drivers gráficos");
        driversButton.setAllCaps(false);
        driversButton.setOnClickListener(v -> startActivity(new Intent(this, DriversActivity.class)));
        settings.addView(driversButton, buttonParams());

        addSectionLabel(settings, "Carpeta de partidas");
        saveFolderLabel = new TextView(this);
        saveFolderLabel.setTextColor(Color.LTGRAY);
        settings.addView(saveFolderLabel, selectorParams());
        saveFolderButton = new Button(this);
        saveFolderButton.setText("Elegir carpeta");
        saveFolderButton.setAllCaps(false);
        saveFolderButton.setOnClickListener(v -> chooseSaveFolder());
        settings.addView(saveFolderButton, buttonParams());
        internalSavesButton = new Button(this);
        internalSavesButton.setText("Usar partidas internas");
        internalSavesButton.setAllCaps(false);
        internalSavesButton.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove(PREF_SAVE_FOLDER).remove(PREF_SAVE_FOLDER_NAME).apply();
            updateLauncherControls();
        });
        settings.addView(internalSavesButton, buttonParams());
        Button diagnosticsButton = new Button(this);
        diagnosticsButton.setText("Informe de diagnóstico");
        diagnosticsButton.setAllCaps(false);
        diagnosticsButton.setOnClickListener(v -> CrashReport.showLatest(this));
        settings.addView(diagnosticsButton, buttonParams());

        updateLauncherControls();
        return scroll;
    }

    private LinearLayout addCard(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(16), dp(10), dp(16), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(24, 28, 36));
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), Color.rgb(43, 49, 60));
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        parent.addView(card, params);
        return card;
    }

    private TextView cardDescription(String value) {
        TextView description = new TextView(this);
        description.setText(value);
        description.setTextColor(Color.rgb(174, 183, 197));
        description.setTextSize(13f);
        description.setGravity(Gravity.START);
        return description;
    }

    private void addSectionLabel(LinearLayout root, String value) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextColor(Color.rgb(139, 159, 188));
        label.setTextSize(12f);
        label.setPadding(0, dp(7), 0, dp(5));
        root.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private LinearLayout.LayoutParams selectorParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams buttonParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void updateLauncherControls() {
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_GAME_URI, null);
        boolean hasGame = saved != null;
        if (chooseGameButton != null) {
            chooseGameButton.setText(hasGame ? "Cambiar ROM" : "Seleccionar ROM");
        }
        if (playGameButton != null) {
            playGameButton.setEnabled(hasGame && !launchPending && !folderPending);
        }
        if (createLanButton != null) {
            createLanButton.setEnabled(hasGame && !launchPending && !folderPending);
        }
        if (joinLanButton != null) {
            joinLanButton.setEnabled(hasGame && !launchPending && !folderPending);
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean external = prefs.getString(PREF_SAVE_FOLDER, null) != null;
        if (saveFolderLabel != null) saveFolderLabel.setText(external
                ? prefs.getString(PREF_SAVE_FOLDER_NAME, "Carpeta seleccionada") : "Almacenamiento de la aplicación");
        if (saveFolderButton != null) saveFolderButton.setEnabled(!folderPending && !launchPending);
        if (internalSavesButton != null) {
            internalSavesButton.setVisibility(external ? View.VISIBLE : View.GONE);
            internalSavesButton.setEnabled(!folderPending && !launchPending);
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

    private void chooseSaveFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, PICK_SAVE_FOLDER);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir el selector de carpetas.", Toast.LENGTH_LONG).show();
        }
    }

    private void acceptSaveFolder(Intent data) {
        Uri folder = data.getData();
        if (folder == null || folderPending) return;
        int required = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            if ((data.getFlags() & required) != required) throw new SecurityException("Sin permiso de escritura");
            getContentResolver().takePersistableUriPermission(folder, required);
        } catch (Exception e) {
            Toast.makeText(this, "La carpeta necesita permiso de lectura y escritura.", Toast.LENGTH_LONG).show();
            return;
        }
        folderPending = true;
        updateLauncherControls();
        String previous = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_SAVE_FOLDER, null);
        new Thread(() -> {
            String error = null;
            try {
                SaveFolder destination = new SaveFolder(getApplicationContext(), folder);
                destination.validate();
                if (!folder.toString().equals(previous)) destination.copyIfEmpty(getFilesDir(),
                        previous == null ? null : new SaveFolder(getApplicationContext(), Uri.parse(previous)));
                destination.prepareDirectories();
                String name = destination.displayName();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(PREF_SAVE_FOLDER, folder.toString()).putString(PREF_SAVE_FOLDER_NAME, name).commit();
            } catch (Exception e) {
                error = "No se pudo usar esa carpeta. Elige una carpeta local con permiso de escritura.";
                RunLog.append(this, "save folder selection failed: " + e);
            }
            final String result = error;
            runOnUiThread(() -> {
                folderPending = false;
                MainActivity current = resumedLauncher.get();
                if (current == null || current.isFinishing() || current.isDestroyed()) return;
                current.updateLauncherControls();
                Toast.makeText(current, result == null ? "Carpeta de partidas guardada." : result, Toast.LENGTH_LONG).show();
            });
        }, "strikers-save-folder").start();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_SAVE_FOLDER) {
            if (resultCode == RESULT_OK && data != null) acceptSaveFolder(data);
            return;
        }
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
        return launchGame(GameBootstrapActivity.LAN_ROLE_OFF, null, null);
    }

    private void showCreateLanDialog() {
        final String localAddress = findLocalIpv4Address();
        if (localAddress == null) {
            Toast.makeText(this, "No encontré una dirección Wi-Fi. Conéctate a la misma red en ambos móviles e inténtalo de nuevo.", Toast.LENGTH_LONG).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Crear sala LAN")
                .setMessage("Tu móvil será el jugador 1 (P1). Comparte esta dirección:\n\n"
                        + localAddress + ":" + GameBootstrapActivity.LAN_PORT
                        + "\n\nEl otro móvil debe elegir «Unirse» y será P2. Usen la misma ROM,"
                        + " modo y equipos. El indicador del juego mostrará 2/2 cuando se conecte."
                        + " El inicio de la partida aún no se sincroniza automáticamente.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Abrir anfitrión", (dialog, which) ->
                        launchGame(GameBootstrapActivity.LAN_ROLE_HOST, null, localAddress))
                .show();
    }

    private void showJoinLanDialog() {
        EditText addressInput = new EditText(this);
        addressInput.setSingleLine(true);
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addressInput.setHint("192.168.1.25");
        addressInput.setPadding(dp(20), dp(10), dp(20), dp(10));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Unirse a una sala LAN")
                .setMessage("Escribe la IP del anfitrión. Tu móvil será P2 y ambos deben estar en la misma Wi-Fi.")
                .setView(addressInput)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Conectar", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String address = addressInput.getText().toString().trim();
                    if (!isIpv4Literal(address)) {
                        addressInput.setError("Escribe una dirección IPv4 válida");
                        return;
                    }
                    dialog.dismiss();
                    launchGame(GameBootstrapActivity.LAN_ROLE_CLIENT, address, null);
                }));
        dialog.show();
    }

    private String findLocalIpv4Address() {
        try {
            ConnectivityManager connectivity =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivity == null) return null;
            Network active = connectivity.getActiveNetwork();
            NetworkCapabilities capabilities = active == null
                    ? null : connectivity.getNetworkCapabilities(active);
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return null;
            }
            LinkProperties properties = connectivity.getLinkProperties(active);
            if (properties == null) return null;
            for (LinkAddress linkAddress : properties.getLinkAddresses()) {
                InetAddress address = linkAddress.getAddress();
                if (address instanceof Inet4Address && !address.isLoopbackAddress()
                        && !address.isLinkLocalAddress() && !address.isMulticastAddress()) {
                    return address.getHostAddress();
                }
            }
            return null;
        } catch (Exception e) {
            RunLog.append(this, "LAN: no se pudo detectar la IP local: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean isIpv4Literal(String address) {
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) return false;
        int firstOctet = -1;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3
                    || (part.length() > 1 && part.charAt(0) == '0')) return false;
            int value = 0;
            for (int i = 0; i < part.length(); i++) {
                char digit = part.charAt(i);
                if (digit < '0' || digit > '9') return false;
                value = value * 10 + digit - '0';
            }
            if (value > 255) return false;
            if (firstOctet < 0) firstOctet = value;
        }
        return firstOctet > 0 && firstOctet < 224 && firstOctet != 127;
    }

    private boolean launchGame(int lanRole, String lanAddress, String localAddress) {
        if (launchPending || folderPending) return false;
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
        int targetFps = frameRateSpinner.getSelectedItemPosition() == 1 ? 120 : 60;
        prefs.edit()
                .putString(PREF_LANGUAGE, language)
                .putInt(PREF_RESOLUTION_ROWS, rows)
                .putInt(PREF_TARGET_FPS, targetFps)
                .putBoolean(PREF_AUTO_HIDE_TOUCH, autoHideTouch.isChecked())
                .apply();

        RunLog.reset(this, "launcher: Jugar; language=" + language
                + " render_rows=" + (rows == 0 ? "auto" : rows) + " fps_limit=" + targetFps);

        Intent game = new Intent();
        game.setClassName(getPackageName(), GameBootstrapActivity.class.getName());
        game.putExtra(GameBootstrapActivity.EXTRA_GAME_URI, saved);
        game.putExtra(GameBootstrapActivity.EXTRA_LANGUAGE, language);
        game.putExtra(GameBootstrapActivity.EXTRA_RENDER_ROWS, rows);
        game.putExtra(GameBootstrapActivity.EXTRA_TARGET_FPS, targetFps);
        game.putExtra(GameBootstrapActivity.EXTRA_SAVE_FOLDER, prefs.getString(PREF_SAVE_FOLDER, null));
        game.putExtra(GameBootstrapActivity.EXTRA_AUTO_HIDE_TOUCH, autoHideTouch.isChecked());
        game.putExtra(GameBootstrapActivity.EXTRA_LAN_ROLE, lanRole);
        if (lanAddress != null) game.putExtra(GameBootstrapActivity.EXTRA_LAN_ADDRESS, lanAddress);
        if (localAddress != null) game.putExtra(GameBootstrapActivity.EXTRA_LAN_LOCAL_ADDRESS, localAddress);
        GraphicsSettings.putLaunchExtras(game, prefs);
        game.putExtra(DriverRuntime.EXTRA_DRIVER, prefs.getString(DriverStore.PREF_DRIVER, ""));
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
