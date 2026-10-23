/* HiveMind, LGPL-3.0. */
package hivemind.controller;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Runs a single explicitly configured foreground Minecraft process on the same machine. */
public final class ClientSupervisor {
    public record Profile(Path workdir, Path instance, List<String> command, int initialDelaySeconds) {
        public Profile { command = List.copyOf(command); }
    }

    private ClientSupervisor() {}

    public static Profile read(Path file) throws IOException {
        Properties p = new Properties();
        try (var input = Files.newInputStream(file)) { p.load(input); }
        Path base = file.toAbsolutePath().getParent();
        Path workdir = base.resolve(required(p, "workdir")).normalize().toRealPath();
        Path instance = base.resolve(required(p, "instance")).normalize().toRealPath();
        if (!Files.isDirectory(workdir) || !Files.isDirectory(instance)) throw new IOException("workdir and instance must be directories");
        List<String> command = new ArrayList<>();
        for (int i = 0; i < 64 && p.containsKey("arg." + i); i++) command.add(required(p, "arg." + i));
        if (command.isEmpty() || p.stringPropertyNames().stream().anyMatch(key -> key.matches("arg\\.[0-9]+")
                && !commandIndex(key, command.size()))) throw new IOException("Use contiguous arg.0, arg.1, ... for a foreground launch command");
        int delay;
        try { delay = Integer.parseInt(p.getProperty("restartDelaySeconds", "15")); }
        catch (NumberFormatException error) { throw new IOException("Invalid restartDelaySeconds", error); }
        if (delay < 1 || delay > 300) throw new IOException("restartDelaySeconds must be 1–300");
        return new Profile(workdir, instance, command, delay);
    }

    private static boolean commandIndex(String key, int size) {
        try { return Integer.parseInt(key.substring(4)) < size; }
        catch (NumberFormatException error) { return false; }
    }
    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0) throw new IOException("Missing/invalid " + key);
        return value;
    }

    public static int delaySeconds(int initial, int crashes) {
        long delay = initial;
        for (int i = 1; i < crashes && delay < 300; i++) delay = Math.min(300, delay * 2);
        return (int) delay;
    }

    public static void run(Path file) throws Exception {
        Profile profile = read(file);
        System.out.println("HiveMind local supervisor: " + profile.instance() + " · " + profile.command().get(0));
        int crashes = 0;
        while (!Thread.currentThread().isInterrupted()) {
            Path updatePlan = profile.instance().resolve("hivemind/updates/install.properties");
            while (Files.exists(updatePlan)) {
                System.out.println("Waiting for client update installer: " + updatePlan);
                TimeUnit.SECONDS.sleep(5);
            }
            Process child = new ProcessBuilder(profile.command()).directory(profile.workdir().toFile()).inheritIO().start();
            long started = System.nanoTime();
            int code;
            try { code = child.waitFor(); }
            catch (InterruptedException interrupted) { child.destroy(); Thread.currentThread().interrupt(); return; }
            if (code == 0) { System.out.println("Minecraft launcher exited normally; supervisor stopped."); return; }
            if (TimeUnit.NANOSECONDS.toMinutes(System.nanoTime() - started) >= 10) crashes = 0;
            int delay = delaySeconds(profile.initialDelaySeconds(), ++crashes);
            System.err.println("Minecraft exited with " + code + "; retrying in " + delay + " seconds");
            TimeUnit.SECONDS.sleep(delay);
        }
    }
}
