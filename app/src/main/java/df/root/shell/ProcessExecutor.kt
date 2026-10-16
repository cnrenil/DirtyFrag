package df.root.shell

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Drain output concurrently: reading to EOF before waitFor defeats all deadlines. */
internal object ProcessExecutor {
    fun run(builder: ProcessBuilder, timeoutMs: Long, maxOutput: Int = 65536): ShellResult {
        val process = builder.redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Executors.newSingleThreadExecutor { task -> Thread(task, "shell-output").apply { isDaemon = true } }
        val drain = reader.submit {
            runCatching {
                process.inputStream.reader().use { input ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            output.append(buffer, 0, count)
                            if (output.length > maxOutput) output.delete(0, output.length - maxOutput)
                        }
                    }
                }
            }
        }
        try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(200, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
            runCatching { drain.get(300, TimeUnit.MILLISECONDS) }
            val text = synchronized(output) { output.toString() }
            return ShellResult(if (finished) process.exitValue() else 124, text + if (finished) "" else "\n(超时)")
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
            if (process.isAlive) process.destroyForcibly()
            drain.cancel(true)
            reader.shutdownNow()
        }
    }
}
