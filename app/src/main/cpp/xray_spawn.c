/*
 * Native spawn for the Xray core.
 *
 * Why native: Android's ProcessBuilder closes every fd >= 3 across exec
 * (measured on-device by DisruptorProxy), so a core started from Kotlin can
 * never receive the VpnService tun fd. A native fork/exec inherits every fd
 * without FD_CLOEXEC, and dup() returns a copy with that flag cleared.
 *
 * The fd is handed over through the environment variable this exact build
 * reads: "xray.tun.fd" (Xray-core proxy/tun/tun_android.go:
 * strconv.Atoi(platform.NewEnvFlag(platform.TunFdKey))). The variable name is
 * not cosmetic — 26.11.2026 the spawner exported XRAY_TUN_FD, the core never
 * saw a fd, fell back to creating its own device and died in netlink with
 * "fail to get system interface information". Verified against the shipped
 * binary: only xray.tun.fd=<n> changes the start path (a bogus fd gives
 * "bad file descriptor"), while XRAY_TUN_FD=<n> is ignored. Keep this literal
 * in sync with TunFdContract.ENV_NAME — a JVM test enforces it.
 *
 * The parent never touches the child after fork except through signals;
 * the child redirects its stdout/stderr to a log file, so a failed start
 * leaves evidence on disk (logcat is not readable from the app itself).
 */

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

/* Environment variable the shipped Xray build reads the tun fd from. */
#define XRAY_TUN_FD_ENV "xray.tun.fd"

/* Lowest descriptor handed to the core: 0/1/2 belong to the redirected log. */
#define TUN_FD_MIN 3

static int last_errno = 0;

/*
 * Write a one-line evidence record into the child's log: own pid, the tun fd
 * number, its FD_CLOEXEC state and the inherited fd table. Written from the
 * child before exec, because after execv the core is in charge and nothing of
 * ours runs any more. Costs a few syscalls on a path that never repeats.
 */
static void log_child_evidence(int tun_fd) {
    int flags = tun_fd >= 0 ? fcntl(tun_fd, F_GETFD) : -1;
    char fds[128];
    size_t used = 0;
    fds[0] = '\0';
    DIR* dir = opendir("/proc/self/fd");
    if (dir != NULL) {
        struct dirent* entry;
        while ((entry = readdir(dir)) != NULL) {
            if (entry->d_name[0] == '.') continue;
            int written = snprintf(fds + used, sizeof(fds) - used, "%s%s",
                                   used == 0 ? "" : ",", entry->d_name);
            if (written <= 0 || (size_t) written >= sizeof(fds) - used) break;
            used += (size_t) written;
        }
        closedir(dir);
    }
    dprintf(STDERR_FILENO,
            "[maestro-spawn] pid=%d tun_fd=%d cloexec=%d env=%s fds=%s\n",
            (int) getpid(), tun_fd, (flags < 0 ? -1 : (flags & FD_CLOEXEC)),
            XRAY_TUN_FD_ENV, fds);
}

static jstring new_error_string(JNIEnv* env, const char* what) {
    char buf[256];
    snprintf(buf, sizeof(buf), "%s: %s", what, strerror(last_errno));
    return (*env)->NewStringUTF(env, buf);
}

/*
 * Spawn the Xray binary as a child process.
 *
 * tunFd: raw tun fd from VpnService (ParcelFileDescriptor.detachFd), or -1
 *   for proxy-only mode (no tun). The fd is dup()'d here — dup clears
 *   FD_CLOEXEC on the copy, so it survives exec — and the parent's copy is
 *   closed on return; the child owns its copy.
 * Returns the child pid, or -1 on error (see nativeLastError).
 */
JNIEXPORT jlong JNICALL
Java_com_maestrovpn_client_core_engine_XrayNative_nativeSpawn(
        JNIEnv* env, jobject thiz, jint tunFd,
        jstring binaryPath, jstring configPath, jstring logPath) {
    (void) thiz;
    const char* bin = (*env)->GetStringUTFChars(env, binaryPath, NULL);
    const char* cfg = (*env)->GetStringUTFChars(env, configPath, NULL);
    const char* log = logPath != NULL
            ? (*env)->GetStringUTFChars(env, logPath, NULL) : NULL;
    if (bin == NULL || cfg == NULL) {
        last_errno = EINVAL;
        if (bin != NULL) (*env)->ReleaseStringUTFChars(env, binaryPath, bin);
        if (cfg != NULL) (*env)->ReleaseStringUTFChars(env, configPath, cfg);
        if (log != NULL) (*env)->ReleaseStringUTFChars(env, logPath, log);
        return -1;
    }

    int childFd = -1;
    if (tunFd >= 0) {
        childFd = dup(tunFd);
        if (childFd < 0) {
            last_errno = errno;
            (*env)->ReleaseStringUTFChars(env, binaryPath, bin);
            (*env)->ReleaseStringUTFChars(env, configPath, cfg);
            if (log != NULL) (*env)->ReleaseStringUTFChars(env, logPath, log);
            return -1;
        }
    }

    pid_t pid = fork();
    if (pid < 0) {
        last_errno = errno;
        if (childFd >= 0) close(childFd);
        (*env)->ReleaseStringUTFChars(env, binaryPath, bin);
        (*env)->ReleaseStringUTFChars(env, configPath, cfg);
        if (log != NULL) (*env)->ReleaseStringUTFChars(env, logPath, log);
        return -1;
    }

    if (pid == 0) {
        /* Child: never returns. Die with the parent thread that spawned us:
         * if the app process is killed, the tun fd dies with it and an
         * orphaned core would keep running with a dead tunnel.
         * nativeSpawn must be called from a thread that outlives the child
         * (the service's monitor thread, which blocks in waitpid). */
        if (getppid() == 1) _exit(127);
        prctl(PR_SET_PDEATHSIG, SIGTERM);
        if (getppid() == 1) _exit(127);
        /* Redirect stdout/stderr to the log file (best effort). */
        if (log != NULL) {
            int fd = open(log, O_WRONLY | O_CREAT | O_APPEND, 0600);
            if (fd >= 0) {
                dup2(fd, STDOUT_FILENO);
                dup2(fd, STDERR_FILENO);
                if (fd > STDERR_FILENO) close(fd);
            }
        }
        int tunFd = -1;
        if (childFd >= 0) {
            /* Move the fd to a low, known descriptor >= TUN_FD_MIN. F_DUPFD
             * returns the lowest free fd >= arg and its copy never carries
             * FD_CLOEXEC (POSIX), so the number survives exec. Doing it after
             * the log redirection keeps 0/1/2 out of the way. */
            tunFd = fcntl(childFd, F_DUPFD, TUN_FD_MIN);
            if (tunFd >= 0 && tunFd != childFd) close(childFd);
            else if (tunFd < 0) tunFd = childFd;
            /* Explicit for the case F_DUPFD was unavailable: the fd must
             * survive exec, otherwise the core sees a closed descriptor. */
            (void) fcntl(tunFd, F_SETFD, 0);
            char value[16];
            snprintf(value, sizeof(value), "%d", tunFd);
            setenv(XRAY_TUN_FD_ENV, value, 1);
        } else {
            /* Proxy-only run: no stale fd number may reach the core. */
            unsetenv(XRAY_TUN_FD_ENV);
        }
        log_child_evidence(tunFd);
        char* argv[] = {(char*) bin, "run", "-c", (char*) cfg, NULL};
        execv(bin, argv);
        _exit(127);
    }

    /* Parent: the dup'd fd belongs to the child now. */
    if (childFd >= 0) close(childFd);
    (*env)->ReleaseStringUTFChars(env, binaryPath, bin);
    (*env)->ReleaseStringUTFChars(env, configPath, cfg);
    if (log != NULL) (*env)->ReleaseStringUTFChars(env, logPath, log);
    return (jlong) pid;
}

JNIEXPORT jint JNICALL
Java_com_maestrovpn_client_core_engine_XrayNative_nativeSignal(
        JNIEnv* env, jobject thiz, jlong pid, jint sig) {
    (void) env;
    (void) thiz;
    if (kill((pid_t) pid, sig) != 0) {
        last_errno = errno;
        return -1;
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_maestrovpn_client_core_engine_XrayNative_nativeIsAlive(
        JNIEnv* env, jobject thiz, jlong pid) {
    (void) env;
    (void) thiz;
    return kill((pid_t) pid, 0) == 0 ? JNI_TRUE : JNI_FALSE;
}

/*
 * Blocking wait for the child. Returns the exit code, or -1 if the child
 * was killed by a signal (then nativeLastError carries the signal number
 * as text) or waitpid failed. Call from a background thread.
 */
JNIEXPORT jint JNICALL
Java_com_maestrovpn_client_core_engine_XrayNative_nativeWaitpid(
        JNIEnv* env, jobject thiz, jlong pid) {
    (void) env;
    (void) thiz;
    int status = 0;
    pid_t r;
    do {
        r = waitpid((pid_t) pid, &status, 0);
    } while (r < 0 && errno == EINTR);
    if (r < 0) {
        last_errno = errno;
        return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) {
        last_errno = 0;
        return -100 - WTERMSIG(status);
    }
    return -1;
}

JNIEXPORT jstring JNICALL
Java_com_maestrovpn_client_core_engine_XrayNative_nativeLastError(
        JNIEnv* env, jobject thiz) {
    (void) thiz;
    if (last_errno == 0) return (*env)->NewStringUTF(env, "unknown error");
    return new_error_string(env, "spawn");
}
