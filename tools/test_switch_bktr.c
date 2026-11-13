/* Synthetic BKTR reads, linked against the same hactool code as the Android importer. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "nca.h"
#include "aes.h"
int main(void) {
    const unsigned char key[16] = {1};
    const char base_data[16] = "BASE-0123456789";
    const char patch_data[32] = "PATCH-0123456789SECOND-01234567";
    unsigned char encrypted[32], iv[16] = {0};
    aes_ctx_t *enc = new_aes_ctx(key, 16, AES_MODE_CTR);
    aes_setiv(enc, iv, 16); aes_encrypt(enc, encrypted, patch_data, 16);
    iv[7] = 7; iv[15] = 1;
    aes_setiv(enc, iv, 16); aes_encrypt(enc, encrypted + 16, patch_data + 16, 16);
    free_aes_ctx(enc);
    FILE *base = tmpfile(), *patch = tmpfile();
    if (!base || !patch) return 2;
    fwrite(base_data, 1, 16, base); fwrite(encrypted, 1, 32, patch);
    hactool_ctx_t tool = {0}; nca_ctx_t base_nca = {0}; nca_section_ctx_t ctx = {0};
    base_nca.section_contexts[0].type = ROMFS;
    base_nca.section_contexts[0].is_decrypted = 1;
    base_nca.section_contexts[0].file = base;
    tool.base_file = base; tool.base_file_type = BASEFILE_NCA; tool.base_nca_ctx = &base_nca;
    ctx.file = patch; ctx.tool_ctx = &tool; ctx.type = BKTR; ctx.crypt_type = CRYPT_BKTR;
    ctx.aes = new_aes_ctx(key, 16, AES_MODE_CTR);
    ctx.bktr_ctx.relocation_block = calloc(1, sizeof(bktr_relocation_block_t) + sizeof(bktr_relocation_bucket_t) + 64);
    ctx.bktr_ctx.subsection_block = calloc(1, sizeof(bktr_subsection_block_t) + sizeof(bktr_subsection_bucket_t) + 64);
    bktr_relocation_block_t *r = ctx.bktr_ctx.relocation_block;
    r->num_buckets = 1; r->total_size = 48; r->buckets[0].num_entries = 2;
    r->buckets[0].entries[0] = (bktr_relocation_entry_t){0, 0, 0};
    r->buckets[0].entries[1] = (bktr_relocation_entry_t){16, 0, 1};
    r->buckets[0].entries[2].virt_offset = 48;
    bktr_subsection_block_t *s = ctx.bktr_ctx.subsection_block;
    s->num_buckets = 1; s->buckets[0].num_entries = 2;
    s->buckets[0].entries[0] = (bktr_subsection_entry_t){0, 0, 0};
    s->buckets[0].entries[1] = (bktr_subsection_entry_t){16, 0, 7};
    s->buckets[0].entries[2].offset = 32;
    unsigned char output[48]; nca_section_fseek(&ctx, 0);
    if (nca_section_fread(&ctx, output, sizeof output) != sizeof output ||
        memcmp(output, base_data, 16) || memcmp(output + 16, patch_data, 32)) {
        fprintf(stderr, "BKTR relocation or subsection decryption failed\n"); return 1;
    }
    nca_section_fseek(&ctx, 19);
    if (nca_section_fread(&ctx, output, 7) != 7 || memcmp(output, patch_data + 3, 7)) {
        fprintf(stderr, "BKTR unaligned patch read failed\n"); return 1;
    }
    nca_section_fseek(&ctx, 28);
    if (nca_section_fread(&ctx, output, 12) != 12 || memcmp(output, patch_data + 12, 12)) {
        fprintf(stderr, "BKTR subsection boundary read failed\n"); return 1;
    }
    nca_section_fseek(&ctx, 35);
    if (nca_section_fread(&ctx, output, 7) != 7 || memcmp(output, patch_data + 19, 7)) {
        fprintf(stderr, "BKTR unaligned second subsection read failed\n"); return 1;
    }
    puts("BKTR base + patch relocation and subsection CTR passed");
    free_aes_ctx(ctx.aes); free(r); free(s); fclose(base); fclose(patch);
    return 0;
}
