#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "nca.h"
#include "aes.h"
#include "sha.h"
static const unsigned char hdrkey[32] = { [0 ... 31] = 0x11 };
static const unsigned char areakey[16] = { [0 ... 15] = 0x33 };
static const unsigned char seckey[16] = { [0 ... 15] = 0x55 };
static void ctr(void *data, size_t size, uint64_t absolute, uint32_t generation) {
    unsigned char iv[16] = {0};
    iv[4] = generation >> 24; iv[5] = generation >> 16; iv[6] = generation >> 8; iv[7] = generation;
    absolute >>= 4;
    for (int i = 15; i >= 8; i--) { iv[i] = absolute; absolute >>= 8; }
    aes_ctx_t *a = new_aes_ctx(seckey, 16, AES_MODE_CTR);
    aes_setiv(a, iv, 16); aes_encrypt(a, data, data, size); free_aes_ctx(a);
}
static void header(FILE *f, nca_header_t *h) {
    unsigned char encrypted[0xc00], keys[64]; memset(keys, 0x55, sizeof keys);
    aes_ctx_t *a = new_aes_ctx(areakey, 16, AES_MODE_ECB);
    aes_encrypt(a, h->encrypted_keys, keys, sizeof keys); free_aes_ctx(a);
    a = new_aes_ctx(hdrkey, 32, AES_MODE_XTS);
    aes_xts_encrypt(a, encrypted, h, sizeof encrypted, 0, 0x200); free_aes_ctx(a);
    fwrite(encrypted, 1, sizeof encrypted, f);
}
static void ivfc(ivfc_hdr_t *i, unsigned char *data) {
    memset(i, 0, sizeof *i); i->magic = MAGIC_IVFC; i->num_levels = 7;
    for (int n=0;n<4;n++) {
        i->level_headers[n].logical_offset=(uint64_t)n*0x20;
        i->level_headers[n].hash_data_size=0x20;
        i->level_headers[n].block_size=5;
    }
    i->level_headers[4].logical_offset=0x80;
    i->level_headers[4].hash_data_size=0x60;
    i->level_headers[4].block_size=7;
    i->level_headers[5].logical_offset=0x100;
    i->level_headers[5].hash_data_size=0x300;
    i->level_headers[5].block_size=8;
    for (int n=0;n<3;n++) sha256_hash_buffer(data+0x80+n*0x20, data+0x100+n*0x100, 0x100);
    unsigned char level4[0x80]={0}; memcpy(level4, data+0x80, 0x60);
    sha256_hash_buffer(data+0x60, level4, sizeof level4);
    sha256_hash_buffer(data+0x40, data+0x60, 0x20);
    sha256_hash_buffer(data+0x20, data+0x40, 0x20);
    sha256_hash_buffer(data, data+0x20, 0x20);
    sha256_hash_buffer(i->master_hash, data, 0x20);
}
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    char path[4096];
    unsigned char data[0x400] = {0};
    romfs_hdr_t *r=(void *)(data+0x100);
    r->header_size=0x50; r->dir_meta_table_offset=0x50; r->dir_meta_table_size=0x18;
    r->file_meta_table_offset=0x70; r->file_meta_table_size=0x28; r->data_offset=0x200;
    romfs_direntry_t *dir=(void *)(data+0x150);
    dir->sibling=ROMFS_ENTRY_EMPTY; dir->child=ROMFS_ENTRY_EMPTY; dir->file=0;
    romfs_fentry_t *file=(void *)(data+0x170);
    file->sibling=ROMFS_ENTRY_EMPTY; file->offset=0; file->size=16; file->name_size=6; memcpy(file->name,"sample",6);
    memcpy(data+0x300,"BASE-EXAMPLE-00",16);
    nca_header_t h = {0}; h.magic=MAGIC_NCA3; h.title_id=UINT64_C(0x01007ef00011e000); h.nca_size=0x1000;
    h.section_entries[0].media_start_offset=6; h.section_entries[0].media_end_offset=8;
    h.fs_headers[0].partition_type=PARTITION_ROMFS; h.fs_headers[0].fs_type=FS_TYPE_ROMFS; h.fs_headers[0].crypt_type=CRYPT_NONE;
    ivfc(&h.fs_headers[0].romfs_superblock.ivfc_header, data);
    snprintf(path, sizeof path, "%s/full-base.nca", argv[1]);
    FILE *out=fopen(path,"wb"); if (!out) return 2;
    header(out,&h); fwrite(data,1,sizeof data,out); fclose(out);
    h.title_id=UINT64_C(0x01007ef00011f001); h.content_type=NCACONTENTTYPE_DATA;
    memcpy(data+0x300,"DLC--EXAMPLE-00",16);
    ivfc(&h.fs_headers[0].romfs_superblock.ivfc_header, data);
    snprintf(path, sizeof path, "%s/full-dlc.nca", argv[1]);
    out=fopen(path,"wb"); if (!out) return 2;
    header(out,&h); fwrite(data,1,sizeof data,out); fclose(out);
    h.content_type=NCACONTENTTPYE_PUBLICDATA;
    snprintf(path, sizeof path, "%s/public-dlc.nca", argv[1]);
    out=fopen(path,"wb"); if (!out) return 2;
    header(out,&h); fwrite(data,1,sizeof data,out); fclose(out);
    h.title_id=UINT64_C(0x01007ef00011f002);
    snprintf(path, sizeof path, "%s/full-dlc-unlock.nca", argv[1]);
    out=fopen(path,"wb"); if (!out) return 2;
    header(out,&h); fwrite(data,1,sizeof data,out); fclose(out);
    const size_t section_size=0x10400; unsigned char *sec=calloc(1, section_size);
    bktr_relocation_block_t *rel=(void *)(sec+0x400); rel->num_buckets=1; rel->total_size=0x400;
    rel->buckets[0].num_entries=4; rel->buckets[0].virtual_offset_end=0x400;
    rel->buckets[0].entries[0]=(bktr_relocation_entry_t){0,0,1};
    rel->buckets[0].entries[1]=(bktr_relocation_entry_t){0x100,0x100,0};
    rel->buckets[0].entries[2]=(bktr_relocation_entry_t){0x300,0x100,1};
    rel->buckets[0].entries[3]=(bktr_relocation_entry_t){0x310,0x310,0};
    bktr_subsection_block_t *sub=(void *)(sec+0x8400); sub->num_buckets=1; sub->total_size=0x8400;
    sub->buckets[0].num_entries=1; sub->buckets[0].physical_offset_end=0x400;
    sub->buckets[0].entries[0]=(bktr_subsection_entry_t){0,0,7};
    memset(&h,0,sizeof h); h.magic=MAGIC_NCA3; h.title_id=UINT64_C(0x01007ef00011e800); h.nca_size=0xc00+section_size;
    h.section_entries[0].media_start_offset=6; h.section_entries[0].media_end_offset=(0xc00+section_size)/0x200;
    nca_fs_header_t *fs=&h.fs_headers[0]; fs->partition_type=PARTITION_ROMFS; fs->fs_type=FS_TYPE_ROMFS; fs->crypt_type=CRYPT_BKTR;
    memcpy(data+0x300,"PATCH-EXAMPLE-0",16);
    ivfc(&fs->bktr_superblock.ivfc_header, data);
    fs->bktr_superblock.relocation_header=(bktr_header_t){0x400,0x8000,MAGIC_BKTR,0,4,0};
    fs->bktr_superblock.subsection_header=(bktr_header_t){0x8400,0x8000,MAGIC_BKTR,0,1,0};
    ctr(sec,section_size,0xc00,0);
    memset(sec,0,0x200); memcpy(sec,data,0x100); memcpy(sec+0x100,data+0x300,16); ctr(sec,0x200,0xc00,7);
    snprintf(path, sizeof path, "%s/full-update.nca", argv[1]);
    out=fopen(path,"wb"); if (!out) return 2;
    header(out,&h); fwrite(sec,1,section_size,out); fclose(out);
    h.title_id=UINT64_C(0x01007ef00011e000);
    snprintf(path, sizeof path, "%s/same-title-update.nca", argv[1]);
    out=fopen(path,"wb"); if (!out) return 2;
    header(out,&h); fwrite(sec,1,section_size,out); fclose(out);
    h.title_id=UINT64_C(0x01007ef00011e800);
    /* A second layout has a gap, trailing padding and a physical-size bucket header. */
    const size_t padded_size = 0x10800;
    unsigned char *padded = calloc(1, padded_size);
    memcpy(padded, sec, section_size);
    ctr(padded, section_size, 0xc00, 0);
    memmove(padded+0x8600, padded+0x8400, 0x8000);
    memset(padded+0x8400, 0, 0x200);
    ((bktr_subsection_block_t *)(padded+0x8600))->total_size = 0x400;
    ctr(padded, padded_size, 0xc00, 0);
    h.fs_headers[0].bktr_superblock.subsection_header.offset = 0x8600;
    h.section_entries[0].media_end_offset = (0xc00+padded_size)/0x200;
    h.nca_size = 0xc00+padded_size;
    snprintf(path, sizeof path, "%s/padded-update.nca", argv[1]);
    out=fopen(path,"wb"); if (!out) return 2;
    header(out,&h); fwrite(padded,1,padded_size,out); fclose(out); free(padded);
    h.fs_headers[0].bktr_superblock.subsection_header.offset = 0x8400;
    h.section_entries[0].media_end_offset = (0xc00+section_size)/0x200;
    h.nca_size = 0xc00+section_size;
    sec[0x105] ^= 1;
    snprintf(path, sizeof path, "%s/corrupt-update.nca", argv[1]);
    out=fopen(path,"wb"); if (!out) return 2;
    header(out,&h); fwrite(sec,1,section_size,out); fclose(out);
    sec[0x105] ^= 1;
    h.fs_headers[0].bktr_superblock.relocation_header.size = 0x7e00;
    snprintf(path, sizeof path, "%s/bad-update.nca", argv[1]);
    out=fopen(path,"wb"); if (!out) return 2;
    header(out,&h); fwrite(sec,1,section_size,out); fclose(out); free(sec);
    puts("Synthetic encrypted base and BKTR update created"); return 0;
}
