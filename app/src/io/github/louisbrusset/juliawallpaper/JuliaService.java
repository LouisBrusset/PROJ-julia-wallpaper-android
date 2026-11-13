package io.github.louisbrusset.juliawallpaper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Service de premier plan, dans son propre processus (":wall") pour que la
 * mémoire de l'interface ne reste pas chargée quand l'appli est fermée.
 *
 * Il y a toujours une fractale "suivante" calculée d'avance :
 *  - écran éteint (bouton marche/arrêt ou mise en veille) : on applique la
 *    suivante tout de suite (aucun calcul à ce moment-là) ;
 *  - puis on calcule la nouvelle suivante en priorité basse, écran éteint.
 * Le passage mode clair / mode sombre réapplique la bonne variante.
 *
 * Android ne livre l'événement "écran éteint" qu'aux récepteurs enregistrés
 * dynamiquement : il faut donc un service qui reste en vie, d'où le service
 * de premier plan (type specialUse).
 */
public class JuliaService extends Service {
    static final String TAG = "JuliaWallpaper";
    static final String ACTION_ENSURE = "ensure", ACTION_NEXT = "next", ACTION_REAPPLY = "reapply", ACTION_APPLY = "apply";
    static final String EXTRA_DAY = "day", EXTRA_NIGHT = "night", EXTRA_INFO = "info";
    private static final String CHANNEL = "service";

    /** Vrai tant que le service vit dans ce processus (lu par WatchdogJob, même processus). */
    static volatile boolean running;

    private ExecutorService worker;
    private BroadcastReceiver screenOff;
    private int lastNight;
    private volatile boolean promotePending;
    private boolean refused;
    private final SecureRandom random = new SecureRandom();

    private static final ThreadFactory LOW = new ThreadFactory() {
        public Thread newThread(final Runnable r) {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }
            }, "julia");
            t.setDaemon(true);
            return t;
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        /* Quand Android relance lui-même le service (après l'avoir tué faute de
           mémoire), il peut refuser le passage au premier plan si l'appli est
           soumise à l'optimisation de batterie. Avant la v2 cela faisait planter
           le service en boucle, jusqu'à la prochaine ouverture de l'appli. */
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(1, n);
        } catch (Exception e) {
            Store.log(this, "relance refusée par Android (" + e.getClass().getSimpleName() + ")"
                    + (Store.batteryExempt(this) ? "" : " : autorisez l'appli en arrière-plan"));
            refused = true;
            stopSelf();
            return;
        }

        running = true;
        Store.log(this, "service démarré" + (Store.batteryExempt(this) ? "" : " (optimisation de batterie active)"));
        Store.scheduleWatchdog(this);
        worker = Executors.newSingleThreadExecutor(LOW);
        lastNight = Store.isNight(this) ? 1 : 0;
        screenOff = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                int st = Store.enabledState(JuliaService.this);
                if (st != 0) submitPromote();         // -1 (lecture ratée) : on continue quand même
                else { Store.log(JuliaService.this, "service désactivé : arrêt"); stopSelf(); }
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOff, f);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (refused) { stopSelf(); return START_NOT_STICKY; }
        if (intent == null) Store.log(this, "service relancé par Android");
        String a = intent != null && intent.getAction() != null ? intent.getAction() : ACTION_ENSURE;
        if (ACTION_NEXT.equals(a)) submitPromote();
        else if (ACTION_REAPPLY.equals(a)) submit(new Runnable() { public void run() { applyCurrent(); } });
        else if (ACTION_APPLY.equals(a)) {
            final String d = intent.getStringExtra(EXTRA_DAY), n = intent.getStringExtra(EXTRA_NIGHT), info = intent.getStringExtra(EXTRA_INFO);
            submit(new Runnable() { public void run() { applyGiven(d, n, info); } });
        } else submit(new Runnable() { public void run() { ensure(); } });

        if (Store.enabledState(this) == 0) {
            /* service désactivé : on exécute la commande puis on s'arrête */
            submit(new Runnable() { public void run() { stopSelf(); } });
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (refused) return;
        int night = (c.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES ? 1 : 0;
        if (night != lastNight) {
            lastNight = night;
            submit(new Runnable() { public void run() { applyCurrent(); } });
        }
    }

    @Override public void onDestroy() {
        if (!refused) {
            running = false;
            Store.log(this, "service arrêté");
            try { unregisterReceiver(screenOff); } catch (Exception ignored) {}
            worker.shutdown();
        }
        super.onDestroy();
    }

    /* ---------------------------------------------------------------- */

    private void submit(final Runnable r) {
        worker.execute(new Runnable() {
            public void run() {
                PowerManager.WakeLock wl = getSystemService(PowerManager.class)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "julia:work");
                wl.acquire(3 * 60 * 1000L);
                try { r.run(); }
                catch (Throwable t) { Log.e(TAG, "erreur", t); Store.log(JuliaService.this, "erreur : " + t); }
                finally { if (wl.isHeld()) wl.release(); }
            }
        });
    }

    private void submitPromote() {
        if (promotePending) return;          // plusieurs extinctions rapprochées : une seule
        promotePending = true;
        submit(new Runnable() { public void run() { promotePending = false; promote(); } });
    }

    /** Taille réelle de l'écran, en portrait. */
    private int[] screenSize() {
        Rect b = getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        return new int[]{Math.min(b.width(), b.height()), Math.max(b.width(), b.height())};
    }

    private void ensure() {
        Properties st = Store.state(this);
        if (!Store.exists(st.getProperty(Store.K_CUR_DAY))) { promote(); return; }
        if (!Store.exists(st.getProperty(Store.K_NEXT_DAY))) renderNext();
    }

    private void renderNext() {
        int[] wh = screenSize();
        long seed = random.nextLong();
        String id = String.format("%016x", seed);
        File dir = Store.walls(this);
        File d = new File(dir, id + "-day.png"), n = new File(dir, id + "-night.png");
        try {
            long t0 = System.nanoTime();
            Renderer.Result r = new Renderer()
                    .threads(Runtime.getRuntime().availableProcessors(), LOW)
                    .render(wh[0], wh[1], seed, d, n, null, -1);
            Log.i(TAG, r.info() + String.format(" | %dx%d en %.1f s", wh[0], wh[1], (System.nanoTime() - t0) / 1e9));
            Properties st = Store.state(this);
            st.setProperty(Store.K_NEXT_DAY, d.getPath());
            st.setProperty(Store.K_NEXT_NIGHT, n.getPath());
            st.setProperty(Store.K_NEXT_INFO, r.info());
            Store.save(this, "state.properties", st);
        } catch (Exception e) {
            Log.e(TAG, "rendu impossible", e);
            Store.log(this, "rendu impossible : " + e);
            d.delete(); n.delete();
        }
        System.gc();   // rend tout de suite les ~10 Mo du rendu
    }

    private void promote() {
        Properties st = Store.state(this);
        if (!Store.exists(st.getProperty(Store.K_NEXT_DAY))) { renderNext(); st = Store.state(this); }
        if (!Store.exists(st.getProperty(Store.K_NEXT_DAY))) return;
        String oldD = st.getProperty(Store.K_CUR_DAY), oldN = st.getProperty(Store.K_CUR_NIGHT);
        st.setProperty(Store.K_CUR_DAY, st.getProperty(Store.K_NEXT_DAY));
        st.setProperty(Store.K_CUR_NIGHT, st.getProperty(Store.K_NEXT_NIGHT));
        st.setProperty(Store.K_CUR_INFO, st.getProperty(Store.K_NEXT_INFO, ""));
        st.remove(Store.K_NEXT_DAY); st.remove(Store.K_NEXT_NIGHT); st.remove(Store.K_NEXT_INFO);
        Store.save(this, "state.properties", st);
        applyCurrent();
        Store.log(this, "nouveau fond : " + st.getProperty(Store.K_CUR_INFO, ""));
        deleteIfOwned(oldD); deleteIfOwned(oldN);
        renderNext();
    }

    private void applyGiven(String day, String night, String info) {
        if (!Store.exists(day) || !Store.exists(night)) return;
        Properties st = Store.state(this);
        String oldD = st.getProperty(Store.K_CUR_DAY), oldN = st.getProperty(Store.K_CUR_NIGHT);
        st.setProperty(Store.K_CUR_DAY, day);
        st.setProperty(Store.K_CUR_NIGHT, night);
        st.setProperty(Store.K_CUR_INFO, info != null ? info : "");
        Store.save(this, "state.properties", st);
        applyCurrent();
        if (!day.equals(oldD)) { deleteIfOwned(oldD); deleteIfOwned(oldN); }
        if (!Store.exists(st.getProperty(Store.K_NEXT_DAY))) renderNext();
    }

    /** Supprime une image seulement si elle vient du dossier de travail (jamais un favori). */
    private void deleteIfOwned(String path) {
        if (path == null) return;
        File f = new File(path);
        if (Store.walls(this).equals(f.getParentFile())) f.delete();
    }

    private void applyCurrent() {
        Properties st = Store.state(this);
        String path = st.getProperty(Store.isNight(this) ? Store.K_CUR_NIGHT : Store.K_CUR_DAY);
        if (!Store.exists(path)) return;
        int which;
        switch (Store.target(this)) {
            case Store.TARGET_HOME: which = WallpaperManager.FLAG_SYSTEM; break;
            case Store.TARGET_LOCK: which = WallpaperManager.FLAG_LOCK; break;
            default: which = WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK;
        }
        InputStream in = null;
        try {
            /* setStream : c'est le système qui décode l'image, pas notre processus */
            in = new FileInputStream(path);
            WallpaperManager.getInstance(this).setStream(in, null, true, which);
        } catch (Exception e) {
            Log.e(TAG, "application du fond impossible", e);
            Store.log(this, "application du fond impossible : " + e);
        } finally { if (in != null) try { in.close(); } catch (Exception ignored) {} }
    }
}
