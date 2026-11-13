#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "dlc_install.h"
#include "romfs_build.h"

static bool keep_movie(const char *path) { return strstr(path, ".webm") != NULL; }
static bool present(const char *dir, const char *name) {
    char path[4096]; snprintf(path, sizeof path, "%s/%s", dir, name);
    return access(path, F_OK) == 0;
}

int main(int argc, char **argv) {
    if (argc != 7) return 2;
    char why[256] = "";
    if (!romfs_build(argv[1], argv[4], NULL, why, sizeof why)) { fprintf(stderr, "build: %s\n", why); return 1; }
    int fd = open(argv[4], O_RDWR);
    if (fd < 0 || !romfs_check_dlc_image(fd, why, sizeof why)) { fprintf(stderr, "valid DLC: %s\n", why); return 1; }
    uint64_t header[10], bad_size;
    struct stat st;
    if (pread(fd, header, sizeof header, 0) != sizeof header || fstat(fd, &st) != 0) return 1;
    bad_size = (uint64_t)st.st_size + 1;
    if (pwrite(fd, &bad_size, sizeof bad_size, (off_t)header[7] + 16) != sizeof bad_size) return 1;
    if (romfs_check_dlc_image(fd, why, sizeof why)) { fprintf(stderr, "out-of-bounds DLC file accepted\n"); return 1; }
    close(fd);
    if (!romfs_build(argv[1], argv[4], NULL, why, sizeof why)) return 1;
    fd = open(argv[4], O_RDWR);
    if (fd < 0 || pread(fd, header, sizeof header, 0) != sizeof header ||
        pwrite(fd, "/", 1, (off_t)header[7] + 0x20) != 1) return 1;
    if (romfs_check_dlc_image(fd, why, sizeof why)) { fprintf(stderr, "unsafe DLC filename accepted\n"); return 1; }
    close(fd);
    for (int i = 2; i <= 3; i++) {
        if (!romfs_build(argv[i], argv[4], NULL, why, sizeof why)) { fprintf(stderr, "build incomplete: %s\n", why); return 1; }
        fd = open(argv[4], O_RDONLY);
        if (fd < 0 || romfs_check_dlc_image(fd, why, sizeof why)) { fprintf(stderr, "incomplete DLC accepted\n"); return 1; }
        close(fd);
    }
    if (dlc_install_image(argv[4], argv[5], keep_movie, why, sizeof why) || !present(argv[5], "romfs/old.webm")) {
        fprintf(stderr, "incomplete image changed the existing installation\n"); return 1;
    }
    if (!romfs_build(argv[1], argv[4], NULL, why, sizeof why)) return 1;
    if (dlc_install_image(argv[4], argv[6], keep_movie, why, sizeof why) ||
        !present(argv[6], "romfs/old.webm") || present(argv[6], "romfs/Movie/new.webm") ||
        present(argv[6], "romfs.importing") || access(argv[4], F_OK) != 0) {
        fprintf(stderr, "failed image replacement did not restore previous loose files\n"); return 1;
    }
    if (!dlc_install_image(argv[4], argv[5], keep_movie, why, sizeof why) ||
        !present(argv[5], "aoc.bin") || !present(argv[5], "romfs/Movie/new.webm") ||
        present(argv[5], "romfs/old.webm") || !present(argv[5], "unrelated.txt")) {
        fprintf(stderr, "DLC image installation failed: %s\n", why); return 1;
    }
    puts("DLC validation, image installation and rollback passed");
    return 0;
}
