package df.root.term

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import df.root.R
import df.root.daemon.Dfctl
import df.root.shell.Privilege
import df.root.shell.Shell
import df.root.shell.ShellResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Commands outlive the pager composition and configuration changes. */
class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    val buffer = TermBuffer()
    var input by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var privilege by mutableStateOf(Shell.current)
        private set
    var revision by mutableIntStateOf(0)
        private set

    private val context get() = getApplication<Application>()
    private val transport = Mutex()
    private var bannerPrinted = false
    private var initialized = false

    fun updateInput(value: String) { input = value }
    fun appendInput(value: String) { input += value }
    fun previousCommand() { buffer.historyPrev()?.let { input = it } }
    fun nextCommand() { input = buffer.historyNext() }

    // A fresh UI revision also publishes clear while a command is suspended.
    private fun refresh() { revision++ }
    fun clear() { buffer.clear(); refresh() }

    /** Only the active page's resumed lifecycle calls this; cancellation stops polling. */
    suspend fun pollForeground() {
        if (!bannerPrinted) {
            buffer.notice(context.getString(R.string.terminal_banner))
            bannerPrinted = true
            refresh()
        }
        while (true) {
            try {
                transport.withLock {
                    if (!initialized) {
                        privilege = Shell.detect(context, forceSu = true)
                        buffer.notice(context.getString(R.string.terminal_transport,
                            privilegeName(), Shell.channelNote(context)))
                        refresh()
                        val result = Shell.run(context, "id")
                        if (result.output.isNotBlank()) buffer.output(result.output.trimEnd('\n'))
                        else buffer.notice(context.getString(R.string.terminal_id_empty, result.exit),
                            isError = result.exit != 0)
                        privilege = Shell.current
                        initialized = true
                        refresh()
                    } else if (!busy) {
                        val detected = Shell.detect(context, forceSu = Shell.current == Privilege.App)
                        if (detected != privilege) {
                            privilege = detected
                            buffer.notice(context.getString(R.string.terminal_privilege_changed, privilegeName()))
                            refresh()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                buffer.notice(context.getString(R.string.terminal_failure, failure.message ?: failure.javaClass.simpleName), true)
                refresh()
            }
            delay(4000)
        }
    }

    /** Only acceptance consumes input; rejected empty/busy submissions keep it. */
    fun submit(command: String = input): Boolean {
        if (busy || command.isBlank()) return false
        busy = true
        input = ""
        buffer.prompt(command)
        refresh()
        viewModelScope.launch {
            try {
                transport.withLock {
                    privilege = Shell.detect(context)
                    val result = when {
                        command == "!__log" -> readDaemonLog()
                        command.startsWith("!") -> {
                            val verb = command.drop(1).trim()
                            Shell.daemonVerb(context, verb) ?: ShellResult(1,
                                context.getString(R.string.terminal_daemon_unreachable, verb))
                        }
                        else -> Shell.run(context, command)
                    }
                    if (result.output.isNotEmpty()) buffer.output(result.output.trimEnd('\n'))
                    if (result.exit != 0) buffer.notice("exit=${result.exit}", true)
                    privilege = Shell.current
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                buffer.notice(context.getString(R.string.terminal_failure, failure.message ?: failure.javaClass.simpleName), true)
            } finally {
                busy = false
                refresh()
            }
        }
        return true
    }

    private fun privilegeName(): String = when (privilege) {
        Privilege.App -> context.getString(R.string.terminal_app_privilege)
        else -> privilege.label
    }

    private suspend fun readDaemonLog(): ShellResult {
        val path = Dfctl.logPath(context).absolutePath
        val direct = Shell.run(context, "tail -n 200 $path 2>&1")
        if (direct.exit == 0 && direct.output.isNotBlank()) return direct
        return Shell.daemonVerb(context, "status")?.let {
            ShellResult(0, context.getString(R.string.terminal_log_fallback, path, it.output))
        } ?: direct
    }
}
