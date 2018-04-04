package java.lang;

import java.io.FlatpakFile;
import java.io.IOException;
import java.util.Map;

/**
 * A shim that sits between {@link java.lang.ProcessBuilder} and
 * {@link java.lang.Process} that will start processes on the sandbox host if
 * the client tries to execute programs that do not exist inside the Flatpak
 * sandbox.
 */
class ProcessImplFactory {

    /**
     * For use only by {@link ProcessBuilder#start()}.
     */
    static Process start(String[] cmdarray, Map<String, String> environment, String dir,
            ProcessBuilder.Redirect[] redirects, boolean redirectErrStream) throws IOException {

        // If the desired executable program exists only on the sandbox host, then
        // execute it on the sandbox host instead, otherwise execute it normally
        FlatpakFile exe = new FlatpakFile(cmdarray[0]);
        if (exe.existsSandboxHost() && !exe.existsSandbox()) {
            System.out.println("Running on sandbox host: " + exe);
            return FlatpakProcessImpl.start(cmdarray, environment, dir, redirects, redirectErrStream);
        } else {
            System.out.println("Running in sandbox: " + exe);
            return ProcessImpl.start(cmdarray, environment, dir, redirects, redirectErrStream);
        }
    }
}
