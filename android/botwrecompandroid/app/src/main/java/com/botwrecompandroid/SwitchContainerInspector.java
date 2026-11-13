package com.botwrecompandroid;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;

import java.io.FileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Read-only PFS0/HFS0 directory inspection through a seekable SAF file descriptor. */
final class SwitchContainerInspector {
    private static final int MAX_ENTRIES = 1_000_000;
    private static final int MAX_TABLE_BYTES = 64 * 1024 * 1024;
    private static final int DISPLAY_LIMIT = 100;

    private static final class Entry {
        final String name;
        final long offset, size;

        Entry(String name, long offset, long size) {
            this.name = name;
            this.offset = offset;
            this.size = size;
        }
    }

    private static final class Fs {
        final List<Entry> entries;
        final long dataAt;

        Fs(List<Entry> entries, long dataAt) {
            this.entries = entries;
            this.dataAt = dataAt;
        }
    }

    private SwitchContainerInspector() {}

    static String inspect(ParcelFileDescriptor descriptor) throws IOException {
        final FileDescriptor fd = descriptor.getFileDescriptor();
        final long fileSize;
        try {
            fileSize = Os.fstat(fd).st_size;
        } catch (ErrnoException e) {
            throw new IOException("Could not read the selected file: " + e.getMessage(), e);
        }
        if (fileSize < 0x10) throw new IOException("The selected file is too small to contain a PFS0/HFS0 directory.");

        byte[] magic = readAt(fd, 0, 4, fileSize, "container magic");
        if (isMagic(magic, "PFS0")) {
            Fs pfs = readFs(fd, 0, fileSize, "PFS0");
            StringBuilder out = new StringBuilder("NSP · PFS0\n");
            appendEntries(out, pfs.entries, 0);
            return out.toString();
        }

        if (fileSize < 0x140 || !isMagic(readAt(fd, 0x100, 4, fileSize, "XCI magic"), "HEAD"))
            throw new IOException("Unrecognized file. Expected an NSP (PFS0) or XCI (HEAD) container.");

        ByteBuffer locator = little(readAt(fd, 0x130, 16, fileSize, "XCI partition locator"));
        long hfsOffset = locator.getLong();
        long hfsHeaderSize = locator.getLong();
        if (hfsOffset < 0 || hfsHeaderSize < 0 || hfsHeaderSize < 0x10 || !within(hfsOffset, hfsHeaderSize, fileSize))
            throw new IOException("The XCI root HFS0 header has invalid bounds.");

        Fs root = readFs(fd, hfsOffset, fileSize, "HFS0");
        if (root.dataAt > hfsOffset + hfsHeaderSize)
            throw new IOException("The XCI root HFS0 tables exceed the declared header size.");

        StringBuilder out = new StringBuilder("XCI · HFS0\n");
        appendEntries(out, root.entries, 0);
        int nested = 0;
        for (Entry partition : root.entries) {
            if (!(partition.name.equals("secure") || partition.name.equals("update") || partition.name.equals("normal"))) continue;
            if (partition.size < 0x10) continue;
            try {
                Fs child = readFs(fd, partition.offset, partition.offset + partition.size, "HFS0");
                out.append("\n[").append(partition.name).append(" partition]\n");
                appendEntries(out, child.entries, 0);
                nested++;
            } catch (IOException e) {
                out.append("\n[").append(partition.name).append(" partition: ").append(e.getMessage()).append("]\n");
            }
        }
        if (nested == 0) out.append("\nNo readable secure, update, or normal partition directory was found.\n");
        return out.toString();
    }

    interface NcaProcessor {
        /** Return true to stop after this NCA. The file is deleted as soon as this callback returns. */
        boolean process(File nca) throws IOException;
    }

    interface NcaFilter {
        boolean accept(File header) throws IOException;
    }

    interface CopyProgress {
        void update(long done, long total);
    }

    /** Stream tickets first, then one NCA at a time; the complete NSP/XCI is never staged. */
    static void processNcas(ParcelFileDescriptor descriptor, File outputDir, NcaFilter filter,
                            NcaProcessor processor, CopyProgress progress) throws IOException {
        final FileDescriptor fd = descriptor.getFileDescriptor();
        final long fileSize;
        try {
            fileSize = Os.fstat(fd).st_size;
        } catch (ErrnoException e) {
            throw new IOException("Could not read the selected file: " + e.getMessage(), e);
        }
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) throw new IOException("Could not create import working folder.");
        byte[] magic = readAt(fd, 0, 4, fileSize, "container magic");
        List<Entry> entries;
        if (isMagic(magic, "PFS0")) {
            entries = readFs(fd, 0, fileSize, "PFS0").entries;
        } else {
            if (fileSize < 0x140 || !isMagic(readAt(fd, 0x100, 4, fileSize, "XCI magic"), "HEAD"))
                throw new IOException("Unrecognized file. Expected an NSP (PFS0) or XCI (HEAD) container.");
            ByteBuffer locator = little(readAt(fd, 0x130, 16, fileSize, "XCI partition locator"));
            long hfsOffset = locator.getLong(), hfsHeaderSize = locator.getLong();
            if (hfsOffset < 0 || hfsHeaderSize < 0 || hfsHeaderSize < 0x10 || !within(hfsOffset, hfsHeaderSize, fileSize))
                throw new IOException("The XCI root HFS0 header has invalid bounds.");
            Fs root = readFs(fd, hfsOffset, fileSize, "HFS0");
            if (root.dataAt > hfsOffset + hfsHeaderSize) throw new IOException("The XCI root HFS0 tables exceed the declared header size.");
            Entry secure = null;
            for (Entry e : root.entries) if (e.name.equals("secure")) secure = e;
            if (secure == null) throw new IOException("The XCI has no secure partition.");
            entries = readFs(fd, secure.offset, secure.offset + secure.size, "HFS0").entries;
        }

        for (Entry entry : entries) {
            if (!entry.name.toLowerCase(java.util.Locale.ROOT).endsWith(".tik")) continue;
            copyEntry(fd, fileSize, outputDir, entry, null);
        }
        int ncaCount = 0;
        for (Entry entry : entries) {
            if (!entry.name.toLowerCase(java.util.Locale.ROOT).endsWith(".nca")) continue;
            ncaCount++;
            File header = new File(outputDir, ".nca-header");
            boolean selected;
            try {
                try (OutputStream out = new FileOutputStream(header)) {
                    out.write(readAt(fd, entry.offset, (int)Math.min(entry.size, 0xc00), fileSize, "NCA header"));
                }
                selected = filter.accept(header);
            } finally {
                if (header.exists() && !header.delete()) throw new IOException("Could not remove temporary NCA header.");
            }
            if (!selected) continue;
            File nca = copyEntry(fd, fileSize, outputDir, entry, progress);
            boolean stop;
            try {
                stop = processor.process(nca);
            } finally {
                if (nca.exists() && !nca.delete()) throw new IOException("Could not remove temporary NCA " + nca.getName());
            }
            if (stop) return;
        }
        if (ncaCount == 0) throw new IOException("No NCA files were found in the selected archive.");
    }

    private static File copyEntry(FileDescriptor fd, long fileSize, File outputDir, Entry entry, CopyProgress progress) throws IOException {
        if (entry.size > outputDir.getUsableSpace())
            throw new IOException("Not enough free space to read " + entry.name + " from the archive.");
        File out = new File(outputDir, entry.name);
        byte[] buffer = new byte[1 << 20];
        try (OutputStream stream = new FileOutputStream(out)) {
            long remaining = entry.size, offset = entry.offset;
            long reported = 0;
            if (progress != null) progress.update(0, entry.size);
            while (remaining > 0) {
                int length = (int)Math.min(buffer.length, remaining);
                readAt(fd, offset, buffer, length, fileSize, entry.name);
                stream.write(buffer, 0, length);
                offset += length;
                remaining -= length;
                long done = entry.size - remaining;
                if (progress != null && (done - reported >= (16L << 20) || remaining == 0)) {
                    progress.update(done, entry.size);
                    reported = done;
                }
            }
        } catch (IOException e) {
            if (out.exists()) out.delete();
            throw e;
        }
        return out;
    }

    private static Fs readFs(FileDescriptor fd, long start, long limit, String expected) throws IOException {
        if (start < 0 || limit < start) throw new IOException("Filesystem bounds are invalid.");
        byte[] header = readAt(fd, start, 16, limit, "filesystem header");
        ByteBuffer h = little(header);
        byte[] magic = new byte[4];
        h.get(magic);
        if (!isMagic(magic, expected)) throw new IOException("Expected " + expected + " at offset 0x" + Long.toHexString(start) + ".");
        long count = Integer.toUnsignedLong(h.getInt());
        long stringSize = Integer.toUnsignedLong(h.getInt());
        h.getInt(); // reserved
        if (count > MAX_ENTRIES) throw new IOException("The directory has an unreasonable number of entries.");
        int entrySize = expected.equals("PFS0") ? 0x18 : 0x40;
        long tableSize;
        try {
            tableSize = Math.multiplyExact(count, entrySize);
        } catch (ArithmeticException e) {
            throw new IOException("The directory entry table is too large.", e);
        }
        if (tableSize > MAX_TABLE_BYTES || stringSize > MAX_TABLE_BYTES)
            throw new IOException("The directory tables are unexpectedly large.");
        long stringsAt = start + 0x10 + tableSize;
        long dataAt = stringsAt + stringSize;
        if (stringsAt < start || dataAt < stringsAt || dataAt > limit)
            throw new IOException("The directory table or name table exceeds its bounds.");
        byte[] entriesData = readAt(fd, start + 0x10, (int)tableSize, limit, "entry table");
        byte[] names = readAt(fd, stringsAt, (int)stringSize, limit, "name table");
        ByteBuffer rows = little(entriesData);
        List<Entry> entries = new ArrayList<>((int)count);
        for (int i = 0; i < count; i++) {
            long relative = rows.getLong();
            long size = rows.getLong();
            long nameOffset;
            if (expected.equals("PFS0")) {
                nameOffset = Integer.toUnsignedLong(rows.getInt());
                rows.getInt();
            } else {
                nameOffset = Integer.toUnsignedLong(rows.getInt());
                long hashedSize = Integer.toUnsignedLong(rows.getInt());
                rows.getLong(); // reserved
                rows.position(rows.position() + 32); // SHA-256 digest
                if (hashedSize > size)
                    throw new IOException("An HFS0 entry has an invalid hashed region.");
            }
            if (relative < 0 || size < 0 || relative > limit - dataAt || size > limit - dataAt - relative)
                throw new IOException("An entry extends beyond its filesystem bounds.");
            String name = nameAt(names, nameOffset, i);
            entries.add(new Entry(name, dataAt + relative, size));
        }
        return new Fs(entries, dataAt);
    }

    private static String nameAt(byte[] names, long offset, int index) throws IOException {
        if (offset < 0 || offset >= names.length) throw new IOException("Entry " + index + " has an invalid name offset.");
        int end = (int)offset;
        while (end < names.length && names[end] != 0) end++;
        if (end == names.length) throw new IOException("Entry " + index + " has an unterminated name.");
        String name;
        try {
            name = new String(names, (int)offset, end - (int)offset, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IOException("UTF-8 is unavailable.", impossible);
        }
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0)
            throw new IOException("Entry " + index + " has an invalid name.");
        return name;
    }

    private static void appendEntries(StringBuilder out, List<Entry> entries, int indent) {
        int shown = Math.min(entries.size(), DISPLAY_LIMIT);
        String prefix = indent == 0 ? "" : "  ";
        for (int i = 0; i < shown; i++) {
            Entry entry = entries.get(i);
            out.append(prefix).append(entry.name).append("  ·  ").append(entry.size).append(" bytes  ·  0x")
                    .append(Long.toHexString(entry.offset)).append('\n');
        }
        if (entries.size() > shown) out.append("… and ").append(entries.size() - shown).append(" more entries\n");
        if (entries.isEmpty()) out.append("(empty directory)\n");
    }

    private static byte[] readAt(FileDescriptor fd, long offset, int size, long limit, String what) throws IOException {
        byte[] result = new byte[size];
        readAt(fd, offset, result, size, limit, what);
        return result;
    }

    private static void readAt(FileDescriptor fd, long offset, byte[] result, int size, long limit, String what) throws IOException {
        if (!within(offset, size, limit)) throw new IOException("The " + what + " extends beyond the file.");
        int done = 0;
        try {
            while (done < size) {
                int n = Os.pread(fd, result, done, size - done, offset + done);
                if (n <= 0) throw new IOException("Could not read the " + what + ".");
                done += n;
            }
        } catch (ErrnoException e) {
            if (e.errno == android.system.OsConstants.ESPIPE)
                throw new IOException("This file provider doesn't support random access. Copy the NSP/XCI to local storage and select it there.", e);
            throw new IOException("Could not read the " + what + ": " + e.getMessage(), e);
        }
    }

    private static boolean within(long offset, long size, long limit) {
        return offset >= 0 && size >= 0 && offset <= limit && size <= limit - offset;
    }

    private static ByteBuffer little(byte[] data) { return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); }
    private static boolean isMagic(byte[] data, String expected) {
        if (data.length < 4) return false;
        for (int i = 0; i < 4; i++) if (data[i] != (byte)expected.charAt(i)) return false;
        return true;
    }
}
