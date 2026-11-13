package android.os;
import android.system.Os;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
public final class ParcelFileDescriptor implements Closeable {
    private final RandomAccessFile file;
    private final FileDescriptor fd;
    public ParcelFileDescriptor(Path path) throws IOException {
        file = new RandomAccessFile(path.toFile(), "r"); fd = file.getFD(); Os.register(fd, file.getChannel());
    }
    public FileDescriptor getFileDescriptor() { return fd; }
    public void close() throws IOException { Os.unregister(fd); file.close(); }
}
