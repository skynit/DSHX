#include <sys/prctl.h>
#include <unistd.h>
#include <signal.h>
#include <stdlib.h>
#include <stdio.h>

/* Kill the direct engine process when its Android host dies. Setting this in
 * the short-lived launcher avoids racing a later PID-file cleanup. */
int main(int argc, char **argv) {
    const char *expected = getenv("DSHX_PARENT");
    if (argc < 2 || expected == NULL) return 125;
    pid_t parent = (pid_t)strtol(expected, NULL, 10);
    if (parent <= 1 || prctl(PR_SET_PDEATHSIG, SIGKILL) != 0 || getppid() != parent) {
        fputs("dshx: host lifetime could not be attached\n", stderr);
        return 125;
    }
    execv(argv[1], &argv[1]);
    perror("dshx: engine launch failed");
    return 126;
}
