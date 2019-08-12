/*********************************************************************
 * Copyright (c) 2018, 2019 Red Hat, Inc. and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 **********************************************************************/
package java.lang;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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

        Path exe = Paths.get(cmdarray[0]);
        if (exe.startsWith(Paths.get("/var/run/host"))) {
            // If the desired executable program lives in /var/run/host (where the sandbox
            // host is mounted) then execute it on the sandbox host
            cmdarray[0] = Paths.get("/").resolve(exe.subpath(3, exe.getNameCount())).toString();
            return runOnHost(cmdarray, environment, dir, redirects, redirectErrStream);
        } else {
            boolean inSandbox = detectExecutablePresence(true, cmdarray, environment, dir);
            if (inSandbox) {
                // If the desired executable program exists in the sandbox, then run normally
                return runInSandbox(cmdarray, environment, dir, redirects, redirectErrStream);
            } else {
                boolean onHost = detectExecutablePresence(false, cmdarray, environment, dir);
                if (onHost) {
                    // If the desired executable program does not exist in the sandbox, then execute
                    // it on the sandbox host
                    return runOnHost(cmdarray, environment, dir, redirects, redirectErrStream);
                } else {
                    throw new IOException("No such file or directory");
                }
            }
        }
    }

    private static boolean detectExecutablePresence(boolean sandbox, String[] cmdarray, Map<String, String> environment,
            String dir) throws IOException {
        String[] whichCommand = new String[] { "which", cmdarray[0] };
        ProcessBuilder.Redirect[] redirects = new ProcessBuilder.Redirect[] { ProcessBuilder.Redirect.PIPE,
                ProcessBuilder.Redirect.PIPE, ProcessBuilder.Redirect.PIPE };
        Process which;
        if (sandbox) {
            which = runInSandbox(whichCommand, environment, dir, redirects, false);
        } else {
            which = runOnHost(whichCommand, environment, dir, redirects, false);
        }
        try {
            int exit = which.waitFor();
            if (exit == 0) {
                return true;
            } else {
                return false;
            }
        } catch (InterruptedException e) {
            throw new IOException("Unable to determine location of executable");
        }
    }

    private static Process runInSandbox(String[] cmdarray, Map<String, String> environment, String dir,
            ProcessBuilder.Redirect[] redirects, boolean redirectErrStream) throws IOException {
        if (Boolean.getBoolean("flatpak.hostcommandrunner.debug")) {
            StringBuilder sb = new StringBuilder("Running in sandbox:");
            for (String arg : cmdarray) {
                sb.append(" " + arg);
            }
            System.err.println(sb.toString());
        }
        return ProcessImpl.start(cmdarray, environment, dir, redirects, redirectErrStream);
    }

    private static Process runOnHost(String[] cmdarray, Map<String, String> environment, String dir,
            ProcessBuilder.Redirect[] redirects, boolean redirectErrStream) throws IOException {
        if (Boolean.getBoolean("flatpak.hostcommandrunner.debug")) {
            StringBuilder sb = new StringBuilder("Running on sandbox host:");
            for (String arg : cmdarray) {
                sb.append(" " + arg);
            }
            System.err.println(sb.toString());
        }
        return FlatpakProcessImpl.start(cmdarray, environment, dir, redirects, redirectErrStream);
    }
}
