/*
 * bun-pty shim for Android.
 *
 * bun-pty (https://github.com/sursaone/bun-pty) embeds native libraries for
 * desktop Linux/macOS/Windows, but not Android. OpenCode's web terminal calls
 * bun-pty through Bun FFI, so without a loadable library POST /api/pty fails
 * with "undefined is not an object (evaluating 'i.symbols')".
 *
 * This file implements the exact bun_pty_* ABI used by bun-pty 0.4.x on top of
 * bionic's forkpty(3). It is built for arm64-v8a and shipped as
 * libbunpty_android.so. LocalRuntime points BUN_PTY_LIB at it.
 */

#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define MAX_PTYS 64
#define CHILD_EXITED (-2)
#define ERROR (-1)
#define SUCCESS 0

/*
 * Debug aid: BUN_PTY_DEBUG=1 appends spawn details and exec failures to
 * <cwd>/.bunpty.log. Only the debug build sets it.
 */
#include <stdarg.h>

static void bunpty_log(const char *dir, const char *fmt, ...) {
    if (!dir || !*dir || !getenv("BUN_PTY_DEBUG")) return;
    char path[512];
    if (snprintf(path, sizeof(path), "%s/.bunpty.log", dir) >= (int)sizeof(path)) return;
    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0600);
    if (fd < 0) return;
    char msg[1024];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    if (n > 0) write(fd, msg, n < (int)sizeof(msg) ? (size_t)n : sizeof(msg) - 1);
    write(fd, "\n", 1);
    close(fd);
}

/*
 * We compile with the NDK's bionic headers but load into OpenCode's musl
 * process. bionic's <errno.h> references __errno(); musl exports
 * __errno_location() instead. Provide the thin bridge so the library has no
 * unresolved bionic-only symbols.
 */
#ifndef BUNPTY_BIONIC
extern int *__errno_location(void);
int *__errno(void) { return __errno_location(); }
#endif

typedef struct {
    int used;
    int fd;
    pid_t pid;
    volatile int exited;
    volatile int exit_code;
} BunPty;

static BunPty g_ptys[MAX_PTYS];
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static BunPty *bunpty_get(int handle) {
    if (handle <= 0 || handle >= MAX_PTYS) return NULL;
    BunPty *p = &g_ptys[handle];
    return p->used ? p : NULL;
}

static void bunpty_mark_exited(BunPty *p) {
    if (p->exited) return;
    p->exited = 1;
    int status = 0;
    pid_t r;
    do {
        r = waitpid(p->pid, &status, 0);
    } while (r < 0 && errno == EINTR);
    if (r == p->pid) {
        if (WIFEXITED(status)) p->exit_code = WEXITSTATUS(status);
        else if (WIFSIGNALED(status)) p->exit_code = 128 + WTERMSIG(status);
    } else {
        p->exit_code = 0;
    }
}

/* ---- POSIX shell word splitting (matches shell-words for our inputs) ---- */

typedef struct {
    char **items;
    int count;
    int capacity;
} WordList;

static int word_push(WordList *w, char *s) {
    /* Keep one extra slot: execve needs argv/envp terminated by NULL. */
    if (w->count + 1 >= w->capacity) {
        int cap = w->capacity ? w->capacity * 2 : 8;
        char **next = realloc(w->items, (size_t)cap * sizeof(char *));
        if (!next) return 0;
        w->items = next;
        w->capacity = cap;
    }
    w->items[w->count++] = s;
    w->items[w->count] = NULL;
    return 1;
}

static int parse_words(const char *src, WordList *out) {
    size_t cap = strlen(src) + 1;
    char *buf = malloc(cap);
    if (!buf) return 0;
    size_t n = 0;
    int in_word = 0;
    int quote = 0; /* 0 none, '\'' single, '"' double */
    int escaped = 0;

    for (const char *s = src; *s; s++) {
        char c = *s;
        if (escaped) {
            buf[n++] = c;
            in_word = 1;
            escaped = 0;
            continue;
        }
        if (quote == '\'') {
            if (c == '\'') quote = 0;
            else buf[n++] = c;
            continue;
        }
        if (quote == '"') {
            if (c == '\\') {
                char nx = s[1];
                if (nx == '"' || nx == '\\' || nx == '$' || nx == '`' || nx == '\n') {
                    escaped = 1;
                } else {
                    buf[n++] = c;
                }
            } else if (c == '"') {
                quote = 0;
            } else {
                buf[n++] = c;
            }
            continue;
        }
        if (c == '\\') {
            in_word = 1;
            escaped = 1;
        } else if (c == '\'') {
            in_word = 1;
            quote = '\'';
        } else if (c == '"') {
            in_word = 1;
            quote = '"';
        } else if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
            if (in_word) {
                buf[n] = 0;
                char *word = strdup(buf);
                if (!word || !word_push(out, word)) {
                    free(word);
                    free(buf);
                    return 0;
                }
                n = 0;
                in_word = 0;
            }
        } else {
            buf[n++] = c;
            in_word = 1;
        }
    }
    if (escaped) buf[n++] = '\\';
    if (quote != 0 || escaped) {
        free(buf);
        return 0;
    }
    if (in_word) {
        buf[n] = 0;
        char *word = strdup(buf);
        if (!word || !word_push(out, word)) {
            free(word);
            free(buf);
            return 0;
        }
    }
    free(buf);
    return 1;
}

static void words_free(WordList *w) {
    for (int i = 0; i < w->count; i++) free(w->items[i]);
    free(w->items);
    w->items = NULL;
    w->count = w->capacity = 0;
}

/* ---- env string: "K=V\0K2=V2\0...\0" ---- */

static char **parse_env(const char *src) {
    int cap = 8;
    int count = 0;
    char **envp = malloc((size_t)cap * sizeof(char *));
    if (!envp) return NULL;
    if (!src) {
        envp[0] = NULL;
        return envp;
    }
    const char *p = src;
    while (*p) {
        size_t len = strlen(p);
        if (len == 0) break;
        char *entry = strdup(p);
        if (!entry) break;
        if (!memchr(entry, '=', len)) {
            free(entry);
            p += len + 1;
            continue;
        }
        if (count + 1 >= cap) {
            cap *= 2;
            char **next = realloc(envp, (size_t)cap * sizeof(char *));
            if (!next) {
                free(entry);
                break;
            }
            envp = next;
        }
        envp[count++] = entry;
        p += len + 1;
    }
    envp[count] = NULL;
    return envp;
}

static void env_free(char **envp) {
    if (!envp) return;
    for (int i = 0; envp[i]; i++) free(envp[i]);
    free(envp);
}

/* ---- FFI ---- */

int bun_pty_spawn(const char *cmd, const char *cwd, const char *env,
                  int cols, int rows) {
    if (!cmd || !cwd || cols <= 0 || rows <= 0) return ERROR;
    bunpty_log(cwd, "spawn cmd=[%s] cols=%d rows=%d env=[%.120s]", cmd, cols, rows, env ? env : "");

    WordList words = {0};
    if (!parse_words(cmd, &words) || words.count == 0) {
        words_free(&words);
        return ERROR;
    }
    char **envp = parse_env(env);

    int slot = -1;
    pthread_mutex_lock(&g_lock);
    for (int i = 1; i < MAX_PTYS; i++) {
        if (!g_ptys[i].used) {
            g_ptys[i].used = 1;
            g_ptys[i].exited = 0;
            g_ptys[i].exit_code = -1;
            g_ptys[i].fd = -1;
            g_ptys[i].pid = -1;
            slot = i;
            break;
        }
    }
    pthread_mutex_unlock(&g_lock);
    if (slot < 0) {
        words_free(&words);
        env_free(envp);
        return ERROR;
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)cols;
    ws.ws_row = (unsigned short)rows;

    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0) {
        pthread_mutex_lock(&g_lock);
        g_ptys[slot].used = 0;
        pthread_mutex_unlock(&g_lock);
        words_free(&words);
        env_free(envp);
        return ERROR;
    }

    if (pid == 0) {
        /* child */
        errno = 0;
        if (cwd && *cwd) chdir(cwd);
        int chdir_errno = errno;
        execve(words.items[0], words.items, envp ? envp : environ);
        int e = errno;
        bunpty_log(cwd, "exec failed file=[%s] chdir_errno=%d errno=%d", words.items[0], chdir_errno, e);
        _exit(127);
    }

    int flags = fcntl(master, F_GETFL, 0);
    if (flags >= 0) fcntl(master, F_SETFL, flags | O_NONBLOCK);
    bunpty_log(cwd, "forked pid=%d", (int)pid);

    pthread_mutex_lock(&g_lock);
    g_ptys[slot].fd = master;
    g_ptys[slot].pid = pid;
    pthread_mutex_unlock(&g_lock);

    words_free(&words);
    env_free(envp);
    return slot;
}

int bun_pty_write(int handle, const void *data, int len) {
    if (!data || len < 0) return ERROR;
    BunPty *p = bunpty_get(handle);
    if (!p) return ERROR;
    if (p->exited) return CHILD_EXITED;
    const char *buf = data;
    int left = len;
    while (left > 0) {
        ssize_t n = write(p->fd, buf, (size_t)left);
        if (n > 0) {
            buf += n;
            left -= (int)n;
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            usleep(1000);
            continue;
        }
        return ERROR;
    }
    return SUCCESS;
}

int bun_pty_read(int handle, void *buf, int len) {
    if (!buf || len <= 0) return ERROR;
    BunPty *p = bunpty_get(handle);
    if (!p) return ERROR;
    if (p->exited) return CHILD_EXITED;
    for (;;) {
        ssize_t n = read(p->fd, buf, (size_t)len);
        if (n > 0) return (int)n;
        if (n == 0) {
            bunpty_mark_exited(p);
            return CHILD_EXITED;
        }
        if (errno == EINTR) continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
        if (errno == EIO) {
            bunpty_mark_exited(p);
            return CHILD_EXITED;
        }
        return ERROR;
    }
}

int bun_pty_resize(int handle, int cols, int rows) {
    if (cols <= 0 || rows <= 0) return ERROR;
    BunPty *p = bunpty_get(handle);
    if (!p) return ERROR;
    if (p->exited) return CHILD_EXITED;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)cols;
    ws.ws_row = (unsigned short)rows;
    return ioctl(p->fd, TIOCSWINSZ, &ws) == 0 ? SUCCESS : ERROR;
}

int bun_pty_kill(int handle) {
    BunPty *p = bunpty_get(handle);
    if (!p) return ERROR;
    if (p->exited) return SUCCESS;
    if (kill(p->pid, SIGKILL) == 0) {
        int status = 0;
        pid_t r;
        do {
            r = waitpid(p->pid, &status, 0);
        } while (r < 0 && errno == EINTR);
        p->exited = 1;
        p->exit_code = 128 + SIGKILL;
        return SUCCESS;
    }
    return ERROR;
}

int bun_pty_get_pid(int handle) {
    BunPty *p = bunpty_get(handle);
    return p ? (int)p->pid : ERROR;
}

int bun_pty_get_exit_code(int handle) {
    BunPty *p = bunpty_get(handle);
    return p ? p->exit_code : ERROR;
}

void bun_pty_close(int handle) {
    if (handle <= 0 || handle >= MAX_PTYS) return;
    pthread_mutex_lock(&g_lock);
    BunPty *p = &g_ptys[handle];
    if (!p->used) {
        pthread_mutex_unlock(&g_lock);
        return;
    }
    p->used = 0;
    int fd = p->fd;
    pid_t pid = p->pid;
    int exited = p->exited;
    p->fd = -1;
    p->pid = -1;
    pthread_mutex_unlock(&g_lock);

    if (fd >= 0) close(fd);
    if (!exited && pid > 0) {
        kill(pid, SIGKILL);
        int status = 0;
        pid_t r;
        do {
            r = waitpid(pid, &status, 0);
        } while (r < 0 && errno == EINTR);
    }
}
