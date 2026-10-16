package df.root.script

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Boot is only an entry point for an explicitly enabled module plan. */
class ScriptBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        try {
            if (!ModuleStore.read(app).onBoot) return
            val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.schedule(JobInfo.Builder(7342, ComponentName(app, ScriptBootJob::class.java))
                .setMinimumLatency(0).build())
        } catch (failure: Exception) {
            android.util.Log.e("dfroot", "Unable to schedule module plan", failure)
        }
    }
}
