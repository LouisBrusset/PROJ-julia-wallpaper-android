package io.github.louisbrusset.juliawallpaper;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
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

    static boolean enabled(Context c) { return enabledState(c) == 1; }

    /**
     * 1 = activé, 0 = désactivé explicitement par l'utilisateur, -1 = inconnu
     * (fichier absent ou illisible). Le service ne s'arrête que sur un 0 :
     * une lecture ratée ne doit jamais l'éteindre.
     */
    static int enabledState(Context c) {
        File f = new File(c.getFilesDir(), "settings.properties");
        Properties p = new Properties();
        InputStream in = null;
        try { in = new FileInputStream(f); p.load(in); }
        catch (IOException e) { return -1; }
        finally { if (in != null) try { in.close(); } catch (IOException ignored) {} }
        String v = p.getProperty(K_ENABLED);
        return v == null ? -1 : Boolean.parseBoolean(v.trim()) ? 1 : 0;
    }

    static boolean batteryExempt(Context c) {
        return c.getSystemService(android.os.PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName());
    }
    static int target(Context c) {
        try { return Integer.parseInt(settings(c).getProperty(K_TARGET, "2")); }
        catch (NumberFormatException e) { return TARGET_BOTH; }
    }

    static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    static boolean exists(String path) { return path != null && new File(path).isFile(); }

    /* ---------------------------------------------------------------- */
    /* Journal de diagnostic (files/journal.txt, quelques Ko au plus)     */

    static synchronized void log(Context c, String msg) {
        File f = new File(c.getFilesDir(), "journal.txt");
        try {
            if (f.length() > 24 * 1024) {             // on garde la fin
                byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
                int from = all.length - 12 * 1024;
                while (from < all.length && all[from] != '\n') from++;
                java.nio.file.Files.write(f.toPath(), java.util.Arrays.copyOfRange(all, Math.min(from + 1, all.length), all.length));
            }
            String line = new java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.FRANCE).format(new java.util.Date())
                    + "  " + msg + "\n";
            FileOutputStream out = new FileOutputStream(f, true);
            try { out.write(line.getBytes("UTF-8")); } finally { out.close(); }
        } catch (IOException ignored) {}
    }

    static String journal(Context c, int maxLines) {
        File f = new File(c.getFilesDir(), "journal.txt");
        try {
            String[] lines = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = lines.length - 1; i >= 0 && i >= lines.length - maxLines; i--) sb.append(lines[i]).append('\n');
            return sb.toString();
        } catch (IOException e) { return ""; }
    }

    /* ---------------------------------------------------------------- */
    /* Tâche de secours : relance le service s'il n'est plus là          */

    static final int WATCHDOG_JOB = 42;

    static void scheduleWatchdog(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js.getPendingJob(WATCHDOG_JOB) != null) return;
        JobInfo job = new JobInfo.Builder(WATCHDOG_JOB, new ComponentName(c, WatchdogJob.class))
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)                 // survit au redémarrage
                .build();
        js.schedule(job);
    }

    static void cancelWatchdog(Context c) {
        c.getSystemService(JobScheduler.class).cancel(WATCHDOG_JOB);
    }

    /** Démarre (ou relance) le service ; ne plante jamais, journalise l'échec. */
    static boolean startService(Context c, String why) {
        try {
            c.startForegroundService(new android.content.Intent(c, JuliaService.class).setAction(JuliaService.ACTION_ENSURE));
            return true;
        } catch (Exception e) {
            log(c, "échec du démarrage du service (" + why + ") : " + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }

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
