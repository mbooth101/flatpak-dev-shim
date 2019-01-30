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
        if (exe.startsWith(Paths.get("/var/run/host"))) {
            cmdarray[0] = Paths.get("/").resolve(exe.subpath(3, exe.getNameCount())).toString();
            if (Boolean.getBoolean("flatpak.hostcommandrunner.debug")) {
                StringBuilder sb = new StringBuilder("Running on sandbox host: ");
                for (String arg : cmdarray) {
                    sb.append(" " + arg);
                }
                System.err.println(sb.toString());
            }
            return FlatpakProcessImpl.start(cmdarray, environment, dir, redirects, redirectErrStream);
        } else {
            if (Boolean.getBoolean("flatpak.hostcommandrunner.debug")) {
                StringBuilder sb = new StringBuilder("Running in sandbox: ");
                for (String arg : cmdarray) {
                    sb.append(" " + arg);
                }
                System.err.println(sb.toString());
            }
            return ProcessImpl.start(cmdarray, environment, dir, redirects, redirectErrStream);
        }
    }
}
