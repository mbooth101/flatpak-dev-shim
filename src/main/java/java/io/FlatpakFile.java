package java.io;

import java.net.URI;

/**
 * A Flatpak sandbox aware version of {@link java.io.File} that allows clients
 * to check whether the file exists within the sandbox or on the sandbox host.
 */
public class FlatpakFile extends File {

    private static final FileSystem fs = DefaultFileSystem.getFileSystem();

    public FlatpakFile(String pathname) {
        super(pathname);
    }

    public FlatpakFile(String parent, String child) {
        super(parent, child);
    }

    public FlatpakFile(File parent, String child) {
        super(parent, child);
    }

    public FlatpakFile(URI uri) {
        super(uri);
    }

    public boolean existsSandbox() {
        if (isInvalid()) {
            return false;
        }
        return ((fs.getBooleanAttributes(this) & FlatpakFileSystem.BA_SANDBOX) != 0);
    }

    public boolean existsSandboxHost() {
        if (isInvalid()) {
            return false;
        }
        return ((fs.getBooleanAttributes(this) & FlatpakFileSystem.BA_SANDBOXHOST) != 0);
    }
}
