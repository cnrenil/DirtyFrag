/*
 * dfdaemon - a resident root daemon that replaces fire-and-forget bootstrap.
 *
 * Started exactly once by the kernel module's usermode helper and not
 * restartable without re-running the exploit, so instead of exiting it stays
 * alive and serves the dfctl protocol over a FIFO pair. The app can then ask
 * which step it is on, run commands as uid 0, or drive late-load by hand.
 */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "dfctl.h"

#define BLKROSET   0x125d
#define SU_STAGE   "/data/user_de/0/df.root/"
#define KSUD       "/data/adb/ksud"
#define APD        "/data/adb/apd"
#define PREFS_PATH "/data/user_de/0/df.root/shared_prefs/dfroot.xml"
#define MODULES_DIR "/data/adb/modules"

/* The app's device-protected data directory. Its owner is the app uid, which
 * is how the daemon learns who is allowed to use the control channel. */
#define APP_DATA_DIR "/data/user_de/0/df.root"

/* ------------------------------------------------------------------ state */

/* Everything the status verb reports. Kept global because the request handler
 * and the startup sequence both fill it in. */
struct dfstate {
    int   step;                 /* DFCTL_STEP_* */
    int   prefs_ok;
    int   zygote_ok;
    int   partitions_ro_ok;
    int   modules_disabled_ok;
    int   late_load_rc;
    int   late_load_attempts;
    int   soft_reboot;
    int   disable_mods;
    int   use_apd;
    char  su_manager[256];
    char  su_backend[16];
    char  kmi[64];
    char  fail_why[256];
};

static struct dfstate g;
/* Recorded so a silent chown failure is not mistaken for a daemon that never
 * started. */
static char g_owner_note[160];

static const char *g_stage;      /* staged late-load binary */
static char g_stage_buf[512];
static const char *g_tool;       /* "ksud" or "apd" */
static int g_in_plan;            /* replies silenced; output goes to PLAN_LOG */

static int g_listen_fd = -1;     /* fifo read end  (app -> daemon) */
static int g_reply_fd  = -1;     /* fifo write end (daemon -> app) */
static volatile sig_atomic_t g_stop;

/* ------------------------------------------------------------------ misc */

/* Ownership for everything the daemon creates on the app's behalf.
 *
 * The daemon runs as uid 0; files it drops into the app's data directory are
 * root:root unless we say otherwise, and the app then cannot read its own
 * diagnostics. Both ids are taken from the data directory (never from a
 * client), and every creation path runs through app_chown(). */
static uid_t g_app_uid = (uid_t)-1;
static gid_t g_app_gid = (gid_t)-1;

static void resolve_app_owner(void)
{
    struct stat st;
    if (stat(APP_DATA_DIR, &st) == 0) {
        g_app_uid = st.st_uid;
        g_app_gid = st.st_gid;
    }
}

/* Chown uid AND gid. A uid-only chown leaves the group as root, which defeats
 * anything that checks group access. Best effort: a failure is recorded in the
 * owner note and never aborts the run. */
static void app_chown(const char *path)
{
    if (g_app_uid == (uid_t)-1)
        return;
    if (chown(path, g_app_uid, g_app_gid) != 0) {
        /* Keep the first failure only; later ones are usually consequences. */
        if (!g_owner_note[0])
            snprintf(g_owner_note, sizeof(g_owner_note),
                     "chown(%s, %d, %d) failed: %s", path,
                     (int)g_app_uid, (int)g_app_gid, strerror(errno));
    }
}

static void ensure_dir(const char *path, mode_t mode)
{
    mkdir(path, mode);
    app_chown(path);
    chmod(path, mode);
}

static void step_log(const char *fmt, ...);

/* Creates a marker file readable by the app.
 *
 * Markers are created *then* chowned, so use O_CREAT rather than relying on an
 * afterwards fixup: the window between create and chown is why the old code's
 * markers could end up unreadable. */
static void touch(const char *path)
{
    int fd = open(path, O_CREAT | O_WRONLY | O_CLOEXEC, 0600);
    if (fd >= 0) {
        app_chown(path);
        chmod(path, 0600);
        close(fd);
    }
}

/* Bind a marker to this boot. First line is the live boot id; extra is payload. */
static void write_marker(const char *path, const char *id, const char *extra)
{
    int fd = open(path, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
    if (fd < 0) return;
    if (id && id[0]) {
        ssize_t w = write(fd, id, strlen(id));
        (void)w;
        if (extra && extra[0]) {
            w = write(fd, "\n", 1);
            (void)w;
            w = write(fd, extra, strlen(extra));
            (void)w;
        }
    }
    close(fd);
    app_chown(path);
    chmod(path, 0600);
}

static void mark_msg(const char *name, const char *extra)
{
    char path[256];
    char id[80];
    int n = snprintf(path, sizeof(path), "%s/%s", MARKER_DIR, name);
    if (n <= 0 || (size_t)n >= sizeof(path)) return;
    if (dfctl_live_boot_id(id, sizeof id) != 0) id[0] = '\0';
    write_marker(path, id, extra);
    write_marker(MARKER("boot_id"), id, NULL);
}

static void mark(const char *name)
{
    mark_msg(name, NULL);
}

static int selinux_enforcing(void)
{
    char buf[8];
    if (dfctl_read_trimmed("/sys/fs/selinux/enforce", buf, sizeof buf) != 0)
        return 0;
    return buf[0] == '1';
}

static int stop_if_enforcing(void)
{
    if (!selinux_enforcing()) return 0;
    step_log("SELinux is enforcing; daemon exiting");
    g_stop = 1;
    unlink(DAEMON_READY);
    return 1;
}

/* Appends a step trace line to the daemon's own log, owned by the app. */
static void step_log(const char *fmt, ...)
{
    char line[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(line, sizeof(line) - 2, fmt, ap);
    va_end(ap);
    if (n < 0)
        return;
    if (n > (int)sizeof(line) - 2)
        n = (int)sizeof(line) - 2;
    line[n++] = '\n';
    line[n] = '\0';

    int fd = open(DAEMON_STEP_LOG, O_CREAT | O_WRONLY | O_APPEND | O_CLOEXEC, 0600);
    if (fd < 0)
        return;
    app_chown(DAEMON_STEP_LOG);
    ssize_t w = write(fd, line, (size_t)n);
    (void)w;
    close(fd);
}

static void mark_failed(int step, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(g.fail_why, sizeof(g.fail_why), fmt, ap);
    va_end(ap);
    g.step = step;
}

/* appends to a growing, NUL-terminated buffer; silently stops when full */
struct buf {
    char  *p;
    size_t len;
    size_t cap;
};

static void buf_add(struct buf *b, const char *fmt, ...)
{
    if (b->len + 1 >= b->cap)
        return;
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(b->p + b->len, b->cap - b->len, fmt, ap);
    va_end(ap);
    if (n > 0)
        b->len += (size_t)n;
    if (b->len >= b->cap)
        b->len = b->cap - 1;
}

/* Reads the whole of path into a freshly allocated NUL-terminated string. */
static char *slurp(const char *path, size_t max)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0)
        return NULL;
    char *out = calloc(1, max + 1);
    if (!out) { close(fd); return NULL; }
    ssize_t n = read(fd, out, max);
    close(fd);
    if (n <= 0) { free(out); return NULL; }
    out[n] = '\0';
    return out;
}

/* ------------------------------------------------------------------ prefs */

static int pref_true(const char *buf_, const char *key)
{
    char needle[64];
    snprintf(needle, sizeof(needle), "name=\"%s\"", key);
    char *p = strstr(buf_, needle);
    if (!p) return 0;
    char *tag_end = strchr(p, '>');
    char *v = strstr(p, "value=\"true\"");
    return v && tag_end && v < tag_end;
}

static void pref_str(const char *buf_, const char *key, char *out, size_t out_size)
{
    out[0] = '\0';
    char needle[64];
    snprintf(needle, sizeof(needle), "name=\"%s\">", key);
    char *p = strstr(buf_, needle);
    if (!p) return;
    p += strlen(needle);
    char *end = strchr(p, '<');
    if (!end || end == p) return;
    size_t len = (size_t)(end - p);
    if (len >= out_size) return;
    memcpy(out, p, len);
    out[len] = '\0';
}

/* Best-effort legacy late-load configuration; never a daemon prerequisite. */
static int read_prefs(void)
{
    g_stage_buf[0] = '\0';
    g.prefs_ok = 0;
    g.su_manager[0] = '\0';
    g.su_backend[0] = '\0';
    g.kmi[0] = '\0';
    g.soft_reboot = 0;
    g.disable_mods = 0;
    g.use_apd = 0;
    g_stage = KSUD;
    g_tool = "ksud";
    char *buf_ = slurp(PREFS_PATH, 65535);
    if (!buf_) {
        /* Distinguish "cannot read" from "not there" - it matters. */
        struct stat st;
        if (stat(PREFS_PATH, &st) != 0)
            snprintf(g.fail_why, sizeof(g.fail_why), "cannot stat %s: %s",
                     PREFS_PATH, strerror(errno));
        else
            snprintf(g.fail_why, sizeof(g.fail_why), "cannot read %s: %s",
                     PREFS_PATH, strerror(errno));
        return -1;
    }

    pref_str(buf_, "su_manager", g.su_manager, sizeof(g.su_manager));
    pref_str(buf_, "su_backend", g.su_backend, sizeof(g.su_backend));
    pref_str(buf_, "su_library", g_stage_buf, sizeof(g_stage_buf));
    pref_str(buf_, "kmi", g.kmi, sizeof(g.kmi));
    g.soft_reboot  = pref_true(buf_, "soft_reboot")
                  || pref_true(buf_, "auto_soft_reboot");
    g.disable_mods = pref_true(buf_, "disable_modules");

    free(buf_);

    if (!g.su_manager[0]) {
        snprintf(g.fail_why, sizeof(g.fail_why), "su_manager is not set in %s", PREFS_PATH);
        return -1;
    }
    g.use_apd = !strcmp(g.su_backend, "apd");
    /* First late-load precedes installation of /data/adb's CLI. Use the
     * package library recorded by selectManager; old configurations fall back. */
    g_stage = g_stage_buf[0] ? g_stage_buf : (g.use_apd ? APD : KSUD);
    g_tool  = g.use_apd ? "apd" : "ksud";

    /* Present is not the same as usable: the binary must be executable. The
     * manager package the app selected is the authority on whether it should
     * be there at all, so a failure here is reported rather than worked around. */
    if (access(g_stage, X_OK) != 0) {
        snprintf(g.fail_why, sizeof(g.fail_why), "%s is not executable: %s",
                 g_stage, strerror(errno));
        return -1;
    }
    g.prefs_ok = 1;
    g.fail_why[0] = '\0';
    return 0;
}

/* ------------------------------------------------------------------ steps */

/* KSU only lets init transition into ksud. SELinux is still permissive
 * until late-load; write current (not exec) while we still can. */
static void try_become_init(void)
{
    int fd = open("/proc/self/attr/current", O_WRONLY);
    if (fd < 0)
        return;
    const char *ctx = "u:r:init:s0";
    ssize_t w = write(fd, ctx, strlen(ctx));
    (void)w;
    close(fd);
}

static int run_ctx(const char *ctx, char *const argv[])
{
    pid_t pid = fork();
    if (pid < 0)
        return -1;
    if (pid == 0) {
        try_become_init();
        if (ctx) {
            int fd = open("/proc/self/attr/exec", O_WRONLY);
            if (fd >= 0) { ssize_t w = write(fd, ctx, strlen(ctx)); (void)w; close(fd); }
        }
        execv(argv[0], argv);
        _exit(127);
    }
    int status;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR)
        ;
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

static int adopt_zygote_env(void)
{
    char *env = slurp("/proc/1/environ", 65535);
    if (!env)
        return -1;
    /* NUL-separated; adopt the ones that matter for a working exec env. */
    static const char *want[] = { "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_RUNTIME_ROOT",
                                  "ANDROID_TZDATA_ROOT", "ANDROID_ART_ROOT",
                                  "BOOTCLASSPATH", "EXTERNAL_STORAGE", NULL };
    for (size_t i = 0; want[i]; i++) {
        size_t wl = strlen(want[i]);
        for (char *p = env; *p; p += strlen(p) + 1) {
            if (!strncmp(p, want[i], wl) && p[wl] == '=') {
                char *eq = strchr(p, '=');
                if (eq) setenv(want[i], eq + 1, 1);
                break;
            }
        }
    }
    free(env);
    return 0;
}

static int late_load_running(const char *name)
{
    DIR *d = opendir("/proc");
    if (!d)
        return 0;
    struct dirent *e;
    char path[64], buf[256];
    int found = 0;
    while ((e = readdir(d)) != NULL) {
        char *end;
        strtol(e->d_name, &end, 10);
        if (*end) continue;
        snprintf(path, sizeof(path), "/proc/%s/cmdline", e->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n <= 0) continue;
        buf[n] = '\0';
        for (ssize_t i = 0; i < n; i++)
            if (!buf[i]) buf[i] = ' ';
        if (strstr(buf, name) && strstr(buf, "late-load")) {
            found = 1;
            break;
        }
    }
    closedir(d);
    return found;
}

/* The action the old bootstrap performed automatically. Exposed as a verb so
 * it can also be run by hand, and so a failure is reportable. */
static int do_late_load(struct buf *out)
{
    char *argv[8];
    int argc = 0;
    g.late_load_attempts++;
    g.step = DFCTL_STEP_LATELOAD;

    argv[argc++] = (char *)g_stage;
    argv[argc++] = "late-load";
    argv[argc++] = "--package-name";
    argv[argc++] = g.su_manager;
    if (g.kmi[0]) {
        argv[argc++] = "--kmi";
        argv[argc++] = g.kmi;
    }
    argv[argc] = NULL;

    buf_add(out, "%s", g_stage);
    for (int i = 1; argv[i]; i++)
        buf_add(out, " %s", argv[i]);
    buf_add(out, "\n");

    int rc = run_ctx(NULL, argv);
    g.late_load_rc = rc;
    if (rc == 0) {
        g.step = DFCTL_STEP_DONE;
        buf_add(out, "late-load exit=0\n");
    } else {
        g.step = DFCTL_STEP_FAILED;
        mark_failed(DFCTL_STEP_FAILED, "late-load exited %d", rc);
        buf_add(out, "late-load exit=%d\n", rc);
    }
    return rc;
}

/* ---------------------------------------------------------- reply plumbing */

static void reply(const char *fmt, ...)
{
    if (g_in_plan || g_reply_fd < 0)
        return;
    char line[DFCTL_LINE_MAX];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    size_t n = strlen(line);
    if (n == 0 || line[n - 1] != '\n') {
        if (n + 1 < sizeof(line)) { line[n++] = '\n'; line[n] = '\0'; }
    }
    ssize_t r = write(g_reply_fd, line, n);
    (void)r;
}

static void reply_ok(const char *verb)
{
    reply("%s %s", DFCTL_OK, verb);
}

static void reply_err(const char *verb, const char *fmt, ...)
{
    char msg[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    reply("%s %s %s", DFCTL_ERR, verb, msg);
}

/* Prefixes body lines so a reply is never mistaken for the status line. Long
 * lines are split ("| " then "|+"), not truncated, so a long PATH survives. */
static void reply_body(const char *text)
{
    if (!text)
        return;
    char line[DFCTL_CHUNK + 8];
    const int hdr = 2;              /* "| " or "|+" */
    const size_t payload_max = sizeof(line) - (size_t)hdr - 1;

    const char *p = text;
    while (*p) {
        const char *nl = strchr(p, '\n');
        size_t avail = nl ? (size_t)(nl - p) : strlen(p);
        int first = 1;

        /* Emit the logical line in payload_max sized pieces. A zero-length
         * piece still emits one prefixed line so blank separators survive. */
        do {
            size_t take = avail > payload_max ? payload_max : avail;
            if (g_reply_fd >= 0) {
                line[0] = DFCTL_BODY;
                line[1] = first ? ' ' : DFCTL_CONT;
                memcpy(line + hdr, p, take);
                line[hdr + take] = '\n';
                ssize_t r = write(g_reply_fd, line, (size_t)hdr + take + 1);
                (void)r;
            }
            p += take;
            avail -= take;
            first = 0;
        } while (avail > 0);

        if (!nl)
            break;
        p = nl + 1;
    }
}

/* Runs a shell command and streams its merged output into the reply. */
static int run_shell_stream(const char *cmd, struct buf *out)
{
    int pipefd[2];
    if (pipe(pipefd) != 0)
        return -1;

    pid_t pid = fork();
    if (pid < 0) {
        close(pipefd[0]); close(pipefd[1]);
        return -1;
    }
    if (pid == 0) {
        close(pipefd[0]);
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[1]);
        execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
        _exit(127);
    }
    close(pipefd[1]);

    /* Stream as it arrives: the whole point is to watch a slow command work,
     * not to wait for it to finish. */
    char chunk[DFCTL_CHUNK + 1];
    ssize_t n;
    while ((n = read(pipefd[0], chunk, DFCTL_CHUNK)) > 0) {
        chunk[n] = '\0';
        reply_body(chunk);
        buf_add(out, "%s", chunk);
    }
    close(pipefd[0]);

    int status;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR)
        ;
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

/* ------------------------------------------------------------- status verb */

static void handle_status(struct buf *out)
{
    char cwd[256] = {0};
    if (!getcwd(cwd, sizeof(cwd)))
        snprintf(cwd, sizeof(cwd), "?");

    char exe[256] = {0};
    ssize_t e = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (e > 0) exe[e] = '\0'; else snprintf(exe, sizeof(exe), "?");

    buf_add(out, "step: %s (%d)\n", dfctl_step_name(g.step), g.step);
    buf_add(out, "pid: %d\n", (int)getpid());
    buf_add(out, "uid: %d euid: %d gid: %d\n", (int)getuid(), (int)geteuid(), (int)getgid());
    buf_add(out, "exe: %s\n", exe);
    buf_add(out, "cwd: %s\n", cwd);
    buf_add(out, "owner: %s\n", g_owner_note[0] ? g_owner_note : "(not attempted)");
    /* The app needs to see these from its own uid: if it cannot, the channel
     * is unreachable however healthy everything else looks. */
    {
        struct stat st;
        if (stat(DAEMON_DIR, &st) == 0)
            buf_add(out, "channel dir: mode=%04o owner=%d\n",
                    st.st_mode & 07777, (int)st.st_uid);
        const char *path = DAEMON_FIFO_IN;
        if (stat(path, &st) == 0)
            buf_add(out, "channel:     mode=%04o owner=%d\n",
                    st.st_mode & 07777, (int)st.st_uid);
    }
    buf_add(out, "\n");

    buf_add(out, "prefs: %s\n", g.prefs_ok ? "ok" : "FAILED");
    buf_add(out, "  su_manager: %s\n", g.su_manager[0] ? g.su_manager : "(unset)");
    buf_add(out, "  su_backend: %s\n", g.su_backend[0] ? g.su_backend : "(unset)");
    buf_add(out, "  kmi:        %s\n", g.kmi[0] ? g.kmi : "(autodetect)");
    buf_add(out, "  soft_reboot: %d  disable_modules: %d\n", g.soft_reboot, g.disable_mods);
    buf_add(out, "  late-load binary: %s\n", g_stage ? g_stage : "(unset)");
    if (g_stage) {
        struct stat st;
        if (stat(g_stage, &st) == 0)
            buf_add(out, "    size=%lld mode=%04o\n", (long long)st.st_size, st.st_mode & 07777);
        else
            buf_add(out, "    stat failed: %s\n", strerror(errno));
    }
    buf_add(out, "\n");

    buf_add(out, "zygote env:  %s\n", g.zygote_ok ? "adopted" : "not adopted");
    buf_add(out, "partitions:  %s\n", g.partitions_ro_ok ? "ro" : "unchanged");
    buf_add(out, "modules:     %s\n", g.modules_disabled_ok ? "disabled" : "untouched");
    buf_add(out, "late-load:   attempts=%d last_rc=%d\n",
            g.late_load_attempts, g.late_load_rc);
    if (g.fail_why[0])
        buf_add(out, "FAILURE: %s\n", g.fail_why);
    buf_add(out, "\n");

    /* The usual cause of a child that exits 127 is a PATH missing the vendor
     * dirs; the rest is one `exec env` away. */
    buf_add(out, "env (selected):\n");
    static const char *keys[] = { "PATH", "ANDROID_ROOT", "ANDROID_DATA",
                                  "ANDROID_RUNTIME_ROOT", "BOOTCLASSPATH",
                                  "LD_LIBRARY_PATH", "HOME", NULL };
    for (size_t i = 0; keys[i]; i++) {
        const char *v = getenv(keys[i]);
        if (v) {
            /* PATH is long enough to wrap; show it fully but on its own line. */
            buf_add(out, "  %s=%s\n", keys[i], v);
        } else {
            buf_add(out, "  %s=(unset)\n", keys[i]);
        }
    }
    buf_add(out, "\n");

    char *ctx = slurp("/proc/self/attr/current", 256);
    if (ctx) {
        char *nl = strchr(ctx, '\n');
        if (nl) *nl = '\0';
        buf_add(out, "context: %s\n", ctx);
        free(ctx);
    }

    /* Whether the kernel module is actually resident is the single most
     * useful fact when late-load fails. */
    buf_add(out, "\n");
    char *mods = slurp("/proc/modules", 65535);
    if (mods) {
        buf_add(out, "dfroot loaded: %s\n", strstr(mods, "dfroot") ? "YES" : "no");
        free(mods);
    }
}

/* ------------------------------------------------------- request dispatch */

static int cleanup_runtime(int keep_reply, int remove_logs, int stop_daemon);
static int prepare_reboot(const char *verb);
static void request_soft_reboot(const char *verb, int prepared);
static int run_plan(void);

static void handle_line(char *line)
{
    /* strip trailing newline / CR */
    size_t n = strlen(line);
    while (n && (line[n - 1] == '\n' || line[n - 1] == '\r'))
        line[--n] = '\0';
    if (!n)
        return;

    char *verb = line;
    char *arg = strchr(line, ' ');
    if (arg) { *arg = '\0'; arg++; while (*arg == ' ') arg++; }

    if (!strcmp(verb, DFCTL_PING)) {
        char body[256];
        snprintf(body, sizeof(body), "step: %s (%d)\npid: %d\n",
                 dfctl_step_name(g.step), g.step, (int)getpid());
        reply_body(body);
        reply_ok(verb);

    } else if (!strcmp(verb, DFCTL_STATUS)) {
        struct buf b = { calloc(1, 65536), 0, 65536 };
        if (!b.p) { reply_err(verb, "out of memory"); return; }
        handle_status(&b);
        reply_body(b.p);
        free(b.p);
        reply_ok(verb);

    } else if (!strcmp(verb, DFCTL_ENV)) {
        struct buf b = { calloc(1, 1 << 18), 0, 1 << 18 };
        if (!b.p) { reply_err(verb, "out of memory"); return; }
        extern char **environ;
        for (char **p = environ; p && *p; p++)
            buf_add(&b, "%s\n", *p);
        reply_body(b.p);
        free(b.p);
        reply_ok(verb);

    } else if (!strcmp(verb, DFCTL_EXEC)) {        if (!arg || !*arg) { reply_err(verb, "usage: exec <command>"); return; }
        struct buf b = { calloc(1, 1 << 20), 0, 1 << 20 };
        if (!b.p) { reply_err(verb, "out of memory"); return; }
        int rc = run_shell_stream(arg, &b);
        free(b.p);
        /* The exit code goes in the status line so the client can show it. */
        reply("%s %s exit=%d", rc == 0 ? DFCTL_OK : DFCTL_ERR, verb, rc);

    } else if (!strcmp(verb, DFCTL_SCRIPT)) {
        if (!arg || !*arg) { reply_err(verb, "usage: script <preload|postload>"); return; }
        char path[512];
        snprintf(path, sizeof(path), "%sfiles/%s.sh", SU_STAGE, arg);
        struct stat st;
        if (stat(path, &st) != 0) { reply_err(verb, "no such script: %s", path); return; }
        if (st.st_size <= 0)      { reply_err(verb, "script is empty: %s", path); return; }
        struct buf b = { calloc(1, 1 << 20), 0, 1 << 20 };
        if (!b.p) { reply_err(verb, "out of memory"); return; }
        char cmd[600];
        snprintf(cmd, sizeof(cmd), "/system/bin/sh %s", path);
        int rc = run_shell_stream(cmd, &b);
        free(b.p);
        reply("%s %s exit=%d", rc == 0 ? DFCTL_OK : DFCTL_ERR, verb, rc);

    } else if (!strcmp(verb, DFCTL_LATELOAD)) {
        if (arg && *arg && strcmp(arg, "soft-reboot")) {
            reply_err(verb, "usage: late-load [soft-reboot]");
            return;
        }
        if (read_prefs() != 0) {
            step_log("late-load refused: %s", g.fail_why);
            reply_err(verb, "late-load is not configured: %s", g.fail_why);
            return;
        }
        step_log("late-load: stage=%s manager=%s kmi=%s", g_stage, g.su_manager, g.kmi[0] ? g.kmi : "(none)");
        struct buf b = { calloc(1, 1 << 16), 0, 1 << 16 };
        if (!b.p) { reply_err(verb, "out of memory"); return; }
        int reboot = arg && !strcmp(arg, "soft-reboot");
        if (reboot && prepare_reboot(verb) != 0) { free(b.p); return; }
        int rc = do_late_load(&b);
        if (!reboot) reply_body(b.p);
        free(b.p);
        step_log("late-load exit=%d", rc);
        if (reboot) {
            if (rc == 0) {
                step_log("late-load succeeded; waiting 4 seconds before soft reboot");
                sleep(4);
                request_soft_reboot(verb, 1);
            } else step_log("soft-reboot cancelled: late-load failed exit=%d; daemon exiting", rc);
        } else reply("%s %s exit=%d", rc == 0 ? DFCTL_OK : DFCTL_ERR, verb, rc);

    } else if (!strcmp(verb, DFCTL_RMMOD)) {
        int rc = run_ctx(NULL, (char *[]){ "/system/bin/rmmod", "dfroot", NULL });
        step_log("rmmod dfroot exit=%d", rc);
        reply("%s %s exit=%d", rc == 0 ? DFCTL_OK : DFCTL_ERR, verb, rc);

    } else if (!strcmp(verb, DFCTL_SOFTREBOOT)) {
        request_soft_reboot(verb, 0);

    } else if (!strcmp(verb, DFCTL_EXIT)) {
        reply_ok(verb);
        g_stop = 1;

    } else if (!strcmp(verb, DFCTL_RUNPLAN)) {
        int rc = run_plan();
        if (rc == 0) reply_ok(verb);
        else reply("%s %s exit=%d", DFCTL_ERR, verb, rc);

    } else {
        reply_err(verb, "unknown verb");
    }
}

/* ----------------------------------------------------------------- server */

static int cleanup_unlink(const char *path)
{
    if (unlink(path) == 0) { step_log("cleanup: removed %s", path); return 0; }
    if (errno == ENOENT) return 0;
    step_log("cleanup: unlink %s failed: %s", path, strerror(errno));
    return -1;
}

static int cleanup_runtime(int keep_reply, int remove_logs, int stop_daemon)
{
    if (stop_daemon) g_stop = 1;
    step_log("cleanup: stopping daemon and removing runtime state (no module unload)");
    if (stop_daemon && g_listen_fd >= 0) { close(g_listen_fd); g_listen_fd = -1; }
    if (!keep_reply && stop_daemon && g_reply_fd >= 0) { close(g_reply_fd); g_reply_fd = -1; }
    int failed = 0;
    failed |= cleanup_unlink(DAEMON_READY) != 0;
    failed |= cleanup_unlink(DAEMON_PID) != 0;
    step_log("cleanup complete: result=%s; logs %s", failed ? "failed" : "ok", remove_logs ? "will be removed" : "retained");
    if (remove_logs) {
        failed |= cleanup_unlink(DAEMON_LOG) != 0;
        if (unlink(DAEMON_STEP_LOG) != 0 && errno != ENOENT) {
            step_log("cleanup: unlink %s failed: %s", DAEMON_STEP_LOG, strerror(errno));
            failed = 1;
        }
    }
    return failed ? -1 : 0;
}

/* Complete cleanup and acknowledge acceptance while the channel still works. */
static int prepare_reboot(const char *verb)
{
    if (cleanup_runtime(1, 0, 1) != 0) {
        reply_err(verb, "runtime cleanup failed; late-load and soft reboot cancelled, see steps.log");
        return -1;
    }
    step_log("soft-reboot preparation: cleanup completed before late-load");
    int fd = open(DAEMON_STEP_LOG, O_WRONLY | O_CLOEXEC);
    if (fd < 0) { reply_err(verb, "cannot open cleanup log; request cancelled"); return -1; }
    int rc = fsync(fd);
    close(fd);
    if (rc != 0) { reply_err(verb, "cannot sync cleanup log; request cancelled"); return -1; }
    reply_body("Request accepted: cleanup completed before late-load; late-load and soft reboot will run without further channel replies.\n");
    reply_ok(verb);
    if (g_reply_fd >= 0) { close(g_reply_fd); g_reply_fd = -1; }
    return 0;
}

static void request_soft_reboot(const char *verb, int prepared)
{
    if (!prepared && read_prefs() != 0) {
        step_log("soft-reboot refused: %s", g.fail_why);
        reply_err(verb, "soft-reboot is not configured: %s", g.fail_why);
        return;
    }
    const char *binary = g.use_apd ? APD : KSUD;
    if (prepared) {
        step_log("soft-reboot: dispatching %s using cached configuration after late-load", binary);
        int rc = g.use_apd
            ? run_ctx(NULL, (char *[]){ APD, "soft-reboot", NULL })
            : run_ctx("u:r:ksu:s0", (char *[]){ KSUD, "soft-reboot", NULL });
        step_log("soft-reboot exit=%d; daemon exiting", rc);
        return;
    }
    if (access(binary, X_OK) != 0) {
        reply_err(verb, "%s is not executable: %s", binary, strerror(errno));
        return;
    }
    for (int i = 0; i < 30 && late_load_running(g_tool); i++) {
        step_log("soft-reboot: waiting for %s late-load (%ds)", g_tool, i);
        sleep(1);
    }
    if (late_load_running(g_tool)) {
        step_log("soft-reboot refused: late-load still running");
        reply_err(verb, "late-load still running after 30 seconds");
        return;
    }
    if (cleanup_runtime(1, 0, 1) != 0) {
        reply_err(verb, "runtime cleanup failed; soft reboot cancelled, see steps.log");
        return;
    }
    /* Status means cleanup completed and the reboot command is about to be
     * dispatched. A reboot can kill this process before it reports its exit. */
    step_log("soft-reboot: cleanup completed, dispatching %s", binary);
    int log_fd = open(DAEMON_STEP_LOG, O_WRONLY | O_CLOEXEC);
    if (log_fd < 0) {
        reply_err(verb, "cannot open cleanup log; soft reboot cancelled");
        return;
    }
    int synced = fsync(log_fd);
    int sync_error = errno;
    close(log_fd);
    if (synced != 0) {
        step_log("soft-reboot cancelled: log fsync failed: %s", strerror(sync_error));
        reply_err(verb, "cannot sync cleanup log; soft reboot cancelled");
        return;
    }
    reply_body("Daemon cleanup completed; dispatching soft reboot.\n");
    reply_ok(verb);
    if (g_reply_fd >= 0) { close(g_reply_fd); g_reply_fd = -1; }
    int rc = g.use_apd
        ? run_ctx(NULL, (char *[]){ APD, "soft-reboot", NULL })
        : run_ctx("u:r:ksu:s0", (char *[]){ KSUD, "soft-reboot", NULL });
    step_log("soft-reboot exit=%d; daemon exiting", rc);
}

static void append_plan_log(const char *data, size_t n)
{
    if (!data || !n)
        return;
    int fd = open(PLAN_LOG, O_CREAT | O_WRONLY | O_APPEND | O_CLOEXEC, 0600);
    if (fd < 0)
        return;
    app_chown(PLAN_LOG);
    ssize_t w = write(fd, data, n);
    (void)w;
    close(fd);
}

static void plan_status(const char *id, const char *state, int exitcode, const char *line)
{
    char tmp[sizeof(PLAN_STATUS) + 8];
    snprintf(tmp, sizeof(tmp), "%s.tmp", PLAN_STATUS);
    int fd = open(tmp, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
    if (fd < 0)
        return;
    char body[1024];
    int n = snprintf(body, sizeof(body), "id=%s\nstate=%s\nexit=%d\nline=%s\n",
                     id ? id : "", state ? state : "", exitcode, line ? line : "");
    if (n > 0) {
        ssize_t w = write(fd, body, (size_t)n);
        (void)w;
    }
    fsync(fd);
    close(fd);
    app_chown(tmp);
    rename(tmp, PLAN_STATUS);
    app_chown(PLAN_STATUS);
    if (state && !strcmp(state, "done"))
        mark("plan-done");
    if (state && !strcmp(state, "accepted")) {
        mark("plan-accepted");
        mark("plan-done");
    }
    if (state && !strcmp(state, "fail"))
        mark("plan-failed");
}

static int valid_plan_id(const char *id)
{
    size_t n = 0;
    if (!id || !*id)
        return 0;
    for (const char *p = id; *p; p++, n++) {
        int ok = (*p >= 'A' && *p <= 'Z') || (*p >= 'a' && *p <= 'z') ||
                 (*p >= '0' && *p <= '9') || *p == '_' || *p == '-';
        if (!ok)
            return 0;
    }
    return n <= 80;
}

static int load_manager_file(const char *id)
{
    if (!valid_plan_id(id))
        return -1;
    char path[512];
    snprintf(path, sizeof(path), "%s/%s.manager", PLAN_DIR, id);
    char *buf = slurp(path, 4096);
    if (!buf)
        return -1;
    g.su_manager[0] = g.su_backend[0] = g.kmi[0] = g_stage_buf[0] = '\0';
    g.soft_reboot = 0;
    g.use_apd = 0;
    for (char *line = buf; *line; ) {
        char *nl = strchr(line, '\n');
        if (nl)
            *nl = '\0';
        char *eq = strchr(line, '=');
        if (eq) {
            *eq = '\0';
            const char *val = eq + 1;
            if (!strcmp(line, "package"))
                snprintf(g.su_manager, sizeof(g.su_manager), "%s", val);
            else if (!strcmp(line, "backend"))
                snprintf(g.su_backend, sizeof(g.su_backend), "%s", val);
            else if (!strcmp(line, "library"))
                snprintf(g_stage_buf, sizeof(g_stage_buf), "%s", val);
            else if (!strcmp(line, "kmi"))
                snprintf(g.kmi, sizeof(g.kmi), "%s", val);
            else if (!strcmp(line, "soft_reboot"))
                g.soft_reboot = !strcmp(val, "1") || !strcmp(val, "true");
        }
        if (!nl)
            break;
        line = nl + 1;
    }
    free(buf);
    g.use_apd = !strcmp(g.su_backend, "apd");
    g_tool = g.use_apd ? "apd" : "ksud";
    g_stage = g_stage_buf[0] ? g_stage_buf : (g.use_apd ? APD : KSUD);
    if (!g.su_manager[0] || access(g_stage, X_OK) != 0) {
        snprintf(g.fail_why, sizeof(g.fail_why), "late-load sidecar %s is not usable", path);
        return -1;
    }
    g.prefs_ok = 1;
    g.fail_why[0] = '\0';
    return 0;
}

static int run_shell_log(const char *cmd)
{
    int pipefd[2];
    if (pipe(pipefd) != 0)
        return -1;
    pid_t pid = fork();
    if (pid < 0) {
        close(pipefd[0]);
        close(pipefd[1]);
        return -1;
    }
    if (pid == 0) {
        close(pipefd[0]);
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[1]);
        execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
        _exit(127);
    }
    close(pipefd[1]);
    char chunk[DFCTL_CHUNK];
    ssize_t n;
    while ((n = read(pipefd[0], chunk, sizeof(chunk))) > 0)
        append_plan_log(chunk, (size_t)n);
    close(pipefd[0]);
    int status;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR)
        ;
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

static int run_plan_line(char *line)
{
    char *verb = line;
    char *arg = strchr(line, ' ');
    if (arg) {
        *arg = '\0';
        arg++;
        while (*arg == ' ')
            arg++;
    }
    if (!arg || !*arg)
        return -1;

    char *id = arg;
    char *rest = strchr(arg, ' ');
    if (rest) {
        *rest = '\0';
        rest++;
        while (*rest == ' ')
            rest++;
    }
    if (!valid_plan_id(id))
        return -1;

    if (!strcmp(verb, "exec")) {
        size_t prefix = strlen(PLAN_DIR);
        if (!rest || rest[0] != '/' || strchr(rest, ' ') ||
            strncmp(rest, PLAN_DIR, prefix) != 0 || rest[prefix] != '/')
            return -1;
        plan_status(id, "running", -1, rest);
        step_log("plan exec id=%s path=%s", id, rest);
        char cmd[600];
        snprintf(cmd, sizeof(cmd), "/system/bin/sh %s", rest);
        int rc = run_shell_log(cmd);
        step_log("plan exec id=%s exit=%d", id, rc);
        if (rc != 0) {
            char msg[64];
            snprintf(msg, sizeof(msg), "exec exit=%d (continuing)", rc);
            plan_status(id, "ok", rc, msg);
            return 0;
        }
        plan_status(id, "ok", 0, "exec exit=0");
        return 0;
    }

    if (!strcmp(verb, "lateload")) {
        int reboot = rest && !strcmp(rest, "soft-reboot");
        if (rest && *rest && !reboot)
            return -1;
        plan_status(id, "running", -1, reboot ? "lateload soft-reboot" : "lateload");
        if (load_manager_file(id) != 0 && read_prefs() != 0) {
            plan_status(id, "fail", 1, g.fail_why);
            return 1;
        }
        if (reboot)
            g.soft_reboot = 1;
        step_log("plan lateload id=%s stage=%s manager=%s reboot=%d",
                 id, g_stage, g.su_manager, reboot);
        if (reboot) {
            if (cleanup_runtime(1, 0, 1) != 0) {
                plan_status(id, "fail", 1, "runtime cleanup failed");
                return 1;
            }
            plan_status(id, "accepted", 0,
                        "cleanup completed before late-load; late-load and soft reboot continue");
        }
        struct buf b = { calloc(1, 1 << 16), 0, 1 << 16 };
        if (!b.p) {
            plan_status(id, "fail", 1, "out of memory");
            return 1;
        }
        int rc = do_late_load(&b);
        if (b.p) {
            append_plan_log(b.p, b.len);
            free(b.p);
        }
        step_log("plan lateload id=%s exit=%d", id, rc);
        if (reboot) {
            if (rc == 0) {
                step_log("late-load succeeded; waiting 4 seconds before soft reboot");
                sleep(4);
                request_soft_reboot("late-load", 1);
            } else {
                step_log("soft-reboot cancelled: late-load failed exit=%d", rc);
                plan_status(id, "fail", rc, "late-load failed");
            }
            return rc;
        }
        if (rc != 0) {
            char msg[64];
            snprintf(msg, sizeof(msg), "late-load exit=%d", rc);
            plan_status(id, "fail", rc, msg);
            return rc;
        }
        plan_status(id, "ok", 0, "late-load exit=0");
        return 0;
    }

    if (!strcmp(verb, "soft-reboot")) {
        if (rest && *rest)
            return -1;
        plan_status(id, "running", -1, "soft-reboot");
        if (load_manager_file(id) != 0)
            read_prefs();
        plan_status(id, "accepted", 0, "cleanup then soft reboot");
        request_soft_reboot("soft-reboot", 0);
        return g.fail_why[0] ? 1 : 0;
    }
    return -1;
}

static int run_plan(void)
{
    if (g_in_plan)
        return 1;
    if (access(PLAN_FILE, F_OK) != 0)
        return 0;
    ensure_dir(PLAN_DIR, 0700);
    if (rename(PLAN_FILE, PLAN_RUNNING) != 0) {
        if (errno == ENOENT)
            return 0;
        step_log("plan: rename failed: %s", strerror(errno));
        return 1;
    }
    g_in_plan = 1;
    step_log("plan: starting %s", PLAN_RUNNING);
    append_plan_log("=== plan start ===\n", strlen("=== plan start ===\n"));
    char *text = slurp(PLAN_RUNNING, 65535);
    int rc = 0;
    if (!text) {
        plan_status("", "fail", 1, "cannot read plan");
        rc = 1;
    } else {
        char *p = text;
        while (rc == 0 && p && *p) {
            char *nl = strchr(p, '\n');
            if (nl)
                *nl = '\0';
            while (*p == ' ' || *p == '\t')
                p++;
            size_t n = strlen(p);
            while (n && (p[n - 1] == '\r' || p[n - 1] == ' '))
                p[--n] = '\0';
            if (*p && *p != '#') {
                char copy[DFCTL_LINE_MAX];
                snprintf(copy, sizeof(copy), "%s", p);
                rc = run_plan_line(copy);
            }
            if (!nl)
                break;
            p = nl + 1;
        }
        free(text);
    }
    if (rc == 0 && !g_stop) {
        plan_status("", "done", 0, "plan done");
        step_log("plan: done");
        unlink(PLAN_RUNNING);
    } else if (rc != 0) {
        step_log("plan: failed rc=%d", rc);
    }
    g_in_plan = 0;
    return rc;
}

static void on_signal(int sig)
{
    (void)sig;
    g_stop = 1;
}

/* chown to the app uid: root-owned 0700/0600 is unreachable, 0666 would
 * expose a root channel to every app. The uid comes from the app's own data
 * directory, never from the client.
 */
static int setup_transport(void)
{
    if (g_app_uid == (uid_t)-1 || g_app_gid == (gid_t)-1) {
        snprintf(g_owner_note, sizeof(g_owner_note), "app uid/gid unresolved: stat(%s) failed", APP_DATA_DIR);
        return -1;
    }
    mkdir(DAEMON_DIR, 0700);
    uid_t uid = g_app_uid;
    gid_t gid = g_app_gid;
    if (uid == (uid_t)-1) {
        snprintf(g_owner_note, sizeof(g_owner_note),
                 "app uid unresolved: stat(%s) failed (%s) - owner left as root",
                 APP_DATA_DIR, strerror(errno));
    } else if (chown(DAEMON_DIR, uid, gid) != 0) {
        snprintf(g_owner_note, sizeof(g_owner_note),
                 "chown(%s, %d) failed: %s - the app cannot reach the channel",
                 DAEMON_DIR, (int)uid, strerror(errno));
    } else {
        snprintf(g_owner_note, sizeof(g_owner_note),
                 "owned by app uid %d", (int)uid);
    }
    chmod(DAEMON_DIR, 0700);

    unlink(DAEMON_FIFO_IN);
    unlink(DAEMON_FIFO_OUT);
    if (mkfifo(DAEMON_FIFO_IN, 0600) != 0 || mkfifo(DAEMON_FIFO_OUT, 0600) != 0) {
        snprintf(g_owner_note, sizeof(g_owner_note),
                 "mkfifo failed: %s", strerror(errno));
        return -1;
    }

    if (uid != (uid_t)-1) {
        if (chown(DAEMON_FIFO_IN, uid, gid) != 0 || chown(DAEMON_FIFO_OUT, uid, gid) != 0) {
            snprintf(g_owner_note, sizeof(g_owner_note),
                     "chown(fifo) failed: %s - the app cannot reach the channel",
                     strerror(errno));
        } else {
            snprintf(g_owner_note, sizeof(g_owner_note),
                     "owned by app uid %d", (int)uid);
        }
    }
    chmod(DAEMON_FIFO_IN, 0600);
    chmod(DAEMON_FIFO_OUT, 0600);

    /* O_RDWR on both ends means the daemon is always its own writer, so the
     * pipe never reports EOF when the app disconnects or restarts. */
    g_listen_fd = open(DAEMON_FIFO_IN, O_RDWR | O_NONBLOCK | O_CLOEXEC);
    g_reply_fd  = open(DAEMON_FIFO_OUT, O_RDWR | O_CLOEXEC);
    if (g_listen_fd < 0 || g_reply_fd < 0) {
        snprintf(g_owner_note, sizeof(g_owner_note),
                 "open(fifo) failed: %s", strerror(errno));
        if (g_listen_fd >= 0) { close(g_listen_fd); g_listen_fd = -1; }
        if (g_reply_fd >= 0) { close(g_reply_fd); g_reply_fd = -1; }
        unlink(DAEMON_FIFO_IN);
        unlink(DAEMON_FIFO_OUT);
        return -1;
    }
    return 0;
}

/* Polls the read fifo and hands complete lines to handle_line. */
static void serve_forever(void)
{
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);

    touch(DAEMON_READY);
    mark("daemon-ready");
    step_log("daemon ready, serving on the control channel");

    char line[DFCTL_LINE_MAX];
    size_t used = 0;

    while (!g_stop) {
        if (stop_if_enforcing())
            break;
        if (access(PLAN_FILE, F_OK) == 0)
            run_plan();
        if (g_stop)
            break;
        ssize_t n = read(g_listen_fd, line + used, sizeof(line) - used - 1);
        if (n < 0) {
            if (errno == EAGAIN || errno == EINTR) {
                struct timespec ts = { 0, 100 * 1000 * 1000 };
                nanosleep(&ts, NULL);
                continue;
            }
            break;
        }
        if (n == 0) {
            /* The daemon holds the write end open too, so this means no data
             * rather than EOF. */
            struct timespec ts = { 0, 100 * 1000 * 1000 };
            nanosleep(&ts, NULL);
            continue;
        }
        used += (size_t)n;
        line[used] = '\0';
        char *start = line;
        char *nl;
        while (!g_stop && (nl = strchr(start, '\n')) != NULL) {
                *nl = '\0';
            handle_line(start);
            start = nl + 1;
        }
        used = strlen(start);
        memmove(line, start, used + 1);
        if (used + 1 >= sizeof(line))
            used = 0;   /* oversized line: drop it */
    }

    if (g_listen_fd >= 0) close(g_listen_fd);
    if (g_reply_fd >= 0) close(g_reply_fd);
    unlink(DAEMON_READY);
}

/* ------------------------------------------------------------------- main */

int main(void)
{
    memset(&g, 0, sizeof(g));
    g.step = DFCTL_STEP_START;
    try_become_init();

    /* Learn who owns the app's data directory before creating anything in it,
     * so every file this daemon writes is readable by the app. */
    resolve_app_owner();
    ensure_dir(DAEMON_DIR, 0700);
    ensure_dir(MARKER_DIR, 0700);
    step_log("daemon start pid=%d uid=%d", (int)getpid(), (int)getuid());

    /* Open the control channel first: if this fails there is no point doing
     * anything else, because nothing could be reported. */
    if (setup_transport() != 0) {
        mark("err-transport");
        step_log("FAILED setup_transport: %s", g_owner_note);
        return 1;
    }
    mark("reached-transport");
    step_log("transport ready: %s", g_owner_note);

    /* Record the pid so the app can tell a live daemon from a stale socket. */
    char pidbuf[32];
    snprintf(pidbuf, sizeof(pidbuf), "%d\n", (int)getpid());
    int pf = open(DAEMON_PID, O_CREAT | O_TRUNC | O_WRONLY, 0600);
    if (pf >= 0) { ssize_t w = write(pf, pidbuf, strlen(pidbuf)); (void)w; close(pf); app_chown(DAEMON_PID); }

    /* Legacy manager configuration is optional. A missing selection or staged
     * backend must not prevent environment adoption or daemon readiness. */
    g.step = DFCTL_STEP_PREFS;
    int prefs_rc = read_prefs();
    mark("reached-prefs");
    if (prefs_rc != 0) {
        mark("warn-prefs");
        step_log("prefs not usable (non-fatal): %s", g.fail_why);
    } else {
        step_log("prefs ok: manager=%s backend=%s stage=%s", g.su_manager, g.su_backend, g_stage);
    }

    g.step = DFCTL_STEP_ZYGOTE;
    g.zygote_ok = (adopt_zygote_env() == 0);
    if (!g.zygote_ok) mark("warn-zygote-env");
    mark("reached-zygote-env");
    step_log("zygote env: %s", g.zygote_ok ? "adopted" : "FAILED (non-fatal)");

    /* Partition protection and module disabling are explicit optional actions,
     * not daemon initialization. Leave their status unset until requested. */

    /* The ready marker is emitted after DAEMON_READY in serve_forever(), once
     * the transport is open and the daemon can actually answer. */
    g.step = DFCTL_STEP_READY;

    /* Leave an on-disk record of how we got here, so a pull of the app's data
     * dir is enough to explain the run even without connecting. */
    int lf = open(DAEMON_LOG, O_CREAT | O_TRUNC | O_WRONLY, 0600);
    if (lf >= 0) {
        char banner[512];
        int n = snprintf(banner, sizeof(banner),
                         "step: %s\npid: %d\nuid: %d\nsu_manager: %s\n"
                         "su_backend: %s\nkmi: %s\nlate-load binary: %s\n"
                         "zygote_env: %d\npartitions_ro: %d\nmodules_disabled: %d\n"
                         "transport: fifo\nowner: %s\n",
                         dfctl_step_name(g.step), (int)getpid(), (int)getuid(),
                         g.su_manager, g.su_backend[0] ? g.su_backend : "(default)",
                         g.kmi[0] ? g.kmi : "(autodetect)",
                         g_stage ? g_stage : "(unset)",
                         g.zygote_ok, g.partitions_ro_ok, g.modules_disabled_ok,
                         g_owner_note[0] ? g_owner_note : "(not attempted)");
        if (n > 0) { ssize_t w = write(lf, banner, (size_t)n); (void)w; }
        close(lf);
        app_chown(DAEMON_LOG);
    }

    /* Mark ready before the handed-off plan so the exploit waiter can return
     * without sitting on the FIFO. Remaining steps run from PLAN_FILE. */
    touch(DAEMON_READY);
    mark("daemon-ready");
    step_log("daemon ready; running staged module plan if present");
    run_plan();
    if (!g_stop && !stop_if_enforcing())
        serve_forever();
    return 0;
}
