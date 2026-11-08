package io.github.louisbrusset.juliawallpaper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Relance le service au démarrage du téléphone et après une mise à jour de l'appli.
 * En secours, une tâche périodique persistante (WatchdogJob) le relance aussi
 * si ce démarrage n'a pas suffi.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        boolean enabled = Store.enabled(c);
        Store.log(c, "événement " + a.substring(a.lastIndexOf('.') + 1) + (enabled ? "" : " (service désactivé)"));
        if (!enabled) return;
        Store.scheduleWatchdog(c);
        if (Store.startService(c, "démarrage")) Store.log(c, "service lancé au démarrage");
    }
}
