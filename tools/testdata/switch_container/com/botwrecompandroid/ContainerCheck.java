package com.botwrecompandroid;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class ContainerCheck {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static byte[] fs(boolean hfs, String[] names, byte[][] contents) throws Exception {
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        int[] nameOffsets = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            nameOffsets[i] = strings.size(); strings.write(names[i].getBytes(StandardCharsets.UTF_8)); strings.write(0);
        }
        int size = 16 + names.length * (hfs ? 64 : 24) + strings.size();
        for (byte[] content : contents) size += content.length;
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.put((hfs ? "HFS0" : "PFS0").getBytes(StandardCharsets.US_ASCII));
        b.putInt(names.length).putInt(strings.size()).putInt(0);
        long at = 0;
        for (int i = 0; i < names.length; i++) {
            b.putLong(at).putLong(contents[i].length).putInt(nameOffsets[i]);
            if (hfs) b.putInt(0).putLong(0).put(new byte[32]); else b.putInt(0);
            at += contents[i].length;
        }
        b.put(strings.toByteArray());
        for (byte[] content : contents) b.put(content);
        return b.array();
    }
    static void run(Path dir, byte[] container, String label) throws Exception {
        Path path = dir.resolve(label + ".bin"), output = dir.resolve(label + "-work");
        Files.write(path, container);
        final int[] headers = {0}, selected = {0}, callbacks = {0};
        final long[] lastDone = {0}, lastTotal = {0};
        try (ParcelFileDescriptor fd = new ParcelFileDescriptor(path)) {
            String report = SwitchContainerInspector.inspect(fd);
            check(report.contains("selected.nca"), label + ": directory listing");
            final int[] probed = {0};
            SwitchContainerInspector.processNcas(fd, output.toFile(), header -> { probed[0]++; return false; },
                    nca -> { throw new AssertionError("Preflight should not copy an NCA"); },
                    (done, total) -> { throw new AssertionError("Preflight should not report NCA copy progress"); });
            check(probed[0] == 2 && !Files.exists(output.resolve("selected.nca")), label + ": header-only preflight");
            SwitchContainerInspector.processNcas(fd, output.toFile(), header -> {
                headers[0]++;
                byte[] data = Files.readAllBytes(header.toPath());
                check(data.length == 0xc00, label + ": only the NCA header is staged");
                return data[0] == 'B';
            }, nca -> {
                selected[0]++;
                check(nca.getName().equals("selected.nca"), label + ": selected NCA name");
                check(nca.length() == (3 << 20), label + ": full selected NCA copied");
                return false;
            }, (done, total) -> { callbacks[0]++; lastDone[0] = done; lastTotal[0] = total; });
        }
        check(headers[0] == 2 && selected[0] == 1, label + ": filter selects only requested NCA");
        check(callbacks[0] >= 2 && lastDone[0] == (3 << 20) && lastDone[0] == lastTotal[0], label + ": copy progress");
        check(!Files.exists(output.resolve("ignored.nca")) && !Files.exists(output.resolve("selected.nca")) &&
              !Files.exists(output.resolve(".nca-header")), label + ": temporary NCAs removed");
        check(Files.exists(output.resolve("ticket.tik")), label + ": ticket staged");
    }
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        byte[] ignored = new byte[2 << 20], selected = new byte[3 << 20];
        Arrays.fill(ignored, (byte)'S'); Arrays.fill(selected, (byte)'B');
        String[] names = {"ignored.nca", "selected.nca", "ticket.tik"};
        byte[][] files = {ignored, selected, new byte[]{1, 2, 3}};
        run(dir, fs(false, names, files), "nsp");
        byte[] secure = fs(true, names, files);
        byte[] root = fs(true, new String[]{"secure"}, new byte[][]{secure});
        byte[] xci = new byte[0x200 + root.length];
        System.arraycopy("HEAD".getBytes(StandardCharsets.US_ASCII), 0, xci, 0x100, 4);
        ByteBuffer.wrap(xci).order(ByteOrder.LITTLE_ENDIAN).putLong(0x130, 0x200).putLong(0x138, 16 + 64 + 7);
        System.arraycopy(root, 0, xci, 0x200, root.length);
        run(dir, xci, "xci");
        System.out.println("NSP/XCI directories, header filtering, cleanup and copy progress passed");
    }
}
