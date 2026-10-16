package df.root.script

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Boot entry for an explicitly enabled module plan.
 *
 * `LOCKED_BOOT_COMPLETED` fires before the PIN, while credential-encrypted
 * storage is still unavailable. Everything the plan needs already lives in
 * device-protected storage (`/data/user_de/0/df.root`). `BOOT_COMPLETED` is
 * kept as a fallback on firmwares that skip locked-boot broadcasts; the engine
 * no-ops if this boot already finished.
 */
class ScriptBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext.createDeviceProtectedStorageContext()
        try {
            if (!ModuleStore.read(app).onBoot) return
            if (BootMarkers.autoConsumed(app)) return
            val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(app, ScriptBootJob::class.java))
                .setMinimumLatency(0)
                .setOverrideDeadline(5_000)
                .build())
        } catch (failure: Exception) {
            android.util.Log.e("dfroot", "Unable to schedule module plan", failure)
        }
    }

    companion object {
        const val JOB_ID = 7342
    }
}
