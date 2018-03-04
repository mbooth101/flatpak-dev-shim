package java.lang;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.util.Arrays;
import java.util.Map;

import jdk.internal.misc.JavaIOFileDescriptorAccess;
import jdk.internal.misc.SharedSecrets;

/**
 * A java.lang.Process implementation to break out of the Flatpak sandbox and
 * start processes in the host environment. It does this by communicating with
 * the "Development" interface of the org.freedesktop.Flatpak DBus API.
 */
final class ProcessImpl extends Process {

    static {
        System.loadLibrary("flatpakdevshim");
    }

    private static final JavaIOFileDescriptorAccess fdAccess = SharedSecrets.getJavaIOFileDescriptorAccess();

    private final int pid;
    private final OutputStream stdin;
    private final InputStream stdout;
    private final InputStream stderr;

    private int exitcode;
    private boolean hasExited;

    // Used by native code to keep track of subscription to the signal that will
    // notify us that the process has exited.
    private int subscription;

    private ProcessImpl(byte[][] argv, int argc, byte[][] envv, int envc, byte[] dir, int[] fds) throws IOException {
        pid = execHostCommand(argv, argc, envv, envc, dir, fds);

        // Initialise streams for the process's standard file descriptors
        if (fds[0] == -1) {
            stdin = ProcessBuilder.NullOutputStream.INSTANCE;
        } else {
            stdin = new ProcessPipeOutputStream(fds[0]);
        }
        if (fds[1] == -1) {
            stdout = ProcessBuilder.NullInputStream.INSTANCE;
        } else {
            stdout = new ProcessPipeInputStream(fds[1]);
        }
        if (fds[2] == -1) {
            stderr = ProcessBuilder.NullInputStream.INSTANCE;
        } else {
            stderr = new ProcessPipeInputStream(fds[2]);
        }

        onExit().handle((process, throwable) -> {
            if (stdout instanceof ProcessPipeInputStream) {
                ((ProcessPipeInputStream) stdout).processExited();
            }
            if (stderr instanceof ProcessPipeInputStream) {
                ((ProcessPipeInputStream) stderr).processExited();
            }
            if (stdin instanceof ProcessPipeOutputStream) {
                ((ProcessPipeOutputStream) stdin).processExited();
            }
            return null;
        });
    }

    private native int execHostCommand(byte[][] argv, int argc, byte[][] envv, int envc, byte[] dir, int[] fds)
            throws IOException;

    /**
     * Called by the native code when the process has exited.
     */
    private synchronized void hostCommandExited(int code) {
        hasExited = true;
        exitcode = code;
        notifyAll();
    }

    /**
     * For use only by {@link ProcessBuilder#start()}.
     */
    static Process start(String[] cmdarray, Map<String, String> environment, String dir,
            ProcessBuilder.Redirect[] redirects, boolean redirectErrorStream) throws IOException {

        byte[][] argv = new byte[cmdarray.length][];
        for (int i = 0; i < cmdarray.length; i++) {
            argv[i] = toCString(cmdarray[i]);
        }

        byte[][] envv = null;
        if (environment != null && !environment.isEmpty()) {
            envv = new byte[environment.size() * 2][];
            int i = 0;
            for (Map.Entry<String, String> entry : environment.entrySet()) {
                envv[i++] = toCString(entry.getKey());
                envv[i++] = toCString(entry.getValue());
            }
        }

        // Try to honour ProcessBuilder contract by using user.dir if none is specified
        if (dir == null || dir.isEmpty()) {
            dir = System.getProperty("user.dir");
        }

        FileInputStream f0 = null;
        FileOutputStream f1 = null;
        FileOutputStream f2 = null;

        try {
            // Setup standard file descriptors for process
            int[] fds = { -1, -1, -1 };
            if (redirects != null) {
                // stdin
                if (redirects[0] == Redirect.PIPE) {
                    fds[0] = -1;
                } else if (redirects[0] == Redirect.INHERIT) {
                    fds[0] = 0;
                } else {
                    f0 = new FileInputStream(redirects[0].file());
                    fds[0] = fdAccess.get(f0.getFD());
                }

                // stdout
                if (redirects[1] == Redirect.PIPE) {
                    fds[1] = -1;
                } else if (redirects[1] == Redirect.INHERIT) {
                    fds[1] = 1;
                } else {
                    f1 = new FileOutputStream(redirects[1].file(), redirects[1].append());
                    fds[1] = fdAccess.get(f1.getFD());
                }

                // stderr
                if (redirects[2] == Redirect.PIPE) {
                    fds[2] = -1;
                } else if (redirects[2] == Redirect.INHERIT) {
                    fds[2] = 2;
                } else {
                    f2 = new FileOutputStream(redirects[2].file(), redirects[2].append());
                    fds[2] = fdAccess.get(f2.getFD());
                }
            }
            return new ProcessImpl(argv, argv.length, envv, envv == null ? 0 : envv.length, toCString(dir), fds);
        } finally {

        }
    }

    @Override
    public long pid() {
        return pid;
    }

    @Override
    public boolean supportsNormalTermination() {
        return true;
    }

    @Override
    public void destroy() {
        destroy(false);
    }

    @Override
    public Process destroyForcibly() {
        destroy(true);
        return this;
    }

    private void destroy(boolean force) {
        // TODO send term/kill signal to process depending on force:
        // <method name="HostCommandSignal">
        // <arg type='u' name='pid' direction='in'/>
        // <arg type='u' name='signal' direction='in'/>
        // <arg type='b' name='to_process_group' direction='in'/>
        // </method>
    }

    @Override
    public synchronized int waitFor() throws InterruptedException {
        while (!hasExited) {
            wait();
        }
        return exitcode;
    }

    @Override
    public synchronized int exitValue() {
        if (!hasExited) {
            throw new IllegalThreadStateException("process hasn't exited");
        }
        return exitcode;
    }

    @Override
    public InputStream getErrorStream() {
        return stderr;
    }

    @Override
    public InputStream getInputStream() {
        return stdout;
    }

    @Override
    public OutputStream getOutputStream() {
        return stdin;
    }

    /**
     * Return a string consisting of the native process ID and exit value of the
     * process.
     *
     * @return a string representation of the object.
     */
    @Override
    public String toString() {
        return new StringBuilder("Process[pid=").append(pid).append(", exitValue=")
                .append(hasExited ? exitcode : "\"not exited\"").append("]").toString();
    }

    /**
     * Convert a java string into a C-style null-terminated byte array.
     */
    private static byte[] toCString(String s) {
        if (s == null) {
            return null;
        }
        byte[] bytes = s.getBytes();
        byte[] result = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, result, 0, bytes.length);
        result[bytes.length] = (byte) 0;
        return result;
    }

    /**
     * Creates a new descriptor for the given ID.
     */
    private static FileDescriptor newFileDescriptor(int fd) {
        FileDescriptor descriptor = new FileDescriptor();
        fdAccess.set(descriptor, fd);
        return descriptor;
    }

    /**
     * A buffered input stream for a subprocess pipe file descriptor that allows the
     * underlying file descriptor to be reclaimed when the process exits, via the
     * processExited hook.
     *
     * This is tricky because we do not want the user-level InputStream to be closed
     * until the user invokes close(), and we need to continue to be able to read
     * any buffered data lingering in the OS pipe buffer.
     * 
     * This class is taken more or less as-is from the JDK 9 implementation.
     */
    private static class ProcessPipeInputStream extends BufferedInputStream {
        private final Object closeLock = new Object();

        ProcessPipeInputStream(int fd) {
            super(new FileInputStream(newFileDescriptor(fd)));
        }

        private static byte[] drainInputStream(InputStream in) throws IOException {
            int n = 0;
            int j;
            byte[] a = null;
            while ((j = in.available()) > 0) {
                a = (a == null) ? new byte[j] : Arrays.copyOf(a, n + j);
                n += in.read(a, n, j);
            }
            return (a == null || n == a.length) ? a : Arrays.copyOf(a, n);
        }

        /** Called by the process reaper thread when the process exits. */
        synchronized void processExited() {
            synchronized (closeLock) {
                try {
                    InputStream in = this.in;
                    // this stream is closed if and only if: in == null
                    if (in != null) {
                        byte[] stragglers = drainInputStream(in);
                        in.close();
                        if (stragglers == null) {
                            this.in = ProcessBuilder.NullInputStream.INSTANCE;
                        } else {
                            this.in = new ByteArrayInputStream(stragglers);
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }

        @Override
        public void close() throws IOException {
            // BufferedInputStream#close() is not synchronized unlike most other
            // methods. Synchronizing helps avoid race with processExited().
            synchronized (closeLock) {
                super.close();
            }
        }
    }

    /**
     * A buffered output stream for a subprocess pipe file descriptor that allows
     * the underlying file descriptor to be reclaimed when the process exits, via
     * the processExited hook.
     * 
     * This class is taken more or less as-is from the JDK 9 implementation.
     */
    private static class ProcessPipeOutputStream extends BufferedOutputStream {
        ProcessPipeOutputStream(int fd) {
            super(new FileOutputStream(newFileDescriptor(fd)));
        }

        /** Called by the process reaper thread when the process exits. */
        synchronized void processExited() {
            if (this.out != null) {
                try {
                    this.out.close();
                } catch (IOException ignored) {
                    // We know of no reason to get an IOException, but if
                    // we do, there's nothing else to do but carry on.
                }
                this.out = ProcessBuilder.NullOutputStream.INSTANCE;
            }
        }
    }
}
