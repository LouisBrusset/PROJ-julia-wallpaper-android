package io.github.louisbrusset.juliawallpaper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Relance le service au démarrage du téléphone et après une mise à jour de l'appli. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (Store.enabled(c)) c.startForegroundService(new Intent(c, JuliaService.class));
    }
}
