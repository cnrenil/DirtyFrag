package df.root.daemon

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.system.ErrnoException
import java.io.FileDescriptor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Client for the dfdaemon control protocol over a FIFO pair (see dfctl.h).
 * Never throws: it reconnects per call and reports why the daemon was
 * unreachable. */
object Dfctl {

    private const val DIR = "daemon-control"
    private const val FIFO_IN = "dfctl.in"
    private const val FIFO_OUT = "dfctl.out"
    private const val PID = "dfctl.pid"
    private const val READY = "dfctl.ready"
    private const val LOG = "dfctl.log"

    private const val OK = ".ok"
    private const val ERR = ".err"
    private const val BODY = "| "
    /** Prefix of a piece that continues the previous line (see dfctl.h). */
    private const val CONT = "|+"

    /** Where the daemon keeps its channel. Never throws: the caller is usually
     * a composable, which must show "no channel" rather than crash. */
    fun dir(ctx: Context): File = runCatching {
        val parent = ctx.createDeviceProtectedStorageContext().filesDir.parentFile
        if (parent != null) File(parent, DIR) else File("/data/user_de/0/df.root", DIR)
    }.getOrElse { File("/data/user_de/0/df.root", DIR) }

    /** The daemon's log file. Mirrors DAEMON_LOG in dfctl.h. */
    fun logPath(ctx: Context): File = File(dir(ctx), LOG)

    /**
     * Whether a daemon is actually reachable.
     *
     * File existence is NOT enough: `dfctl.ready` lives in the app's own data
     * directory, so it is never cleaned up by anything but the daemon's own
     * exit path. A killed daemon - or one from a previous run, or a leftover
     * after the device rebooted - leaves the marker behind and the home screen
     * then claims a channel that cannot answer a single command.
     *
     * The only honest test is a real round trip, so this pings. Callers on the
     * main thread must use [pingBlocking] instead.
     */
    suspend fun isReady(ctx: Context): Boolean = ping(ctx)

    /** A single round trip. Safe to call repeatedly: ping has no side effects. */
    suspend fun ping(ctx: Context): Boolean = runCatching {
        when (val r = request(ctx, Verb.PING)) {
            is Outcome.Success -> r.result.ok
            is Outcome.Unreachable -> false
        }
    }.getOrDefault(false)

    /** A failed probe may mean the daemon is busy; never unlink its live channel. */
    suspend fun refreshReadiness(ctx: Context): Boolean = ping(ctx)

    /** Drop a false "uncertain reply" mark after a plan finishes serving again. */
    fun forgetUnreachable(ctx: Context) {
        tainted.remove(dir(ctx).absolutePath)
    }

    /** Bounded even for synchronous callers: lock acquisition and IO share one deadline. */
    fun pingBlocking(ctx: Context, timeoutMs: Long = 1500): Boolean =
        (exchange(ctx, Verb.PING, null, timeoutMs) as? Outcome.Success)?.result?.ok == true

    fun markerPresent(ctx: Context): Boolean =
        runCatching { File(dir(ctx), READY).exists() }.getOrDefault(false)

    /** Compatibility hook: probe failure alone cannot establish that unlinking is safe. */
    fun clearStaleChannel(ctx: Context): List<String> = emptyList()

    fun pid(ctx: Context): Int? = runCatching {
        val f = File(dir(ctx), PID)
        if (!f.isFile) return@runCatching null
        f.readText().trim().toIntOrNull()
    }.getOrNull()

    /** Human-readable state of the control channel, for the terminal header. */
    fun describeChannel(ctx: Context): String = runCatching {
        val d = dir(ctx)
        when {
            !d.exists() -> "未创建"
            d.listFiles() == null -> "无权访问"
            File(d, FIFO_IN).exists() -> "fifo"
            else -> "无通道"
        }
    }.getOrDefault("未知")

    /** What this uid can and cannot see, for the terminal. lstat succeeds even
     * when the entry is unreadable, as long as the parent is traversable, so a
     * screenshot is enough to diagnose without su or adb pull. */
    fun diagnose(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("uid: ").append(android.os.Process.myUid()).append('\n')
        val base = File("/data/user_de/0/df.root")
        sb.append("channel dir: ").append(dir(ctx).absolutePath).append('\n')

        for (p in listOf(base, dir(ctx))) {
            sb.append('\n').append(p.absolutePath).append('\n')
            sb.append("  exists(File): ").append(runCatching { p.exists() }.getOrDefault(false)).append('\n')
            sb.append("  listFiles:    ").append(
                runCatching { p.listFiles()?.size?.toString() ?: "null(不可读或非目录)" }
                    .getOrDefault("异常")
            ).append('\n')
            try {
                val st = android.system.Os.lstat(p.absolutePath)
                sb.append("  lstat: mode=%04o owner=%d group=%d\n".format(
                    st.st_mode and 0xFFF, st.st_uid, st.st_gid))
            } catch (e: Throwable) {
                sb.append("  lstat: 失败 ").append(e.message).append('\n')
            }
        }

        val contents = runCatching { dir(ctx).listFiles()?.map { it.name }?.sorted() }.getOrNull()
        sb.append("\n目录内容: ").append(contents?.joinToString(", ") ?: "无法列出").append('\n')

        for (name in listOf(FIFO_IN, FIFO_OUT, PID, READY)) {
            val f = File(dir(ctx), name)
            sb.append("  ").append(name.padEnd(12))
            try {
                val st = android.system.Os.lstat(f.absolutePath)
                sb.append("mode=%04o owner=%d size=%d".format(
                    st.st_mode and 0xFFF, st.st_uid, st.st_size))
            } catch (e: Throwable) {
                sb.append("缺失/不可见 (").append(e.message).append(')')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** One reply: the streamed body plus the terminating status. */
    data class Result(
        val lines: List<String>,
        val ok: Boolean,
        val exit: Int?,
        val error: String?,
    ) {
        val text: String get() = lines.joinToString("\n")
    }

    sealed interface Outcome {
        data class Success(val result: Result) : Outcome
        /** The daemon is not reachable; [why] is user-facing. */
        data class Unreachable(val why: String) : Outcome
    }

    /** Sends one request and collects the reply. [onLine] receives each body
     * line as it arrives, so long output can be watched live. */
    suspend fun request(
        ctx: Context,
        verb: String,
        arg: String? = null,
        onLine: ((String) -> Unit)? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        exchange(ctx, verb, arg, if (verb == Verb.PING) 1500 else 120_000, onLine)
    }

    private val channelLock = ReentrantLock()
    private val tainted = mutableSetOf<String>()

    internal fun validRequest(verb: String, arg: String?): Boolean {
        if (!verb.matches(Regex("[a-z][a-z-]*"))) return false
        val line = if (arg.isNullOrEmpty()) verb else "$verb $arg"
        return line.none { it == '\n' || it == '\r' || it == '\u0000' } &&
            line.toByteArray(Charsets.UTF_8).size + 1 < 4096
    }

    private fun exchange(ctx: Context, verb: String, arg: String?, timeoutMs: Long,
        onLine: ((String) -> Unit)? = null): Outcome {
        if (!validRequest(verb, arg)) return Outcome.Unreachable("Invalid request or UTF-8 line exceeds daemon limit")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1))
        val acquired = try { channelLock.tryLock(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (!acquired) return Outcome.Unreachable("Daemon channel is busy")
        var sent = false
        val d = dir(ctx)
        val key = d.absolutePath
        var input: FileDescriptor? = null
        var output: FileDescriptor? = null
        try {
            if (key in tainted) return Outcome.Unreachable("Daemon channel reply is uncertain; restart the app/daemon before reuse")
            val inPath = File(d, FIFO_IN).absolutePath
            val outPath = File(d, FIFO_OUT).absolutePath
            for (path in listOf(inPath, outPath)) {
                if (!OsConstants.S_ISFIFO(Os.lstat(path).st_mode)) return Outcome.Unreachable("Not a FIFO: $path")
            }
            val flags = OsConstants.O_RDWR or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW
            input = Os.open(inPath, flags, 0)
            output = Os.open(outPath, flags, 0)
            for (fd in listOf(input, output)) require(OsConstants.S_ISFIFO(Os.fstat(fd).st_mode)) { "Channel changed type" }
            val line = if (arg.isNullOrEmpty()) verb else "$verb $arg"
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
            var offset = 0
            while (offset < bytes.size) {
                waitFd(input, OsConstants.POLLOUT, deadline)
                try {
                    val n = Os.write(input, bytes, offset, bytes.size - offset)
                    if (n > 0) { offset += n; sent = true }
                } catch (e: ErrnoException) { if (e.errno != OsConstants.EAGAIN && e.errno != OsConstants.EINTR) throw e }
            }
            val fd = output
            val stream = object : java.io.InputStream() {
                override fun read(): Int {
                    val byte = ByteArray(1)
                    val count = read(byte, 0, 1)
                    return if (count < 0) -1 else byte[0].toInt() and 255
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    while (true) {
                        waitFd(fd, OsConstants.POLLIN, deadline)
                        try { return Os.read(fd, bytes, offset, length) }
                        catch (e: ErrnoException) { if (e.errno != OsConstants.EAGAIN && e.errno != OsConstants.EINTR) throw e }
                    }
                }
            }
            val result = collect(stream.bufferedReader(Charsets.UTF_8), verb, onLine)
            // Ping is used as a liveness probe while the daemon may be executing a
            // handed-off plan and not reading the FIFO. Timing out must not poison
            // the channel; leftover `.ok ping` is still a valid ping reply later.
            if (result is Outcome.Unreachable && verb != Verb.PING) tainted += key
            return result
        } catch (failure: Exception) {
            if (sent && verb != Verb.PING) tainted += key
            return Outcome.Unreachable("FIFO communication failed: ${failure.message}")
        } finally {
            input?.let { runCatching { Os.close(it) } }
            output?.let { runCatching { Os.close(it) } }
            channelLock.unlock()
        }
    }

    private fun waitFd(fd: FileDescriptor, events: Int, deadline: Long) {
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw java.net.SocketTimeoutException("Daemon request timed out")
            val poll = StructPollfd().apply { this.fd = fd; this.events = events.toShort() }
            try {
                val ready = Os.poll(arrayOf(poll), TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                if (ready == 0) continue
                if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLNVAL or OsConstants.POLLHUP) != 0)
                    throw java.io.IOException("Daemon FIFO closed")
                if (poll.revents.toInt() and events != 0) return
            } catch (e: ErrnoException) { if (e.errno != OsConstants.EINTR) throw e }
        }
    }

    /** Reads body lines until the `.ok` / `.err` status line. */
    internal fun collect(
        reader: java.io.BufferedReader,
        verb: String,
        onLine: ((String) -> Unit)?,
    ): Outcome {
        val body = ArrayList<String>()
        // A "|+" piece continues the current line rather than starting one.
        val logical = StringBuilder()

        fun flush() {
            if (logical.isNotEmpty()) {
                val text = logical.toString()
                body.add(text)
                onLine?.invoke(text)
                logical.setLength(0)
            }
        }

        var guard = 0
        while (true) {
            if (++guard > 200_000)
                return Outcome.Unreachable("回复过长,疑似协议错乱")
            val raw = reader.readLine() ?: break

            if (raw == "$OK $verb" || raw.startsWith("$OK $verb ")) {
                flush()
                return Outcome.Success(Result(body, true, exitOf(raw), null))
            }
            if (raw == "$ERR $verb" || raw.startsWith("$ERR $verb ")) {
                flush()
                val rest = raw.removePrefix(ERR).trim().removePrefix(verb).trim()
                return Outcome.Success(Result(body, false, exitOf(raw), rest.ifEmpty { "命令失败" }))
            }
            if (raw.startsWith(OK) || raw.startsWith(ERR)) return Outcome.Unreachable("Unexpected daemon reply: $raw")
            when {
                raw.startsWith("|+") -> logical.append(raw.substring(2))
                raw.startsWith("| ") -> {
                    flush()
                    logical.append(raw.substring(2))
                }
                raw == "|" -> flush()
                raw.startsWith("|") -> {
                    flush()
                    logical.append(raw.substring(1))
                }
                // Anything else is noise; ignore rather than fail the call.
            }
        }
        flush()
        return Outcome.Unreachable("daemon 关闭了连接,没有返回状态行")
    }

    private fun exitOf(status: String): Int? =
        Regex("""exit=(-?\d+)""").find(status)?.groupValues?.get(1)?.toIntOrNull()
}

/** Verb constants, mirroring dfctl.h. */
object Verb {
    const val PING = "ping"
    const val STATUS = "status"
    const val ENV = "env"
    const val EXEC = "exec"
    const val SCRIPT = "script"
    const val LATELOAD = "late-load"
    const val RMMOD = "rmmod"
    const val SOFT_REBOOT = "soft-reboot"
    const val EXIT = "exit"
    const val RUN_PLAN = "run-plan"
}
