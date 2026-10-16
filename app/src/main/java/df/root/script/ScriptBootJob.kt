package df.root.script

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.*

class ScriptBootJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        job = scope.launch {
            try { ModuleEngine.run(applicationContext, "boot") }
            finally { jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return false // Arbitrary shell side effects must never be retried by the scheduler.
    }
}
