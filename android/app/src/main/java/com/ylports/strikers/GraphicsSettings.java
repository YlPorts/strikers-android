package com.ylports.strikers;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.system.ErrnoException;
import android.system.Os;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

final class GraphicsSettings {
    static final String BACKEND = "graphics_backend";
    static final String MSAA = "graphics_msaa";
    static final String ANISO = "graphics_aniso";
    static final String ASPECT = "graphics_aspect";
    static final String VSYNC = "graphics_vsync";
    static final String STATS = "graphics_stats";
    private static final int[] ANISO_LEVELS = {1, 2, 4, 8, 16};
    private static final String[] ASPECT_VALUES = {"auto", "16:9", "4:3"};

    static String backend(String value) { return "opengles".equals(value) ? "opengles" : "vulkan"; }

    static String launchBackend(Intent game, SharedPreferences prefs) {
        return backend(game.hasExtra(BACKEND) ? game.getStringExtra(BACKEND) : prefs.getString(BACKEND, "vulkan"));
    }

    static int msaa(int value) { return value == 4 ? 4 : 1; }
    static int aniso(int value) {
        for (int level : ANISO_LEVELS) if (value == level) return value;
        return 16;
    }
    static String aspect(String value) {
        for (String choice : ASPECT_VALUES) if (choice.equals(value)) return value;
        return "auto";
    }

    static void putLaunchExtras(Intent game, SharedPreferences prefs) {
        game.putExtra(BACKEND, backend(prefs.getString(BACKEND, "vulkan")));
        game.putExtra(MSAA, msaa(prefs.getInt(MSAA, 1)));
        game.putExtra(ANISO, aniso(prefs.getInt(ANISO, 16)));
        game.putExtra(ASPECT, aspect(prefs.getString(ASPECT, "auto")));
        game.putExtra(VSYNC, prefs.getBoolean(VSYNC, true));
        game.putExtra(STATS, prefs.getBoolean(STATS, false));
    }

    static String applyEnvironment(Intent game, SharedPreferences prefs) throws ErrnoException {
        String renderer = launchBackend(game, prefs);
        int samples = msaa(game.getIntExtra(MSAA, prefs.getInt(MSAA, 1)));
        int filter = aniso(game.getIntExtra(ANISO, prefs.getInt(ANISO, 16)));
        String shape = aspect(game.hasExtra(ASPECT) ? game.getStringExtra(ASPECT) : prefs.getString(ASPECT, "auto"));
        boolean vsync = game.getBooleanExtra(VSYNC, prefs.getBoolean(VSYNC, true));
        Os.setenv("STRIKERS_BACKEND", renderer, true);
        Os.setenv("STRIKERS_MSAA", Integer.toString(samples), true);
        Os.setenv("STRIKERS_ANISO", Integer.toString(filter), true);
        Os.setenv("STRIKERS_ASPECT", shape, true);
        Os.setenv("STRIKERS_VSYNC", vsync ? "1" : "0", true);
        return "backend_requested=" + renderer + " msaa=" + samples + " aniso=" + filter + " aspect=" + shape + " vsync=" + vsync;
    }

    static void show(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(MainActivity.PREFS, Activity.MODE_PRIVATE);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * activity.getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding / 2, padding, padding / 2);
        Spinner renderer = selector(activity, root, "Motor gráfico", new String[]{"Vulkan", "OpenGL ES · experimental"});
        renderer.setSelection("opengles".equals(backend(prefs.getString(BACKEND, "vulkan"))) ? 1 : 0);
        Spinner samples = selector(activity, root, "Suavizado de bordes", new String[]{"Desactivado", "4× MSAA"});
        samples.setSelection(msaa(prefs.getInt(MSAA, 1)) == 4 ? 1 : 0);
        Spinner filter = selector(activity, root, "Filtro de texturas", new String[]{"1×", "2×", "4×", "8×", "16×"});
        int level = aniso(prefs.getInt(ANISO, 16));
        for (int i = 0; i < ANISO_LEVELS.length; i++) if (ANISO_LEVELS[i] == level) filter.setSelection(i);
        Spinner shape = selector(activity, root, "Formato de pantalla", new String[]{"Pantalla completa", "16:9", "4:3"});
        String format = aspect(prefs.getString(ASPECT, "auto"));
        for (int i = 0; i < ASPECT_VALUES.length; i++) if (ASPECT_VALUES[i].equals(format)) shape.setSelection(i);
        CheckBox sync = new CheckBox(activity);
        sync.setText("Sincronización vertical");
        sync.setChecked(prefs.getBoolean(VSYNC, true));
        root.addView(sync);
        CheckBox stats = new CheckBox(activity);
        stats.setText("Mostrar FPS y temperatura de batería");
        stats.setChecked(prefs.getBoolean(STATS, false));
        root.addView(stats);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);
        new AlertDialog.Builder(activity).setTitle("Gráficos").setView(scroll)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Guardar", (dialog, which) -> prefs.edit()
                        .putString(BACKEND, renderer.getSelectedItemPosition() == 1 ? "opengles" : "vulkan")
                        .putInt(MSAA, samples.getSelectedItemPosition() == 1 ? 4 : 1)
                        .putInt(ANISO, ANISO_LEVELS[filter.getSelectedItemPosition()])
                        .putString(ASPECT, ASPECT_VALUES[shape.getSelectedItemPosition()])
                        .putBoolean(VSYNC, sync.isChecked()).putBoolean(STATS, stats.isChecked()).apply())
                .show();
    }

    private static Spinner selector(Activity activity, LinearLayout root, String label, String[] values) {
        TextView title = new TextView(activity);
        title.setText(label);
        title.setPadding(0, 12, 0, 2);
        root.addView(title);
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        root.addView(spinner);
        return spinner;
    }
}
