package java.lang;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * A shim that sits between {@link java.lang.ProcessBuilder} and
 * {@link java.lang.Process} that will start processes on the sandbox host using
 * the Flatpak shim if the client tries to execute programs that reside on the
 * sandbox host mount point.
 */
class ProcessImplFactory {

    /**
     * For use only by {@link ProcessBuilder#start()}.
     */
    static Process start(String[] cmdarray, Map<String, String> environment, String dir,
            ProcessBuilder.Redirect[] redirects, boolean redirectErrStream) throws IOException {

        // If the desired executable program lives in /run/host (where the sandbox host
        // is mounted) then execute it on the sandbox host, otherwise execute normally
        Path exe = Paths.get(cmdarray[0]);
        if (exe.startsWith(Paths.get("/run/host"))) {
            cmdarray[0] = Paths.get("/").resolve(exe.subpath(2, exe.getNameCount())).toString();
            System.out.println("Running on sandbox host: " + cmdarray[0]);
            return FlatpakProcessImpl.start(cmdarray, environment, dir, redirects, redirectErrStream);
        } else {
            System.out.println("Running in sandbox: " + cmdarray[0]);
            return ProcessImpl.start(cmdarray, environment, dir, redirects, redirectErrStream);
        }
    }
}
