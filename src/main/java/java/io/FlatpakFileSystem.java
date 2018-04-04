package java.io;

/**
 * A {@link java.io.FileSystem} implementation that extends the standard Unix
 * implementation with the ability to read the attributes of a file on the
 * Flatpak sandbox host.
 */
class FlatpakFileSystem extends UnixFileSystem {

    // Clients may check for these values to determine if a file that exists is
    // available inside the sandbox, or on the sandbox host, or both
    public static final int BA_SANDBOX = 0x10;
    public static final int BA_SANDBOXHOST = 0x20;

    public int getBooleanAttributes(File file) {
        int rv = getBooleanAttributes0(file);
        rv = rv | ((rv & BA_EXISTS) != 0 ? BA_SANDBOX : 0);

        // Crude check to see if the given file exists on the sandbox host
        File fileHost = new File("/run/host", file.toString());
        int rvHost = getBooleanAttributes0(fileHost);
        rvHost = rvHost | ((rvHost & BA_EXISTS) != 0 ? BA_SANDBOXHOST : 0);

        String name = file.getName();
        boolean hidden = (name.length() > 0) && (name.charAt(0) == '.');
        return rv | rvHost | (hidden ? BA_HIDDEN : 0);
    }
}
