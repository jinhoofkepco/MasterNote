package android.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** JVM test substitute with the same primary/backup behavior used by Android AtomicFile. */
public final class AtomicFile {
    private final File base;
    private final File backup;

    public AtomicFile(File base) {
        this.base = base;
        this.backup = new File(base.getPath() + ".bak");
    }

    public FileInputStream openRead() throws FileNotFoundException {
        if (backup.exists()) {
            if (base.exists() && !base.delete()) throw new FileNotFoundException("Cannot restore backup");
            if (!backup.renameTo(base)) throw new FileNotFoundException("Cannot restore backup");
        }
        return new FileInputStream(base);
    }

    public FileOutputStream startWrite() throws IOException {
        File parent = base.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create parent");
        if (base.exists()) {
            if (!backup.exists()) {
                if (!base.renameTo(backup)) throw new IOException("Cannot preserve old file");
            } else if (!base.delete()) {
                throw new IOException("Cannot discard incomplete base");
            }
        }
        return new FileOutputStream(base);
    }

    public void finishWrite(FileOutputStream output) {
        try { output.close(); } catch (IOException ignored) {}
        if (backup.exists()) backup.delete();
    }

    public void failWrite(FileOutputStream output) {
        try { output.close(); } catch (IOException ignored) {}
        if (base.exists()) base.delete();
        if (backup.exists()) backup.renameTo(base);
    }
}
