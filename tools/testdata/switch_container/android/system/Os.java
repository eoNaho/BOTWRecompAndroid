package android.system;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.IdentityHashMap;
import java.util.Map;
public final class Os {
    private static final Map<FileDescriptor, FileChannel> files = new IdentityHashMap<>();
    public static void register(FileDescriptor fd, FileChannel channel) { files.put(fd, channel); }
    public static void unregister(FileDescriptor fd) { files.remove(fd); }
    public static StructStat fstat(FileDescriptor fd) throws ErrnoException {
        try { StructStat s = new StructStat(); s.st_size = files.get(fd).size(); return s; }
        catch (Exception e) { throw new ErrnoException(e.toString(), 0); }
    }
    public static int pread(FileDescriptor fd, byte[] buf, int start, int length, long offset) throws ErrnoException {
        try { return files.get(fd).read(ByteBuffer.wrap(buf, start, length), offset); }
        catch (Exception e) { throw new ErrnoException(e.toString(), 0); }
    }
}
