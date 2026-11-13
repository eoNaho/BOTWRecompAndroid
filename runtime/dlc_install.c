#include "dlc_install.h"
#include "romfs_build.h"
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static bool remove_owned_tree(const char *path) {
    struct stat st;
    if (lstat(path, &st) != 0) return errno == ENOENT;
    if (!S_ISDIR(st.st_mode)) return unlink(path) == 0;
    DIR *dir = opendir(path);
    if (!dir) return false;
    bool ok = true;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        char child[4096];
        int n = snprintf(child, sizeof child, "%s/%s", path, entry->d_name);
        if (n < 0 || (size_t)n >= sizeof child || !remove_owned_tree(child)) { ok = false; break; }
    }
    if (closedir(dir) != 0) ok = false;
    return ok && rmdir(path) == 0;
}

static bool exists(const char *path) {
    struct stat st;
    return lstat(path, &st) == 0;
}

bool dlc_install_image(const char *source, const char *dir, bool (*keep)(const char *), char *why, size_t why_size) {
    if (!source || !dir || !*dir || !keep) {
        if (why) snprintf(why, why_size, "Invalid DLC installation path.");
        return false;
    }
    char loose[4096], pending[4096], backup[4096], image[4096];
    if (snprintf(loose, sizeof loose, "%s/romfs", dir) >= (int)sizeof loose ||
        snprintf(pending, sizeof pending, "%s/romfs.importing", dir) >= (int)sizeof pending ||
        snprintf(backup, sizeof backup, "%s/romfs.previous", dir) >= (int)sizeof backup ||
        snprintf(image, sizeof image, "%s/aoc.bin", dir) >= (int)sizeof image) {
        if (why) snprintf(why, why_size, "DLC installation path is too long.");
        return false;
    }
    int fd = open(source, O_RDONLY);
    if (fd < 0) { if (why) snprintf(why, why_size, "Could not open the extracted DLC image."); return false; }
    bool ok = romfs_check_dlc_image(fd, why, why_size);
    struct stat st;
    if (ok && mkdir(dir, 0755) != 0 && (errno != EEXIST || stat(dir, &st) != 0 || !S_ISDIR(st.st_mode))) {
        if (why) snprintf(why, why_size, "Could not create the DLC folder.");
        ok = false;
    }
    if (ok && exists(backup)) {
        if (!exists(loose)) {
            if (rename(backup, loose) != 0) {
                if (why) snprintf(why, why_size, "Could not restore the previous DLC files.");
                ok = false;
            }
        } else if (!remove_owned_tree(backup)) {
            if (why) snprintf(why, why_size, "Could not remove old temporary DLC files.");
            ok = false;
        }
    }
    if (ok && (!remove_owned_tree(pending) || mkdir(pending, 0755) != 0)) {
        if (why) snprintf(why, why_size, "Could not create temporary DLC storage.");
        ok = false;
    }
    if (ok) ok = romfs_image_extract(fd, pending, keep, why, why_size);
    close(fd);
    bool had_loose = false, moved_loose = false;
    if (ok && exists(loose)) {
        if (rename(loose, backup) != 0) {
            if (why) snprintf(why, why_size, "Could not save the previous DLC files.");
            ok = false;
        } else had_loose = true;
    }
    if (ok) {
        moved_loose = rename(pending, loose) == 0;
        if (!moved_loose || rename(source, image) != 0) {
            if (why) snprintf(why, why_size, "Could not finish installing the DLC image.");
            ok = false;
        }
    }
    if (!ok) {
        if (moved_loose) remove_owned_tree(loose);
        if (had_loose && rename(backup, loose) != 0)
            fprintf(stderr, "[dlc] could not restore previous loose files from %s\n", backup);
        remove_owned_tree(pending);
    } else if (had_loose && !remove_owned_tree(backup)) {
        fprintf(stderr, "[dlc] could not remove previous loose files at %s\n", backup);
    }
    return ok;
}
