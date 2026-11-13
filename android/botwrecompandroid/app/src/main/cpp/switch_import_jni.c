#include <jni.h>
#include <dirent.h>
#include <stdint.h>
#include <setjmp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/types.h>

#include "extkeys.h"
#include "nca.h"
#include "pki.h"
#include "sha.h"

/* hactool's CLI exits on malformed input. Restrict that failure to the importing thread. */
static _Thread_local jmp_buf *hactool_escape;
__attribute__((noreturn)) void switch_hactool_exit(int status) {
    if (hactool_escape) longjmp(*hactool_escape, 1);
    _Exit(status);
}

static int guarded_keys(hactool_settings_t *settings, FILE *file) {
    jmp_buf guard;
    jmp_buf *previous = hactool_escape;
    hactool_escape = &guard;
    if (setjmp(guard)) { hactool_escape = previous; return 0; }
    pki_initialize_keyset(&settings->keyset, KEYSET_RETAIL);
    settings->skip_key_warnings = 1; /* Unknown lines may contain secrets: never print their values. */
    extkeys_initialize_settings(settings, file);
    pki_derive_keys(&settings->keyset);
    hactool_escape = previous;
    return 1;
}

static int guarded_add_titlekey(hactool_settings_t *settings, const unsigned char *rights_id, const unsigned char *key) {
    jmp_buf guard;
    jmp_buf *previous = hactool_escape;
    hactool_escape = &guard;
    if (setjmp(guard)) { hactool_escape = previous; return 0; }
    settings_add_titlekey(settings, rights_id, key);
    hactool_escape = previous;
    return 1;
}

/* Returns -1 on fatal parser failure, 0 on invalid header and 1 on success. */
static int guarded_header(nca_ctx_t *ctx) {
    jmp_buf guard;
    jmp_buf *previous = hactool_escape;
    hactool_escape = &guard;
    if (setjmp(guard)) { hactool_escape = previous; return -1; }
    int ok = nca_decrypt_header(ctx);
    hactool_escape = previous;
    return ok;
}

static int guarded_process(nca_ctx_t *ctx) {
    jmp_buf guard;
    jmp_buf *previous = hactool_escape;
    hactool_escape = &guard;
    if (setjmp(guard)) { hactool_escape = previous; return 0; }
    nca_process(ctx);
    hactool_escape = previous;
    return 1;
}

static int guarded_section_read(nca_section_ctx_t *ctx, uint64_t off, void *data, size_t size) {
    jmp_buf guard;
    jmp_buf *previous = hactool_escape;
    hactool_escape = &guard;
    if (setjmp(guard)) { hactool_escape = previous; return 0; }
    nca_section_fseek(ctx, off);
    int ok = nca_section_fread(ctx, data, size) == size;
    hactool_escape = previous;
    return ok;
}

/* Check both ends of each IVFC level using the level above it. */
static int verify_romfs_samples(nca_section_ctx_t *section) {
    ivfc_level_ctx_t *levels = section->type == BKTR ? section->bktr_ctx.ivfc_levels : section->romfs_ctx.ivfc_levels;
    for (int i = 1; i < IVFC_MAX_LEVEL; i++) {
        ivfc_level_ctx_t *level = &levels[i], *parent = &levels[i - 1];
        if (!level->data_size) continue;
        if (!parent->data_size) return 0;
        uint64_t block = level->hash_block_size;
        if (!block || block > (1u << 20) || level->data_size > UINT64_MAX - block + 1) return 0;
        uint64_t blocks = (level->data_size + block - 1) / block;
        if (blocks > parent->data_size / 32) return 0;
        unsigned char *buf = malloc((size_t)block);
        if (!buf) return 0;
        uint64_t samples[2] = {0, blocks - 1};
        int valid = 1;
        for (int j = 0; j < (blocks == 1 ? 1 : 2); j++) {
            uint64_t index = samples[j], at = index * block;
            size_t count = (size_t)((level->data_size - at < block) ? level->data_size - at : block);
            unsigned char expected[32], actual[32];
            memset(buf, 0, (size_t)block);
            if (parent->data_offset > UINT64_MAX - index * 32 || level->data_offset > UINT64_MAX - at ||
                    !guarded_section_read(section, parent->data_offset + index * 32, expected, sizeof expected) ||
                    !guarded_section_read(section, level->data_offset + at, buf, count)) { valid = 0; break; }
            sha256_hash_buffer(actual, buf, (size_t)block);
            if (memcmp(actual, expected, sizeof actual)) { valid = 0; break; }
        }
        free(buf);
        if (!valid) return 0;
    }
    return 1;
}

static jstring result(JNIEnv *env, const char *text) {
    return (*env)->NewStringUTF(env, text);
}

static int ticket_body_offset(FILE *ticket) {
    unsigned char b[4];
    if (fseeko(ticket, 0, SEEK_SET) != 0 || fread(b, 1, sizeof b, ticket) != sizeof b) return -1;
    uint32_t types[2] = {
        (uint32_t)b[0] | (uint32_t)b[1] << 8 | (uint32_t)b[2] << 16 | (uint32_t)b[3] << 24,
        (uint32_t)b[3] | (uint32_t)b[2] << 8 | (uint32_t)b[1] << 16 | (uint32_t)b[0] << 24
    };
    for (int i = 0; i < 2; i++) {
        uint32_t type = types[i];
        if (type == 0x00010000 || type == 0x00010003) return 0x240; /* RSA-4096 */
        if (type == 0x00010001 || type == 0x00010004) return 0x140; /* RSA-2048 */
        if (type == 0x00010002 || type == 0x00010005) return 0x080; /* ECDSA */
        if (type == 0x00010006) return 0x040; /* HMAC-SHA1 */
    }
    return -1;
}

static int add_ticket_file(hactool_settings_t *settings, const char *path, const unsigned char *rights_id) {
    FILE *ticket = fopen(path, "rb");
    if (!ticket) return 0;
    int body = ticket_body_offset(ticket);
    unsigned char ticket_rights_id[0x10], encrypted_titlekey[0x10], key_type;
    int ok = 0;
    if (body >= 0 && fseeko(ticket, body + 0x160, SEEK_SET) == 0 &&
            fread(ticket_rights_id, 1, sizeof(ticket_rights_id), ticket) == sizeof(ticket_rights_id) &&
            memcmp(ticket_rights_id, rights_id, sizeof(ticket_rights_id)) == 0 &&
            fseeko(ticket, body + 0x141, SEEK_SET) == 0 && fread(&key_type, 1, 1, ticket) == 1) {
        if (key_type != 0) {
            fclose(ticket);
            return -1; /* Personalized tickets require a console-specific RSA key. */
        }
        if (
            fseeko(ticket, body + 0x40, SEEK_SET) == 0 &&
            fread(encrypted_titlekey, 1, sizeof(encrypted_titlekey), ticket) == sizeof(encrypted_titlekey)) {
            ok = guarded_add_titlekey(settings, rights_id, encrypted_titlekey) ? 1 : -2;
        }
    }
    fclose(ticket);
    return ok;
}

static int add_ticket_from_dir(hactool_settings_t *settings, const char *dir, const unsigned char *rights_id) {
    if (settings_has_titlekey(settings, rights_id)) return 1;
    char rights_name[33], path[4096];
    for (unsigned int i = 0; i < 0x10; i++) snprintf(rights_name + i * 2, 3, "%02x", rights_id[i]);
    snprintf(path, sizeof(path), "%s/%s.tik", dir, rights_name);
    int found = add_ticket_file(settings, path, rights_id);
    if (found) return found;

    DIR *tickets = opendir(dir);
    if (!tickets) return 0;
    struct dirent *entry;
    while ((entry = readdir(tickets)) != NULL) {
        size_t n = strlen(entry->d_name);
        if (n < 4 || strcasecmp(entry->d_name + n - 4, ".tik") != 0) continue;
        snprintf(path, sizeof(path), "%s/%s", dir, entry->d_name);
        found = add_ticket_file(settings, path, rights_id);
        if (found) {
            closedir(tickets);
            return found;
        }
    }
    closedir(tickets);
    return 0;
}

static int nca_is_patch(const nca_header_t *header) {
    for (int i = 0; i < 4; i++) {
        const nca_fs_header_t *fs = &header->fs_headers[i];
        if (header->section_entries[i].media_start_offset && fs->partition_type == PARTITION_ROMFS &&
                fs->fs_type == FS_TYPE_ROMFS && fs->crypt_type == CRYPT_BKTR) return 1;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_botwrecompandroid_BOTWRecompAndroidActivity_nativeNcaKind(JNIEnv *env, jclass cls, jstring path, jstring keys) {
    (void)cls;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    const char *k = (*env)->GetStringUTFChars(env, keys, NULL);
    int kind = -2;
    FILE *f = p ? fopen(p, "rb") : NULL, *kf = k ? fopen(k, "rb") : NULL;
    if (f && kf) {
        hactool_ctx_t tool = {0};
        int keys_ok = guarded_keys(&tool.settings, kf);
        nca_ctx_t nca;
        nca_init(&nca);
        nca.file = f;
        nca.tool_ctx = &tool;
        if (keys_ok && guarded_header(&nca) == 1) {
            kind = -1;
            fprintf(stderr, "[switch-import] NCA title=%016llx content=%u\n",
                    (unsigned long long)nca.header.title_id, nca.header.content_type);
            if (nca.header.content_type == NCACONTENTTYPE_PROGRAM) {
                if (nca.header.title_id == UINT64_C(0x01007ef00011e000)) kind = nca_is_patch(&nca.header) ? 1 : 0;
                else if (nca.header.title_id == UINT64_C(0x01007ef00011e800)) kind = 1;
            } else if (nca.header.content_type == NCACONTENTTYPE_DATA ||
                       nca.header.content_type == NCACONTENTTPYE_PUBLICDATA) {
                if (nca.header.title_id == UINT64_C(0x01007ef00011f001)) kind = 2;
                else if (nca.header.title_id == UINT64_C(0x01007ef00011f002)) kind = 3;
            }
        }
        free(tool.settings.known_titlekeys.titlekeys);
    }
    if (f) fclose(f);
    if (kf) fclose(kf);
    if (p) (*env)->ReleaseStringUTFChars(env, path, p);
    if (k) (*env)->ReleaseStringUTFChars(env, keys, k);
    return kind;
}

JNIEXPORT jstring JNICALL
Java_com_botwrecompandroid_BOTWRecompAndroidActivity_nativeExtractNca(
        JNIEnv *env, jclass cls, jstring nca_path, jstring keys_path, jstring ticket_dir,
        jstring exefs_path, jstring romfs_path, jint mode, jstring base_path, jboolean image) {
    (void)cls;
    const char *nca_name = (*env)->GetStringUTFChars(env, nca_path, NULL);
    const char *keys_name = (*env)->GetStringUTFChars(env, keys_path, NULL);
    const char *tickets_name = (*env)->GetStringUTFChars(env, ticket_dir, NULL);
    const char *exefs_name = (*env)->GetStringUTFChars(env, exefs_path, NULL);
    const char *romfs_name = (*env)->GetStringUTFChars(env, romfs_path, NULL);
    const char *base_name = base_path ? (*env)->GetStringUTFChars(env, base_path, NULL) : NULL;
    if (!nca_name || !keys_name || !tickets_name || !exefs_name || !romfs_name || (base_path && !base_name)) {
        if (nca_name) (*env)->ReleaseStringUTFChars(env, nca_path, nca_name);
        if (keys_name) (*env)->ReleaseStringUTFChars(env, keys_path, keys_name);
        if (tickets_name) (*env)->ReleaseStringUTFChars(env, ticket_dir, tickets_name);
        if (exefs_name) (*env)->ReleaseStringUTFChars(env, exefs_path, exefs_name);
        if (romfs_name) (*env)->ReleaseStringUTFChars(env, romfs_path, romfs_name);
        if (base_name) (*env)->ReleaseStringUTFChars(env, base_path, base_name);
        return result(env, "ERROR|Could not read import paths.");
    }

    char answer[256] = "ERROR|NCA extraction failed.";
    FILE *key_file = fopen(keys_name, "rb");
    FILE *nca_file = fopen(nca_name, "rb");
    if (!key_file || !nca_file) {
        snprintf(answer, sizeof(answer), "ERROR|Could not open %s.", !key_file ? "prod.keys" : "NCA file");
        if (key_file) fclose(key_file);
        if (nca_file) fclose(nca_file);
        goto done;
    }

    hactool_ctx_t tool;
    memset(&tool, 0, sizeof(tool));
    int keys_ok = guarded_keys(&tool.settings, key_file);
    fclose(key_file);
    if (!keys_ok) {
        snprintf(answer, sizeof(answer), "ERROR|Could not read the key file.");
        fclose(nca_file);
        goto free_titlekeys;
    }
    tool.action = ACTION_EXTRACT;
    tool.settings.has_expected_content_type = 1;
    tool.settings.expected_content_type = mode == 1 ? NCACONTENTTYPE_DATA : NCACONTENTTYPE_PROGRAM;
    tool.settings.exefs_dir_path.enabled = 1;
    filepath_set(&tool.settings.exefs_dir_path.path, exefs_name);
    tool.settings.romfs_dir_path.enabled = 1;
    filepath_set(&tool.settings.romfs_dir_path.path, romfs_name);
    if (memcmp(tool.settings.keyset.header_key, (unsigned char[0x20]){0}, 0x20) == 0) {
        snprintf(answer, sizeof(answer), "KEY|header_key");
        fclose(nca_file);
        goto free_titlekeys;
    }

    nca_ctx_t nca;
    nca_init(&nca);
    nca.tool_ctx = &tool;
    nca.file = nca_file;
    tool.file = nca_file;
    nca.is_cli_target = 1;
    if (fseeko(nca_file, 0, SEEK_END) == 0) {
        off_t length = ftello(nca_file);
        if (length > 0) nca.file_size = (size_t)length;
    }
    if (guarded_header(&nca) != 1) {
        snprintf(answer, sizeof(answer), "ERROR|NCA header could not be decrypted. Check header_key in prod.keys.");
        fclose(nca_file);
        goto free_titlekeys;
    }

    /* DLCs can use either Data or PublicData NCAs. */
    if (mode == 1 && nca.header.content_type == NCACONTENTTPYE_PUBLICDATA)
        tool.settings.expected_content_type = NCACONTENTTPYE_PUBLICDATA;

    /* Skip unrelated NCAs before attempting to decrypt their sections. */
    if (!(mode == 1 ? nca.header.title_id == UINT64_C(0x01007ef00011f001) :
            mode == 2 ? (nca.header.title_id == UINT64_C(0x01007ef00011e800) ||
                    (nca.header.title_id == UINT64_C(0x01007ef00011e000) && nca_is_patch(&nca.header))) :
            nca.header.title_id == UINT64_C(0x01007ef00011e000) && !nca_is_patch(&nca.header)) ||
            nca.header.content_type != tool.settings.expected_content_type) {
        snprintf(answer, sizeof(answer), "SKIP|Not the requested BOTW content.");
        fclose(nca_file);
        goto free_titlekeys;
    }

    if (image) {
        int sections = 0;
        tool.settings.romfs_dir_path.enabled = 0;
        for (int i = 0; i < 4; ++i) {
            nca_fs_header_t *fs = &nca.header.fs_headers[i];
            if (nca.header.section_entries[i].media_start_offset && fs->partition_type == PARTITION_ROMFS &&
                    fs->fs_type == FS_TYPE_ROMFS) {
                filepath_set(&tool.settings.section_paths[i], romfs_name);
                sections++;
            }
        }
        if (sections != 1) {
            snprintf(answer, sizeof(answer), "ERROR|The selected content does not contain exactly one RomFS section.");
            fclose(nca_file);
            goto free_titlekeys;
        }
    }

    int has_rights_id = 0;
    for (unsigned int i = 0; i < sizeof(nca.header.rights_id); i++) {
        if (nca.header.rights_id[i] != 0) {
            has_rights_id = 1;
            unsigned int generation = nca.header.crypto_type;
            if (nca.header.crypto_type2 > generation) generation = nca.header.crypto_type2;
            if (generation) generation--;
            if (generation >= 0x20 ||
                    memcmp(tool.settings.keyset.titlekeks[generation], (unsigned char[0x10]){0}, 0x10) == 0) {
                snprintf(answer, sizeof(answer), "KEY|titlekek_%02x", generation);
                fclose(nca_file);
                goto free_titlekeys;
            }
            char rights_name[33];
            for (unsigned int j = 0; j < 0x10; j++) snprintf(rights_name + j * 2, 3, "%02x", nca.header.rights_id[j]);
            int ticket_result = add_ticket_from_dir(&tool.settings, tickets_name, nca.header.rights_id);
            if (ticket_result < 0) {
                snprintf(answer, sizeof(answer), "%s", ticket_result == -1 ?
                        "ERROR|This content uses a personalized ticket, which is not supported." :
                        "ERROR|Could not load the title key.");
                fclose(nca_file);
                goto free_titlekeys;
            }
            if (!settings_has_titlekey(&tool.settings, nca.header.rights_id)) {
                snprintf(answer, sizeof(answer), "RIGHTS|%s", rights_name);
                fclose(nca_file);
                goto free_titlekeys;
            }
            break;
        }
    }

    unsigned int generation = nca.header.crypto_type;
    if (nca.header.crypto_type2 > generation) generation = nca.header.crypto_type2;
    if (generation) generation--;
    if (!has_rights_id && (generation >= 0x20 || nca.header.kaek_ind >= 3 ||
            memcmp(tool.settings.keyset.key_area_keys[generation][nca.header.kaek_ind],
                   (unsigned char[0x10]){0}, 0x10) == 0)) {
        snprintf(answer, sizeof(answer), "KEY|key_area_key_application_%02x", generation);
        fclose(nca_file);
        goto free_titlekeys;
    }

    hactool_ctx_t base_tool = {0};
    nca_ctx_t base_nca;
    FILE *base_file = NULL;
    int base_ready = 0;
    if (mode == 2) {
        base_file = base_name ? fopen(base_name, "rb") : NULL;
        if (!base_file) {
            snprintf(answer, sizeof(answer), "ERROR|The base game NCA is required for the update.");
            fclose(nca_file);
            goto free_titlekeys;
        }
        base_tool.settings.keyset = tool.settings.keyset;
        nca_init(&base_nca);
        base_nca.file = base_file;
        base_nca.tool_ctx = &base_tool;
        if (guarded_header(&base_nca) != 1 || base_nca.header.title_id != UINT64_C(0x01007ef00011e000)) {
            snprintf(answer, sizeof(answer), "ERROR|The selected base NCA is invalid.");
            fclose(base_file); fclose(nca_file);
            goto free_titlekeys;
        }
        unsigned char zero_rights[16] = {0};
        unsigned int base_generation = base_nca.header.crypto_type > base_nca.header.crypto_type2 ?
                base_nca.header.crypto_type : base_nca.header.crypto_type2;
        if (base_generation) base_generation--;
        if (base_generation >= 0x20) {
            snprintf(answer, sizeof(answer), "ERROR|Unsupported base game key generation.");
            fclose(base_file); fclose(nca_file);
            goto free_base_keys;
        }
        if (memcmp(base_nca.header.rights_id, zero_rights, 16)) {
            if (!memcmp(base_tool.settings.keyset.titlekeks[base_generation], (unsigned char[16]){0}, 16)) {
                snprintf(answer, sizeof(answer), "KEY|titlekek_%02x", base_generation);
                fclose(base_file); fclose(nca_file);
                goto free_base_keys;
            }
            int t = add_ticket_from_dir(&base_tool.settings, tickets_name, base_nca.header.rights_id);
            if (t <= 0) {
                snprintf(answer, sizeof(answer), "ERROR|The base game requires a supported matching ticket.");
                fclose(base_file); fclose(nca_file);
                goto free_base_keys;
            }
        } else if (base_nca.header.kaek_ind >= 3 ||
                   !memcmp(base_tool.settings.keyset.key_area_keys[base_generation][base_nca.header.kaek_ind],
                           (unsigned char[16]){0}, 16)) {
            snprintf(answer, sizeof(answer), "KEY|key_area_key_application_%02x", base_generation);
            fclose(base_file); fclose(nca_file);
            goto free_base_keys;
        }
        base_ready = 1;
        if (!guarded_process(&base_nca)) {
            snprintf(answer, sizeof(answer), "ERROR|Could not read the base NCA. Check the file and its keys.");
            goto close_base;
        }
        int has_base_romfs = 0;
        for (int i = 0; i < 4; ++i)
            if (base_nca.section_contexts[i].is_present && base_nca.section_contexts[i].type == ROMFS) has_base_romfs = 1;
        if (!has_base_romfs) {
            snprintf(answer, sizeof(answer), "ERROR|The base NCA has no readable RomFS.");
            goto close_base;
        }
        tool.base_file = base_file;
        tool.base_file_type = BASEFILE_NCA;
        tool.base_nca_ctx = &base_nca;
    }
    if (!guarded_process(&nca)) {
        nca_free_section_contexts(&nca);
        if (image) remove(romfs_name);
        snprintf(answer, sizeof(answer), "ERROR|Could not extract the NCA. Check the file, its keys and the base game.");
        goto close_base;
    }
    int has_exefs = 0, has_romfs = 0, valid_romfs = 0;
    for (unsigned int i = 0; i < 4; i++) {
        nca_section_ctx_t *section = &nca.section_contexts[i];
        if (!section->is_present) continue;
        if (section->type == PFS0 && section->pfs0_ctx.is_exefs) has_exefs = 1;
        if (section->type == ROMFS || section->type == BKTR || section->type == NCA0_ROMFS) {
            has_romfs = 1;
            if (section->superblock_hash_validity == VALIDITY_VALID &&
                    (section->type == NCA0_ROMFS || verify_romfs_samples(section))) valid_romfs = 1;
        }
    }
    if (image && !valid_romfs) {
        remove(romfs_name);
        snprintf(answer, sizeof(answer), "ERROR|The RomFS could not be verified with the selected keys and base game.");
    } else {
        snprintf(answer, sizeof(answer), "OK|%016llx|%u|%d|%d",
                 (unsigned long long)nca.header.title_id, nca.header.content_type, has_exefs, has_romfs);
    }
    nca_free_section_contexts(&nca);
close_base:
    if (base_ready) nca_free_section_contexts(&base_nca);
    if (base_file) fclose(base_file);
    fclose(nca_file);
free_base_keys:
    free(base_tool.settings.known_titlekeys.titlekeys);

free_titlekeys:
    if (tool.settings.known_titlekeys.titlekeys) free(tool.settings.known_titlekeys.titlekeys);
done:
    (*env)->ReleaseStringUTFChars(env, nca_path, nca_name);
    (*env)->ReleaseStringUTFChars(env, keys_path, keys_name);
    (*env)->ReleaseStringUTFChars(env, ticket_dir, tickets_name);
    (*env)->ReleaseStringUTFChars(env, exefs_path, exefs_name);
    (*env)->ReleaseStringUTFChars(env, romfs_path, romfs_name);
    if (base_name) (*env)->ReleaseStringUTFChars(env, base_path, base_name);
    return result(env, answer);
}
