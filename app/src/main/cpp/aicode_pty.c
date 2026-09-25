// CP-32 managed PTY runtime (§27: own runtime, no Termux dependency).
// Minimal forkpty-based interactive shell for arm64. Handles are small
// ints (slot+1); 0 always means invalid. All errors return negative errno
// (Java maps them to honest messages); read() returns null on timeout.
#include <jni.h>

#include <errno.h>
#include <poll.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#define MAX_SESSIONS 16

static int g_fds[MAX_SESSIONS];
static pid_t g_pids[MAX_SESSIONS];
static int g_inited = 0;

static void ensure_init(void) {
    if (g_inited) return;
    for (int i = 0; i < MAX_SESSIONS; i++) {
        g_fds[i] = -1;
        g_pids[i] = -1;
    }
    g_inited = 1;
}

static int slot_for(jint handle) {
    int slot = handle - 1;
    if (slot < 0 || slot >= MAX_SESSIONS) return -1;
    if (g_fds[slot] < 0) return -1;
    return slot;
}

JNIEXPORT jint JNICALL
Java_com_aicodemax_tools_terminal_PtyJni_ptyOpen(JNIEnv *env, jobject thiz, jstring shellPath,
                                                 jint cols, jint rows) {
    (void)thiz;
    ensure_init();
    const char *shell = (*env)->GetStringUTFChars(env, shellPath, NULL);
    if (!shell) return -ENOMEM;

    int slot = -1;
    for (int i = 0; i < MAX_SESSIONS; i++) {
        if (g_fds[i] < 0) {
            slot = i;
            break;
        }
    }
    if (slot < 0) {
        (*env)->ReleaseStringUTFChars(env, shellPath, shell);
        return -EMFILE;
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = cols > 0 ? cols : 80;
    ws.ws_row = rows > 0 ? rows : 24;

    pid_t pid = forkpty(&g_fds[slot], NULL, NULL, &ws);
    if (pid < 0) {
        int err = errno;
        g_fds[slot] = -1;
        (*env)->ReleaseStringUTFChars(env, shellPath, shell);
        return -err;
    }
    if (pid == 0) {
        // Child: interactive shell with a sane terminal type.
        setenv("TERM", "xterm-256color", 1);
        execl(shell, shell, "-i", (char *)NULL);
        _exit(127);
    }
    g_pids[slot] = pid;
    (*env)->ReleaseStringUTFChars(env, shellPath, shell);
    return slot + 1;
}

JNIEXPORT jbyteArray JNICALL
Java_com_aicodemax_tools_terminal_PtyJni_ptyRead(JNIEnv *env, jobject thiz, jint handle,
                                                 jint maxBytes, jint timeoutMs) {
    (void)thiz;
    ensure_init();
    int slot = slot_for(handle);
    if (slot < 0) return NULL;
    if (maxBytes <= 0 || maxBytes > 65536) maxBytes = 4096;

    struct pollfd pfd;
    pfd.fd = g_fds[slot];
    pfd.events = POLLIN | POLLHUP | POLLERR;
    int pr = poll(&pfd, 1, timeoutMs < 0 ? 0 : timeoutMs);
    if (pr < 0) return NULL;  // treat poll errors as timeout; next read retries
    if (pr == 0) return NULL; // timeout: no data yet

    unsigned char *buf = (unsigned char *)malloc(maxBytes);
    if (!buf) return NULL;
    ssize_t n = read(g_fds[slot], buf, maxBytes);
    if (n < 0) {
        free(buf);
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, n); // n==0 means EOF: empty array
    if (out && n > 0) (*env)->SetByteArrayRegion(env, out, 0, n, (jbyte *)buf);
    free(buf);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_aicodemax_tools_terminal_PtyJni_ptyWrite(JNIEnv *env, jobject thiz, jint handle,
                                                  jbyteArray data) {
    (void)thiz;
    ensure_init();
    int slot = slot_for(handle);
    if (slot < 0) return -EBADF;
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return -ENOMEM;
    ssize_t total = 0;
    while (total < len) {
        ssize_t n = write(g_fds[slot], bytes + total, len - total);
        if (n < 0) {
            if (errno == EINTR) continue;
            int err = errno;
            (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
            return -err;
        }
        total += n;
    }
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return (jint)total;
}

JNIEXPORT jint JNICALL
Java_com_aicodemax_tools_terminal_PtyJni_ptyResize(JNIEnv *env, jobject thiz, jint handle, jint cols,
                                                   jint rows) {
    (void)thiz;
    ensure_init();
    int slot = slot_for(handle);
    if (slot < 0) return -EBADF;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = cols > 0 ? cols : 80;
    ws.ws_row = rows > 0 ? rows : 24;
    if (ioctl(g_fds[slot], TIOCSWINSZ, &ws) < 0) return -errno;
    return 0;
}

JNIEXPORT void JNICALL
Java_com_aicodemax_tools_terminal_PtyJni_ptyClose(JNIEnv *env, jobject thiz, jint handle) {
    (void)env;
    (void)thiz;
    ensure_init();
    int slot = slot_for(handle);
    if (slot < 0) return;
    int fd = g_fds[slot];
    pid_t pid = g_pids[slot];
    g_fds[slot] = -1;
    g_pids[slot] = -1;
    close(fd);
    if (pid > 0) {
        kill(pid, SIGHUP);
        waitpid(pid, NULL, WNOHANG);
    }
}
