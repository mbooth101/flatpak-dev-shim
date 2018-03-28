package java.io;

/**
 * A java.io.FileSystem implementation that extends the standard Unix
 * implementation with the ability to read the attributes of a file on the
 * Flatpak sandbox host.
 */
class FlatpakFileSystem extends UnixFileSystem {

    // Clients may check for this value to determine if a file that exists is
    // available only on the sandbox host
    public static final int BA_SANDBOXHOST = 0x10;

    public int getBooleanAttributes(File f) {
        int rv = getBooleanAttributes0(f);

        if ((rv & BA_EXISTS) == 0) {
            // File doesn't exist in the sandbox, lets check the sandbox host
            File fHost = new File("/run/host", f.toString());
            rv = getBooleanAttributes0(fHost);
            rv = rv | ((rv & BA_EXISTS) != 0 ? BA_SANDBOXHOST : 0);
        }

        String name = f.getName();
        boolean hidden = (name.length() > 0) && (name.charAt(0) == '.');
        return rv | (hidden ? BA_HIDDEN : 0);
    }
}
