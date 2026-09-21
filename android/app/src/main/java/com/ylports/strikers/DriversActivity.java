package com.ylports.strikers;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional driver management, separate from the compact launcher. */
public final class DriversActivity extends Activity {
    private static final int IMPORT_DRIVER = 2001;
    private static final AtomicBoolean importing = new AtomicBoolean();
    private static WeakReference<DriversActivity> foreground = new WeakReference<>(null);
    private DriverStore store;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new DriverStore(this);
    }
    @Override public void onResume() {
        super.onResume();
        foreground = new WeakReference<>(this);
        render();
    }
    @Override public void onPause() {
        if (foreground.get() == this) foreground.clear();
        super.onPause();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(13, 15, 19));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);
        TextView title = new TextView(this);
        title.setText("Drivers gráficos");
        title.setTextColor(Color.WHITE);
        title.setTextSize(25);
        root.addView(title);
        boolean supported = DriverStore.customDriversSupported();
        TextView description = new TextView(this);
        description.setText(supported ? "Opcional. Importa un ZIP compatible con tu GPU."
                : "Este móvil usa el driver del sistema. No necesitas instalar nada.");
        description.setTextColor(Color.LTGRAY);
        description.setPadding(0, padding / 2, 0, padding / 2);
        root.addView(description);
        List<DriverStore.Driver> drivers = store.installed();
        String selected = store.selectedId();
        boolean failed = store.failedPreviously(selected);
        if (failed) {
            TextView notice = new TextView(this);
            notice.setText("El inicio con el driver elegido falló. Se usará el del sistema.");
            notice.setTextColor(Color.LTGRAY);
            root.addView(notice);
        }
        RadioGroup choices = new RadioGroup(this);
        RadioButton system = new RadioButton(this);
        system.setText("Sistema (predeterminado)");
        system.setId(android.view.View.generateViewId());
        choices.addView(system);
        root.addView(choices);
        system.setChecked(!supported || failed || store.find(selected) == null);
        system.setOnClickListener(v -> choose(""));
        for (DriverStore.Driver driver : drivers) {
            RadioButton choice = new RadioButton(this);
            choice.setText(driver.label());
            choice.setId(android.view.View.generateViewId());
            choices.addView(choice);
            choice.setChecked(supported && !failed && driver.id.equals(selected));
            choice.setEnabled(supported && !importing.get());
            choice.setOnClickListener(v -> choose(driver.id));
        }
        Button install = new Button(this);
        install.setText(importing.get() ? "Importando…" : "Importar ZIP");
        install.setAllCaps(false);
        install.setEnabled(supported && !importing.get());
        install.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, IMPORT_DRIVER);
        });
        root.addView(install);
        if (store.find(selected) != null) {
            Button remove = new Button(this);
            remove.setText("Eliminar driver elegido");
            remove.setAllCaps(false);
            remove.setEnabled(!importing.get());
            remove.setOnClickListener(v -> {
                try { store.remove(selected); render(); }
                catch (Exception e) { message(e.getMessage()); }
            });
            root.addView(remove);
        }
        Button back = new Button(this);
        back.setText("Volver");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        root.addView(back);
        setContentView(scroll);
    }

    private void choose(String id) {
        try { store.select(id); render(); }
        catch (Exception e) { message(e.getMessage()); }
    }

    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != IMPORT_DRIVER || result != RESULT_OK || data == null || data.getData() == null) return;
        if (!DriverStore.customDriversSupported() || !importing.compareAndSet(false, true)) return;
        android.content.Context app = getApplicationContext();
        android.net.Uri uri = data.getData();
        render();
        new Thread(() -> {
            String resultText;
            try (InputStream input = app.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException("No se pudo abrir el ZIP.");
                DriverStore.Driver imported = new DriverStore(app).importZip(input);
                resultText = "Importado: " + imported.name;
            } catch (Exception e) { resultText = e.getMessage() == null ? "No se pudo importar el driver." : e.getMessage(); }
            importing.set(false);
            String completed = resultText;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                DriversActivity activity = foreground.get();
                if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                    activity.render();
                    activity.message(completed);
                }
            });
        }, "driver-import").start();
    }
}
