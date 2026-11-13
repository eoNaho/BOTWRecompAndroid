package com.botwrecompandroid;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Host-side JNI harness. All keys and content below are synthetic test data. */
public class BOTWRecompAndroidActivity {
    static { System.load(System.getProperty("botw.test.lib")); }
    static native int nativeNcaKind(String path, String keys);
    static native String nativeExtractNca(String path, String keys, String tickets, String exefs, String romfs, int mode, String base, boolean image);
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Path keys = dir.resolve("keys"), tickets = Files.createDirectories(dir.resolve("tickets"));
        Path out = Files.createDirectories(dir.resolve("out"));
        Files.writeString(keys, "header_key = " + "11".repeat(32) + "\ntitlekek_00 = " + "22".repeat(16) +
                "\nkey_area_key_application_00 = " + "33".repeat(16) + "\n");
        String k = keys.toString(), t = tickets.toString(), o = out.toString();
        String base = dir.resolve("full-base.nca").toString();
        String update = dir.resolve("full-update.nca").toString();
        String dlc = dir.resolve("full-dlc.nca").toString();
        String dlcUnlock = dir.resolve("full-dlc-unlock.nca").toString();
        check(nativeNcaKind(base, k) == 0, "base classification");
        check(nativeNcaKind(update, k) == 1, "update classification");
        check(nativeNcaKind(dir.resolve("same-title-update.nca").toString(), k) == 1, "same-title patch classification");
        check(nativeNcaKind(dlc, k) == 2, "DLC classification");
        check(nativeNcaKind(dir.resolve("public-dlc.nca").toString(), k) == 2, "public DLC classification");
        check(nativeNcaKind(dlcUnlock, k) == 3, "second DLC classification");
        Files.writeString(keys, Files.readString(keys) + "master_key_01 = SYNTHETIC_SECRET_MARKER\n");
        check(nativeNcaKind(base, k) == 0 && nativeNcaKind(base, k) == 0, "unrelated malformed key entry");
        Files.writeString(keys, "header_key = " + "11".repeat(32) + "\ntitlekek_00 = " + "22".repeat(16) +
                "\nkey_area_key_application_00 = " + "33".repeat(16) + "\n");
        Path badKeys = dir.resolve("bad-keys");
        Files.writeString(badKeys, "header_key = " + "44".repeat(32) + "\n");
        check(nativeNcaKind(base, badKeys.toString()) == -2, "invalid header key rejected");

        byte[] rights = {1, 0, 0x7e, (byte)0xf0, 0, 0x11, (byte)0xe8, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        byte[] nca = new byte[0xc00];
        ByteBuffer.wrap(nca).order(ByteOrder.LITTLE_ENDIAN).putInt(0x200, 0x3341434e).putLong(0x210, 0x01007ef00011e800L);
        System.arraycopy(rights, 0, nca, 0x230, 16);
        Path rightsNca = dir.resolve("rights.nca"); Files.write(rightsNca, nca);
        byte[] ticket = new byte[0x2c0];
        ByteBuffer.wrap(ticket).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0x10004);
        Arrays.fill(ticket, 0x180, 0x190, (byte)0x44);
        System.arraycopy(rights, 0, ticket, 0x2a0, 16);
        Path ticketPath = tickets.resolve("different-name.tik"); Files.write(ticketPath, ticket);
        String missingBase = nativeExtractNca(rightsNca.toString(), k, t, o, o, 2, null, false);
        check(missingBase.contains("base game NCA"), "common ticket: " + missingBase);
        ticket[0x281] = 1; Files.write(ticketPath, ticket);
        String personalized = nativeExtractNca(rightsNca.toString(), k, t, o, o, 2, null, false);
        check(personalized.contains("personalized ticket"), "personalized ticket: " + personalized);
        int[] types = {0x10000, 0x10001, 0x10002, 0x10003, 0x10004, 0x10005, 0x10006};
        int[] offsets = {0x240, 0x140, 0x80, 0x240, 0x140, 0x80, 0x40};
        for (int i = 0; i < types.length; i++) {
            byte[] variant = new byte[offsets[i] + 0x180];
            ByteBuffer.wrap(variant).order(i == 0 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN).putInt(0, types[i]);
            Arrays.fill(variant, offsets[i] + 0x40, offsets[i] + 0x50, (byte)0x44);
            System.arraycopy(rights, 0, variant, offsets[i] + 0x160, 16);
            Files.write(ticketPath, variant);
            String result = nativeExtractNca(rightsNca.toString(), k, t, o, o, 2, null, false);
            check(result.contains("base game NCA"), "ticket signature type " + types[i] + ": " + result);
        }

        String sameTitle = nativeExtractNca(dir.resolve("same-title-update.nca").toString(), k, t, o, o, 2, base, false);
        check(sameTitle.startsWith("OK|"), "same-title patch extraction: " + sameTitle);
        String merged = nativeExtractNca(update, k, t, o, o, 2, base, false);
        check(merged.startsWith("OK|"), "BKTR extraction: " + merged);
        check(Arrays.equals(Files.readAllBytes(out.resolve("sample")), "PATCH-EXAMPLE-0\0".getBytes(StandardCharsets.US_ASCII)), "patched file contents");
        String publicDlc = nativeExtractNca(dir.resolve("public-dlc.nca").toString(), k, t, o, o, 1, null, false);
        check(publicDlc.startsWith("OK|"), "public DLC extraction: " + publicDlc);
        String extractedDlc = nativeExtractNca(dlc, k, t, o, o, 1, null, false);
        check(extractedDlc.startsWith("OK|"), "DLC extraction: " + extractedDlc);
        check(Arrays.equals(Files.readAllBytes(out.resolve("sample")), "DLC--EXAMPLE-00\0".getBytes(StandardCharsets.US_ASCII)), "DLC file contents");
        Path image = dir.resolve("romfs.bin");
        String invalidPatch = nativeExtractNca(dir.resolve("bad-update.nca").toString(), k, t, o, image.toString(), 2, base, true);
        check(invalidPatch.startsWith("ERROR|"), "damaged update should return an error without terminating the process: " + invalidPatch);
        String noOutput = nativeExtractNca(update, k, t, o, dir.resolve("missing/romfs.bin").toString(), 2, base, true);
        check(noOutput.startsWith("ERROR|"), "unwritable image destination should report an error: " + noOutput);
        String corrupt = nativeExtractNca(dir.resolve("corrupt-update.nca").toString(), k, t, o, image.toString(), 2, base, true);
        check(corrupt.startsWith("ERROR|") && !Files.exists(image), "bad IVFC data must be rejected: " + corrupt);
        String padded = nativeExtractNca(dir.resolve("padded-update.nca").toString(), k, t, o, image.toString(), 2, base, true);
        check(padded.startsWith("OK|"), "padded BKTR layout should be accepted: " + padded);
        check(Arrays.equals(Arrays.copyOfRange(Files.readAllBytes(image), 0x200, 0x210),
                "PATCH-EXAMPLE-0\0".getBytes(StandardCharsets.US_ASCII)), "padded BKTR output");
        String rawBase = nativeExtractNca(base, k, t, o, image.toString(), 0, null, true);
        check(rawBase.startsWith("OK|"), "base image extraction: " + rawBase);
        byte[] imageData = Files.readAllBytes(image);
        check(Arrays.equals(Arrays.copyOfRange(imageData, 0x200, 0x210),
                "BASE-EXAMPLE-00\0".getBytes(StandardCharsets.US_ASCII)), "base image content");
        String raw = nativeExtractNca(update, k, t, o, image.toString(), 2, base, true);
        check(raw.startsWith("OK|"), "BKTR image extraction: " + raw);
        imageData = Files.readAllBytes(image);
        check(imageData.length == 0x300, "BKTR image size");
        check(Arrays.equals(Arrays.copyOfRange(imageData, 0x200, 0x210),
                "PATCH-EXAMPLE-0\0".getBytes(StandardCharsets.US_ASCII)), "BKTR image content");
        String rawDlc = nativeExtractNca(dlc, k, t, o, image.toString(), 1, null, true);
        check(rawDlc.startsWith("OK|"), "DLC image extraction: " + rawDlc);
        imageData = Files.readAllBytes(image);
        check(Arrays.equals(Arrays.copyOfRange(imageData, 0x200, 0x210),
                "DLC--EXAMPLE-00\0".getBytes(StandardCharsets.US_ASCII)), "DLC image content");
        check(nativeExtractNca(dlcUnlock, k, t, o, image.toString(), 1, null, true).startsWith("SKIP|"), "unlock-only DLC rejected as data pack");
        System.out.println("NCA classification, tickets, BKTR update and DLC extraction passed");
    }
}
