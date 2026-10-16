/*
 * dfctl - the app <-> dfdaemon wire protocol.
 *
 * dfdaemon runs as uid 0 in the init context, started exactly once by the
 * kernel module's usermode helper after SELinux is made permissive. It cannot
 * be restarted without re-running the whole exploit, so the app must be able
 * to ask it what is going on at any point - including "which step are you stuck on".
 *
 * Transport: a FIFO pair under DAEMON_DIR, whose owner is the app's own uid,
 * so no other app can reach it.
 *
 * Requests and replies are newline-delimited text. A request is one line:
 *
 *     <verb> [arg...]
 *
 * A reply is one or more lines. Every reply ends with exactly one status
 * line so the client always knows when to stop reading:
 *
 *     .ok <verb>                      request succeeded
 *     .err <verb> <message>           request failed
 *
 * Output lines are prefixed so they can never be confused with the status:
 *     | <raw output line>
 *
 * A line longer than DFCTL_CHUNK is split rather than truncated. The first
 * piece is prefixed "| " and every continuation piece "|+", so the client can
 * tell a wrapped line from a genuine newline and rejoin it losslessly.
 */
#ifndef DFCTL_H
#define DFCTL_H

#include <fcntl.h>
#include <stddef.h>
#include <string.h>
#include <unistd.h>

#define DAEMON_DIR      "/data/user_de/0/df.root/daemon-control"
#define DAEMON_FIFO_IN  DAEMON_DIR "/dfctl.in"   /* app  -> daemon */
#define DAEMON_FIFO_OUT DAEMON_DIR "/dfctl.out"  /* daemon -> app */
#define DAEMON_PID      DAEMON_DIR "/dfctl.pid"
#define DAEMON_READY    DAEMON_DIR "/dfctl.ready"
#define DAEMON_LOG      DAEMON_DIR "/dfctl.log"

/* Module-plan handoff. The app writes the sequence (and per-step shell files)
 * here before starting the daemon. dfdaemon consumes PLAN_FILE at startup and
 * again while serving, so later steps do not wait for the FIFO. */
#define PLAN_DIR        "/data/user_de/0/df.root/files/runs"
#define PLAN_FILE       PLAN_DIR "/plan"
#define PLAN_RUNNING    PLAN_DIR "/plan.running"
#define PLAN_STATUS     PLAN_DIR "/plan.status"
#define PLAN_LOG        PLAN_DIR "/plan.log"

/* Run markers, one file per step. Under the app's data directory rather than
 * /dev: this is the app's own tree, so the app can read the timeline back, and
 * a marker cannot be confused with a device node. */
#define MARKER_DIR      "/data/user_de/0/df.root/markers"
#define MARKER(s)       MARKER_DIR "/" s
#define BOOT_ID_PROC    "/proc/sys/kernel/random/boot_id"

/* Marker files store the kernel boot id as their contents. A leftover file
 * from a previous boot is ignored because the stored id no longer matches
 * BOOT_ID_PROC — no directory wipe is required. */
static inline int dfctl_read_trimmed(const char *path, char *buf, size_t n)
{
    int fd;
    ssize_t r;
    if (!path || !buf || n == 0) return -1;
    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    r = read(fd, buf, n - 1);
    close(fd);
    if (r < 0) return -1;
    buf[r] = '\0';
    while (r > 0) {
        char c = buf[r - 1];
        if (c != '\n' && c != '\r' && c != ' ' && c != '\t') break;
        buf[--r] = '\0';
    }
    return 0;
}

static inline int dfctl_live_boot_id(char *buf, size_t n)
{
    return dfctl_read_trimmed(BOOT_ID_PROC, buf, n);
}

/* Marker files start with the boot id; extra lines are payload. */
static inline int dfctl_first_line(const char *text, char *out, size_t n)
{
    size_t i = 0;
    if (!text || !out || n == 0) return -1;
    while (text[i] && text[i] != '\n' && text[i] != '\r' && i + 1 < n) {
        out[i] = text[i];
        i++;
    }
    out[i] = '\0';
    while (i > 0) {
        char c = out[i - 1];
        if (c != ' ' && c != '\t') break;
        out[--i] = '\0';
    }
    return 0;
}

static inline int dfctl_marker_this_boot(const char *path)
{
    char live[80], stored[256], first[80];
    if (dfctl_live_boot_id(live, sizeof live) != 0 || !live[0]) return 0;
    if (dfctl_read_trimmed(path, stored, sizeof stored) != 0 || !stored[0]) return 0;
    if (dfctl_first_line(stored, first, sizeof first) != 0 || !first[0]) return 0;
    return strcmp(live, first) == 0;
}

/* The daemon's own step trace, appended to as each stage completes. Separate
 * from DAEMON_LOG (which is the startup banner) so triage can see ordering. */
#define DAEMON_STEP_LOG DAEMON_DIR "/steps.log"

/* Longest single request line, including the newline. Raw output is sent in
 * DFCTL_CHUNK sized pieces so a huge command output cannot overflow a reply. */
#define DFCTL_LINE_MAX  4096
#define DFCTL_CHUNK     2048

/* Reply prefixes. */
#define DFCTL_OK    ".ok"
#define DFCTL_ERR   ".err"
#define DFCTL_BODY  '|'
/* Marks a piece that continues the previous line rather than starting a new
 * one. Emitted as the two bytes "|+". */
#define DFCTL_CONT  '+'

/* ---- verbs ---------------------------------------------------------- */

/* Liveness plus the one question that matters when a run is stuck: which
 * step the daemon has reached. Always safe to call. */
#define DFCTL_PING      "ping"
/* Same payload as ping but includes the resolved symbol addresses, the SELinux
 * state, the prefs the daemon parsed and its own credentials. This is the
 * "why did it fail" command. */
#define DFCTL_STATUS    "status"
/* The complete inherited environment. Split out of status because it is long
 * and only occasionally the thing you need. */
#define DFCTL_ENV       "env"
/* Run one shell command as uid 0 in the init context and stream its output
 * back. This is the escape hatch the old build never had. */
#define DFCTL_EXEC      "exec"
/* Run one of the staged preset scripts by name (preload/postload). */
#define DFCTL_SCRIPT    "script"
/* Run the real late-load step by hand: brings up the SU daemon. */
#define DFCTL_LATELOAD  "late-load"
/* Unload dfroot.ko and drop the daemon's own privileges path. */
#define DFCTL_RMMOD     "rmmod"
/* Ask the manager to soft reboot. */
#define DFCTL_SOFTREBOOT "soft-reboot"
/* Leave the process. The app should only ask for this at the very end. */
#define DFCTL_EXIT      "exit"
/* Consume PLAN_FILE if present. Used by the terminal; the daemon also runs
 * the plan on its own at startup and while polling the control channel. */
#define DFCTL_RUNPLAN   "run-plan"

/* ---- daemon step machine -------------------------------------------- */

/* Reported verbatim by ping/status so the app can render "stuck at step N". */
#define DFCTL_STEP_START    0
#define DFCTL_STEP_PREFS    1
#define DFCTL_STEP_ZYGOTE   2
#define DFCTL_STEP_RO       3
#define DFCTL_STEP_MODULES  4
#define DFCTL_STEP_READY    5
#define DFCTL_STEP_LATELOAD 6
#define DFCTL_STEP_DONE     7
#define DFCTL_STEP_FAILED   8

static inline const char *dfctl_step_name(int step)
{
    switch (step) {
    case DFCTL_STEP_START:    return "start";
    case DFCTL_STEP_PREFS:    return "reading-prefs";
    case DFCTL_STEP_ZYGOTE:   return "adopting-zygote-env";
    case DFCTL_STEP_RO:       return "setting-partitions-ro";
    case DFCTL_STEP_MODULES:  return "disabling-modules";
    case DFCTL_STEP_READY:    return "ready";
    case DFCTL_STEP_LATELOAD: return "late-load";
    case DFCTL_STEP_DONE:     return "done";
    case DFCTL_STEP_FAILED:   return "failed";
    default:                  return "unknown";
    }
}

#endif /* DFCTL_H */
