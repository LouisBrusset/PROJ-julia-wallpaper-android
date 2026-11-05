package io.github.louisbrusset.juliawallpaper;

import android.content.Context;
import android.content.res.Configuration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * Stockage partagé entre l'interface (processus principal) et le service
 * (processus ":wall"). Les SharedPreferences ne sont pas fiables entre
 * processus, donc deux petits fichiers .properties écrits de façon atomique :
 *   settings.properties : écrit uniquement par l'interface (réglages)
 *   state.properties    : écrit uniquement par le service (fractale actuelle / suivante)
 */
final class Store {
    static final String K_ENABLED = "enabled", K_TARGET = "target";
    static final int TARGET_HOME = 0, TARGET_LOCK = 1, TARGET_BOTH = 2;

    static final String K_CUR_DAY = "current.day", K_CUR_NIGHT = "current.night", K_CUR_INFO = "current.info";
    static final String K_NEXT_DAY = "next.day", K_NEXT_NIGHT = "next.night", K_NEXT_INFO = "next.info";

    private Store() {}

    static File walls(Context c) { File d = new File(c.getFilesDir(), "walls"); d.mkdirs(); return d; }
    static File favs(Context c)  { File d = new File(c.getFilesDir(), "favs");  d.mkdirs(); return d; }

    static Properties load(Context c, String name) {
        Properties p = new Properties();
        File f = new File(c.getFilesDir(), name);
        if (f.exists()) {
            InputStream in = null;
            try { in = new FileInputStream(f); p.load(in); }
            catch (IOException ignored) {}
            finally { if (in != null) try { in.close(); } catch (IOException ignored) {} }
        }
        return p;
    }

    static void save(Context c, String name, Properties p) {
        File f = new File(c.getFilesDir(), name), t = new File(c.getFilesDir(), name + ".tmp");
        OutputStream out = null;
        try {
            out = new FileOutputStream(t); p.store(out, null); out.close(); out = null;
            if (!t.renameTo(f)) t.delete();
        } catch (IOException e) {
            t.delete();
        } finally { if (out != null) try { out.close(); } catch (IOException ignored) {} }
    }

    static long stamp(Context c, String name) { return new File(c.getFilesDir(), name).lastModified(); }

    static Properties settings(Context c) { return load(c, "settings.properties"); }
    static Properties state(Context c)    { return load(c, "state.properties"); }

    static boolean enabled(Context c) { return Boolean.parseBoolean(settings(c).getProperty(K_ENABLED, "false")); }
    static int target(Context c) {
        try { return Integer.parseInt(settings(c).getProperty(K_TARGET, "2")); }
        catch (NumberFormatException e) { return TARGET_BOTH; }
    }

    static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    static boolean exists(String path) { return path != null && new File(path).isFile(); }

    static void copy(File from, File to) throws IOException {
        File t = new File(to.getPath() + ".tmp");
        InputStream in = new FileInputStream(from);
        try {
            OutputStream out = new FileOutputStream(t);
            try {
                byte[] b = new byte[1 << 16]; int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            } finally { out.close(); }
        } finally { in.close(); }
        if (!t.renameTo(to)) { t.delete(); throw new IOException("rename " + to); }
    }
}
