package io.github.louisbrusset.juliawallpaper;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * Tâche périodique (toutes les ~15 min, conservée après un redémarrage) :
 * si le service est activé, elle le (re)lance. Ne coûte rien si le service
 * tourne déjà. Sur Android 12+, un lancement depuis l'arrière-plan n'est
 * accepté que si l'appli n'est pas soumise à l'optimisation de batterie.
 */
public class WatchdogJob extends JobService {
    @Override public boolean onStartJob(JobParameters p) {
        if (Store.enabled(this)) {
            if (!JuliaService.running) {
                Store.log(this, "service absent : relance par la tâche de secours");
                Store.startService(this, "tâche de secours");
            }
        } else {
            Store.cancelWatchdog(this);
        }
        return false;
    }
    @Override public boolean onStopJob(JobParameters p) { return false; }
}
