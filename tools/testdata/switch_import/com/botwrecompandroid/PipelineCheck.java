package com.botwrecompandroid;

import android.os.ParcelFileDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Exercise the real container reader and JNI importer together, with synthetic game data. */
public final class PipelineCheck {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void scan(Path archive, Path scratch, Path base, Path update, String keys) throws Exception {
        final int[] kind = {-1};
        try (ParcelFileDescriptor pfd = new ParcelFileDescriptor(archive)) {
            SwitchContainerInspector.processNcas(pfd, scratch.toFile(), header -> {
                kind[0] = BOTWRecompAndroidActivity.nativeNcaKind(header.getAbsolutePath(), keys);
                check(kind[0] != -2, "valid synthetic header");
                return kind[0] == 0 || kind[0] == 1;
            }, nca -> {
                Files.move(nca.toPath(), kind[0] == 0 ? base : update);
                return false;
            }, null);
        }
    }
    public static void main(String[] args) throws Exception {
        Path d = Path.of(args[0]), scratch = d.resolve("pipeline-work"), out = Files.createDirectories(d.resolve("pipeline-out"));
        String k = d.resolve("keys").toString(), t = scratch.toString(), o = out.toString();
        byte[] baseData = Files.readAllBytes(d.resolve("full-base.nca"));
        byte[] updateData = Files.readAllBytes(d.resolve("full-update.nca"));
        byte[] dlcData = Files.readAllBytes(d.resolve("full-dlc.nca"));
        byte[] unlockData = Files.readAllBytes(d.resolve("full-dlc-unlock.nca"));
        byte[] secure = ContainerCheck.fs(true, new String[]{"base.nca", "dlc.nca"}, new byte[][]{baseData, dlcData});
        byte[] root = ContainerCheck.fs(true, new String[]{"secure"}, new byte[][]{secure});
        byte[] xci = new byte[0x200 + root.length];
        System.arraycopy("HEAD".getBytes(StandardCharsets.US_ASCII), 0, xci, 0x100, 4);
        ByteBuffer.wrap(xci).order(ByteOrder.LITTLE_ENDIAN).putLong(0x130, 0x200).putLong(0x138, 16 + 64 + 7);
        System.arraycopy(root, 0, xci, 0x200, root.length);
        Path xciPath = d.resolve("pipeline-base.xci"), nspPath = d.resolve("pipeline-update.nsp");
        Files.write(xciPath, xci);
        Files.write(nspPath, ContainerCheck.fs(false, new String[]{"update.nca", "unlock.nca"}, new byte[][]{updateData, unlockData}));
        Path base = d.resolve("staged-base.nca"), update = d.resolve("staged-update.nca"), image = d.resolve("pipeline-romfs.bin");
        scan(xciPath, scratch, base, update, k);
        scan(nspPath, scratch, base, update, k);
        String result = BOTWRecompAndroidActivity.nativeExtractNca(update.toString(), k, t, o, image.toString(), 2, base.toString(), true);
        check(result.startsWith("OK|"), "container-to-BKTR: " + result);
        check(Arrays.equals(Arrays.copyOfRange(Files.readAllBytes(image), 0x200, 0x210),
                "PATCH-EXAMPLE-0\0".getBytes(StandardCharsets.US_ASCII)), "patched image contents");
        try (ParcelFileDescriptor pfd = new ParcelFileDescriptor(xciPath)) {
            final int[] found = {0};
            SwitchContainerInspector.processNcas(pfd, scratch.toFile(),
                header -> BOTWRecompAndroidActivity.nativeNcaKind(header.getAbsolutePath(), k) == 2,
                nca -> {
                    String extracted = BOTWRecompAndroidActivity.nativeExtractNca(nca.getAbsolutePath(), k, t, o, image.toString(), 1, null, true);
                    check(extracted.startsWith("OK|"), "container-to-DLC: " + extracted); found[0]++; return true;
                }, null);
            check(found[0] == 1, "the DLC data NCA was selected");
        }
        check(Arrays.equals(Arrays.copyOfRange(Files.readAllBytes(image), 0x200, 0x210),
                "DLC--EXAMPLE-00\0".getBytes(StandardCharsets.US_ASCII)), "DLC image contents");
        System.out.println("XCI base + NSP update + DLC container pipeline passed");
    }
}
