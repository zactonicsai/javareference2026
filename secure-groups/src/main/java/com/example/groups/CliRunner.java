package com.example.groups;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs a command-line tool (helm, kubectl). Arguments are passed as a list, never through a shell
 * on this server, so nothing in them is shell-interpreted here.
 */
@Component
public class CliRunner {
    private static final Logger log = LoggerFactory.getLogger(CliRunner.class);
    private static final int MAX_OUTPUT_BYTES = 200_000;

    /** Outcome of one command; "command" is the readable command line that was executed. */
    public record Result(String command, int exitCode, String stdout, String stderr) {
        public boolean ok() { return exitCode == 0; }
        /** Standard output followed by any error output. */
        public String output() {
            if (stderr.isBlank()) return stdout.strip();
            return stdout.isBlank() ? stderr.strip() : stdout.strip() + "\n" + stderr.strip();
        }
    }

    public static class CliException extends RuntimeException {
        public CliException(String message) { super(message); }
        public CliException(String message, Throwable cause) { super(message, cause); }
    }

    /** The tool did not finish within its time limit. */
    public static class CliTimeoutException extends CliException {
        public CliTimeoutException(String message) { super(message); }
    }

    public Result run(List<String> command, String kubeconfigPath, long timeoutSeconds) {
        String display = String.join(" ", command);
        log.info("Executing: {}", display);
        Path out = null, err = null;
        try {
            // Output goes to files so a hung or chatty process can never block the timeout below.
            out = Files.createTempFile("cli-out-", ".log");
            err = Files.createTempFile("cli-err-", ".log");
            ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());
            if (kubeconfigPath != null && !kubeconfigPath.isBlank())
                builder.environment().put("KUBECONFIG", Path.of(kubeconfigPath).toAbsolutePath().normalize().toString());
            Process process = builder.start();
            process.getOutputStream().close(); // no stdin
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new CliTimeoutException("Timed out after " + timeoutSeconds + "s: " + display);
            }
            return new Result(display, process.exitValue(), readTail(out), readTail(err));
        } catch (IOException e) {
            throw new CliException("Could not run '" + command.get(0) + "' (is it installed and on the PATH?)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException("Interrupted while waiting for: " + display, e);
        } finally {
            for (Path file : new Path[]{out, err})
                if (file != null) try { Files.deleteIfExists(file); } catch (IOException ignored) { }
        }
    }

    /** Reads at most the last MAX_OUTPUT_BYTES of a file, tolerating non-text bytes. */
    private static String readTail(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file)) {
            long size = channel.size(), start = Math.max(0, size - MAX_OUTPUT_BYTES);
            ByteBuffer buffer = ByteBuffer.allocate((int) (size - start));
            channel.position(start);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
            return start > 0 ? "[earlier output truncated]\n" + text : text;
        }
    }
}
