/* Inert stand-in for `pi`. Herdr recognises the agent from the foreground executable
 * name, so this binary must be named `pi`. When BENCH_PARENT_CMD is set it runs that
 * shell command as a child (the bench parent script) so `oh` runs as a descendant of
 * an agent pane. Otherwise it consumes stdin until EOF, absorbing any typed prompt. */
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/wait.h>

int main(int argc, char **argv) {
  setvbuf(stdout, NULL, _IONBF, 0);
  const char *cmd = getenv("BENCH_PARENT_CMD");
  if (cmd && *cmd) {
    unsetenv("BENCH_PARENT_CMD");
    /* Detached helper: the stub stays the foreground process Herdr classifies. */
    pid_t pid = fork();
    if (pid == 0) { execl("/bin/sh", "sh", "-c", cmd, (char *)0); perror("execl"); _exit(127); }
    printf("STUB HELPER PID %d\n", (int)pid);
  }
  int c; while ((c = getchar()) != EOF) { if (c == '\n') printf("stub pi idle\n"); }
  return 0;
}
