/*********************************************************************
 * Copyright (c) 2018, 2019 Red Hat, Inc. and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 **********************************************************************/
package uk.co.matbooth.flatpak;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

@TestInstance(Lifecycle.PER_METHOD)
public class ProcessImplTests {

    private List<String> outLines = new ArrayList<>();
    private List<String> errLines = new ArrayList<>();

    @Test
    public void runInSandbox() throws IOException, InterruptedException {
        int rc1 = readThenWait(false, "ls", "-l", "/");
        Assertions.assertEquals(0, rc1);
        int rc2 = readThenWait(false, "/usr/bin/ls", "-l", "/");
        Assertions.assertEquals(0, rc2);
    }

    @Test
    public void runOnHost() throws IOException, InterruptedException {
        int rc = readThenWait(false, "/var/run/host/usr/bin/ls", "-l", "/");
        Assertions.assertEquals(0, rc);
    }

    @Test
    public void runOnHostDueToNotFoundInSandbox() throws IOException, InterruptedException {
        try {
            readThenWait(false, "no_such_exe");
            Assertions.fail("An IOException was expected");
        } catch (IOException e) {
            System.out.println("Exception: " + e.getMessage());
            Assertions.assertTrue(e.getMessage().contains("No such file or directory"));
        }
    }

    @Test
    public void runOnHostWithoutErrRedirection() throws IOException, InterruptedException {
        int rc = readThenWait(false, "/var/run/host/bin/sh", "-c",
                "echo 'starting...' && sleep 1 && echo 'oh no' 1>&2 && sleep 1 && echo done && exit 42");
        Assertions.assertEquals(42, rc);
        List<String> expected = new ArrayList<>();
        expected.add("starting...");
        expected.add("done");
        Assertions.assertLinesMatch(expected, outLines);
        List<String> expected2 = new ArrayList<>();
        expected2.add("oh no");
        Assertions.assertLinesMatch(expected2, errLines);
    }

    @Test
    public void runOnHostWithErrRedirection() throws IOException, InterruptedException {
        int rc = readThenWait(true, "/var/run/host/bin/sh", "-c",
                "echo 'starting...' && sleep 1 && echo 'oh no' 1>&2 && sleep 1 && echo done && exit 42");
        Assertions.assertEquals(42, rc);
        List<String> expected = new ArrayList<>();
        expected.add("starting...");
        expected.add("oh no");
        expected.add("done");
        Assertions.assertLinesMatch(expected, outLines);
    }

    private int readThenWait(boolean redirectErr, String... args) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(redirectErr);
        Process p = pb.start();
        BufferedReader outReader = new BufferedReader(new InputStreamReader(p.getInputStream()));
        BufferedReader errReader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
        String line = null;
        while ((line = outReader.readLine()) != null) {
            System.out.println("Read from process stdout: \"" + line + "\"");
            outLines.add(line);
        }
        while ((line = errReader.readLine()) != null) {
            System.err.println("Read from process stderr: \"" + line + "\"");
            errLines.add(line);
        }
        errReader.close();
        outReader.close();
        int exit = p.waitFor();
        System.out.println("Process exited with " + exit);
        return exit;
    }
}
