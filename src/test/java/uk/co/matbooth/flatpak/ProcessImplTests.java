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

    private List<String> lines = new ArrayList<>();

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
    public void runOnHostWithoutErrRedirection() throws IOException, InterruptedException {
        int rc = readThenWait(false, "/var/run/host/bin/sh", "-c",
                "echo 'starting...' && sleep 1 && echo 'oh no' 1>&2 && sleep 1 && echo done && exit 42");
        Assertions.assertEquals(42, rc);
        List<String> expected = new ArrayList<>();
        expected.add("starting...");
        expected.add("done");
        Assertions.assertLinesMatch(expected, lines);
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
        Assertions.assertLinesMatch(expected, lines);
    }

    private int readThenWait(boolean redirectErr, String... args) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(redirectErr);
        Process p = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line = null;
        while ((line = reader.readLine()) != null) {
            System.out.println("Read from process: \"" + line + "\"");
            lines.add(line);
        }
        reader.close();
        int exit = p.waitFor();
        System.out.println("Process exited with " + exit);
        return exit;
    }
}
