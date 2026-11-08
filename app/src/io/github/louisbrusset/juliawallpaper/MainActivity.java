package io.github.louisbrusset.juliawallpaper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Interface : aperçu de la fractale affichée, activation du service, cible
 * (accueil / verrouillage / les deux), favoris.
 * Uniquement des widgets du système (pas d'AndroidX) pour rester léger.
 */
public class MainActivity extends Activity {
    private ImageView preview;
    private TextView info, favEmpty, favTitle;
    private LinearLayout favGrid;
    private Switch enabled;
    private RadioGroup target;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private long lastStamp = -1;
    private String shownPath;
    private Bitmap previewBmp;
    private final List<Bitmap> thumbs = new ArrayList<Bitmap>();
    private boolean binding;
    private View batteryBox;
    private TextView journal;

    private final Runnable poll = new Runnable() {
        public void run() {
            long s = Store.stamp(MainActivity.this, "state.properties");
            if (s != lastStamp) { lastStamp = s; refreshPreview(); }
            ui.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        preview = findViewById(R.id.preview);
        info = findViewById(R.id.info);
        favEmpty = findViewById(R.id.fav_empty);
        favTitle = findViewById(R.id.fav_title);
        favGrid = findViewById(R.id.fav_grid);
        enabled = findViewById(R.id.enabled);
        target = findViewById(R.id.target);
        batteryBox = findViewById(R.id.battery_box);
        journal = findViewById(R.id.journal);
        ((Button) findViewById(R.id.battery_btn)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            }
        });
        ((Button) findViewById(R.id.journal_btn)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (journal.getVisibility() == View.VISIBLE) { journal.setVisibility(View.GONE); return; }
                String j = Store.journal(MainActivity.this, 40);
                journal.setText(j.isEmpty() ? "(vide)" : j);
                journal.setVisibility(View.VISIBLE);
            }
        });

        /* aperçu au format de l'écran */
        Rect bounds = getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        float ratio = (float) Math.max(bounds.width(), bounds.height()) / Math.min(bounds.width(), bounds.height());
        ViewGroup.LayoutParams lp = preview.getLayoutParams();
        lp.height = Math.round(lp.width * ratio);
        preview.setLayoutParams(lp);

        bindSettings();
        enabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                if (binding) return;
                Properties p = Store.settings(MainActivity.this);
                p.setProperty(Store.K_ENABLED, Boolean.toString(on));
                Store.save(MainActivity.this, "settings.properties", p);
                if (on) { Store.scheduleWatchdog(MainActivity.this); command(JuliaService.ACTION_ENSURE, null); }
                else { Store.cancelWatchdog(MainActivity.this); stopService(new Intent(MainActivity.this, JuliaService.class)); }
            }
        });
        target.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup g, int id) {
                if (binding) return;
                int t = id == R.id.t_home ? Store.TARGET_HOME : id == R.id.t_lock ? Store.TARGET_LOCK : Store.TARGET_BOTH;
                Properties p = Store.settings(MainActivity.this);
                p.setProperty(Store.K_TARGET, Integer.toString(t));
                Store.save(MainActivity.this, "settings.properties", p);
                command(JuliaService.ACTION_REAPPLY, null);
            }
        });
        ((Button) findViewById(R.id.next)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { info.setText(R.string.rendering); command(JuliaService.ACTION_NEXT, null); }
        });
        ((Button) findViewById(R.id.fav)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { addFavorite(); }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        bindSettings();
        /* auto-réparation : si le service activé ne tourne plus, on le relance */
        if (Store.enabled(this)) { Store.scheduleWatchdog(this); Store.startService(this, "ouverture de l'appli"); }
        PowerManager pm = getSystemService(PowerManager.class);
        batteryBox.setVisibility(pm.isIgnoringBatteryOptimizations(getPackageName()) ? View.GONE : View.VISIBLE);
        lastStamp = -1;
        ui.post(poll);
        loadFavorites();
    }

    @Override protected void onPause() {
        ui.removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void bindSettings() {
        binding = true;
        enabled.setChecked(Store.enabled(this));
        int t = Store.target(this);
        target.check(t == Store.TARGET_HOME ? R.id.t_home : t == Store.TARGET_LOCK ? R.id.t_lock : R.id.t_both);
        binding = false;
    }

    private void command(String action, Intent extras) {
        Intent i = new Intent(this, JuliaService.class).setAction(action);
        if (extras != null) i.putExtras(extras);
        startForegroundService(i);
    }

    /* ---------------------------------------------------------------- */

    private static Bitmap decode(String path, int sample) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;   // moitié moins de mémoire
        return BitmapFactory.decodeFile(path, o);
    }

    private void refreshPreview() {
        Properties st = Store.state(this);
        final String path = st.getProperty(Store.isNight(this) ? Store.K_CUR_NIGHT : Store.K_CUR_DAY);
        String inf = st.getProperty(Store.K_CUR_INFO, "");
        if (!Store.exists(path)) {
            preview.setImageDrawable(null);
            info.setText(R.string.none_yet);
            return;
        }
        info.setText(inf);
        if (path.equals(shownPath)) return;
        shownPath = path;
        io.execute(new Runnable() {
            public void run() {
                final Bitmap bm = decode(path, 4);
                ui.post(new Runnable() {
                    public void run() {
                        if (isDestroyed()) { if (bm != null) bm.recycle(); return; }
                        preview.setImageBitmap(bm);
                        if (previewBmp != null) previewBmp.recycle();
                        previewBmp = bm;
                    }
                });
            }
        });
    }

    /* ---------------------------------------------------------------- */
    /* Favoris : copies dans files/favs, indépendantes des fonds tournants */

    private static String base(String path) {
        String n = new File(path).getName();
        int i = n.lastIndexOf('-');
        return i > 0 ? n.substring(0, i) : n;
    }

    private void addFavorite() {
        final Properties st = Store.state(this);
        final String d = st.getProperty(Store.K_CUR_DAY), n = st.getProperty(Store.K_CUR_NIGHT);
        if (!Store.exists(d) || !Store.exists(n)) return;
        final File favs = Store.favs(this);
        if (favs.equals(new File(d).getParentFile())) { toast(R.string.fav_already); return; }
        io.execute(new Runnable() {
            public void run() {
                String id = base(d);
                try {
                    Store.copy(new File(d), new File(favs, id + "-day.png"));
                    Store.copy(new File(n), new File(favs, id + "-night.png"));
                    Properties p = new Properties();
                    p.setProperty("info", st.getProperty(Store.K_CUR_INFO, ""));
                    java.io.FileOutputStream out = new java.io.FileOutputStream(new File(favs, id + ".info"));
                    try { p.store(out, null); } finally { out.close(); }
                    ui.post(new Runnable() { public void run() { toast(R.string.fav_added); loadFavorites(); } });
                } catch (final IOException e) {
                    ui.post(new Runnable() { public void run() { toast("Erreur : " + e.getMessage()); } });
                }
            }
        });
    }

    private void loadFavorites() {
        final File favs = Store.favs(this);
        final boolean night = Store.isNight(this);
        io.execute(new Runnable() {
            public void run() {
                File[] list = favs.listFiles();
                final List<String> ids = new ArrayList<String>();
                if (list != null) {
                    Arrays.sort(list, new Comparator<File>() {
                        public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
                    });
                    for (File f : list) if (f.getName().endsWith("-day.png")) ids.add(base(f.getPath()));
                }
                final List<Bitmap> bms = new ArrayList<Bitmap>();
                for (String id : ids) bms.add(decode(new File(favs, id + (night ? "-night.png" : "-day.png")).getPath(), 4));
                ui.post(new Runnable() { public void run() { showFavorites(ids, bms); } });
            }
        });
    }

    private void showFavorites(final List<String> ids, List<Bitmap> bms) {
        if (isDestroyed()) { for (Bitmap b : bms) if (b != null) b.recycle(); return; }
        favGrid.removeAllViews();
        for (Bitmap b : thumbs) if (b != null) b.recycle();
        thumbs.clear(); thumbs.addAll(bms);
        favTitle.setText(getString(R.string.favs) + " (" + ids.size() + ")");
        favEmpty.setVisibility(ids.isEmpty() ? View.VISIBLE : View.GONE);
        final int cols = 3;
        float dp = getResources().getDisplayMetrics().density;
        int gap = Math.round(6 * dp);
        int width = favGrid.getWidth() > 0 ? favGrid.getWidth() : getResources().getDisplayMetrics().widthPixels - Math.round(40 * dp);
        int cw = (width - gap * (cols - 1)) / cols;
        int ch = Math.round(cw * preview.getLayoutParams().height / (float) preview.getLayoutParams().width);
        LinearLayout row = null;
        for (int k = 0; k < ids.size(); k++) {
            if (k % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = gap;
                favGrid.addView(row, rp);
            }
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(bms.get(k));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(cw, ch);
            if (k % cols != 0) ip.leftMargin = gap;
            final String id = ids.get(k);
            iv.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { favoriteMenu(id); } });
            row.addView(iv, ip);
        }
    }

    private void favoriteMenu(final String id) {
        final File favs = Store.favs(this);
        final File d = new File(favs, id + "-day.png"), n = new File(favs, id + "-night.png"), inf = new File(favs, id + ".info");
        new AlertDialog.Builder(this)
                .setItems(new CharSequence[]{getString(R.string.fav_apply), getString(R.string.fav_export), getString(R.string.fav_delete)},
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dlg, int which) {
                                if (which == 0) {
                                    Intent x = new Intent();
                                    x.putExtra(JuliaService.EXTRA_DAY, d.getPath());
                                    x.putExtra(JuliaService.EXTRA_NIGHT, n.getPath());
                                    x.putExtra(JuliaService.EXTRA_INFO, readInfo(inf));
                                    command(JuliaService.ACTION_APPLY, x);
                                } else if (which == 1) {
                                    exportToGallery(id, d, n);
                                } else {
                                    d.delete(); n.delete(); inf.delete();
                                    loadFavorites();
                                }
                            }
                        })
                .show();
    }

    private static String readInfo(File f) {
        Properties p = new Properties();
        try { InputStream in = new FileInputStream(f); try { p.load(in); } finally { in.close(); } }
        catch (IOException ignored) {}
        return p.getProperty("info", "");
    }

    /** Copie jour + nuit dans Images/Julia via MediaStore (aucune permission nécessaire). */
    private void exportToGallery(final String id, final File d, final File n) {
        io.execute(new Runnable() {
            public void run() {
                try {
                    insert(d, "julia-" + id + "-jour.png");
                    insert(n, "julia-" + id + "-nuit.png");
                    ui.post(new Runnable() { public void run() { toast(R.string.exported); } });
                } catch (final IOException e) {
                    ui.post(new Runnable() { public void run() { toast("Erreur : " + e.getMessage()); } });
                }
            }
        });
    }

    private void insert(File src, String name) throws IOException {
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Julia");
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("MediaStore");
        OutputStream out = cr.openOutputStream(uri);
        InputStream in = new FileInputStream(src);
        try {
            byte[] b = new byte[1 << 16]; int k;
            while ((k = in.read(b)) > 0) out.write(b, 0, k);
        } finally { in.close(); out.close(); }
    }

    private void toast(int res) { Toast.makeText(this, res, Toast.LENGTH_SHORT).show(); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
