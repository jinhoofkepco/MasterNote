package android.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Host-JVM stand-in; production uses the Android framework implementation. */
public final class AtomicFile {
    private final File baseFile;
    private final File backupFile;

    public AtomicFile(File baseFile) {
        this.baseFile = baseFile;
        this.backupFile = new File(baseFile.getPath() + ".bak");
    }

    public FileOutputStream startWrite() throws IOException {
        File parent = baseFile.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create " + parent);
        }
        if (baseFile.exists()) {
            if (!backupFile.exists() && !baseFile.renameTo(backupFile)) {
                throw new IOException("Unable to back up " + baseFile);
            }
            if (backupFile.exists() && baseFile.exists() && !baseFile.delete()) {
                throw new IOException("Unable to replace " + baseFile);
            }
        }
        return new FileOutputStream(baseFile);
    }

    public void finishWrite(FileOutputStream stream) {
        closeQuietly(stream);
        if (backupFile.exists() && !backupFile.delete()) {
            throw new IllegalStateException("Unable to remove " + backupFile);
        }
    }

    public void failWrite(FileOutputStream stream) {
        closeQuietly(stream);
        if (baseFile.exists() && !baseFile.delete()) {
            throw new IllegalStateException("Unable to remove failed write " + baseFile);
        }
        if (backupFile.exists() && !backupFile.renameTo(baseFile)) {
            throw new IllegalStateException("Unable to restore " + backupFile);
        }
    }

    public FileInputStream openRead() throws FileNotFoundException {
        if (backupFile.exists()) {
            if (baseFile.exists() && !baseFile.delete()) {
                throw new IllegalStateException("Unable to remove incomplete write " + baseFile);
            }
            if (!backupFile.renameTo(baseFile)) {
                throw new IllegalStateException("Unable to restore " + backupFile);
            }
        }
        return new FileInputStream(baseFile);
    }

    public File getBaseFile() { return baseFile; }

    private static void closeQuietly(FileOutputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) {
            // Production code has already fsynced and owns failure handling.
        }
    }
}
