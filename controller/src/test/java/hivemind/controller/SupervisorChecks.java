/* HiveMind, LGPL-3.0. */
package hivemind.controller;

import java.nio.file.*;
import java.util.Properties;

public final class SupervisorChecks {
    private static void check(boolean yes, String message) { if (!yes) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("child")) {
            Path marker = Path.of(args[1]);
            if (!Files.exists(marker)) { Files.writeString(marker, "crashed"); System.exit(7); }
            return;
        }
        Path root = Files.createTempDirectory("hivemind-supervisor"), instance = Files.createDirectory(root.resolve("instance"));
        Path marker = root.resolve("child-marker"), file = root.resolve("profile.properties");
        Properties p = new Properties();
        p.setProperty("workdir", root.toString()); p.setProperty("instance", instance.toString());
        p.setProperty("arg.0", Path.of(System.getProperty("java.home"), "bin", "java").toString());
        p.setProperty("arg.1", "-cp");
        p.setProperty("arg.2", System.getProperty("java.class.path").replace("build/controller-classes",Path.of("build/controller-classes").toAbsolutePath().toString()));
        p.setProperty("arg.3", SupervisorChecks.class.getName()); p.setProperty("arg.4", "child"); p.setProperty("arg.5", marker.toString()); p.setProperty("restartDelaySeconds", "1");
        try (var out = Files.newOutputStream(file)) { p.store(out, "test"); }
        check(ClientSupervisor.delaySeconds(15, 1) == 15 && ClientSupervisor.delaySeconds(15, 20) == 300, "Capped backoff");
        check(ClientSupervisor.read(file).command().size() == 6, "Explicit foreground launch profile");
        check(ClientSupervisor.health(1000,0,2000,false,120,300)==ClientSupervisor.Health.STARTING,"Missing heartbeat gets startup grace");
        check(ClientSupervisor.health(1000,0,400000,false,120,300)==ClientSupervisor.Health.FROZEN,"A live process with no tick heartbeat eventually fails");
        check(ClientSupervisor.health(1000,2000,3000,false,120,300)==ClientSupervisor.Health.HEALTHY,"Fresh tick heartbeat is healthy");
        check(ClientSupervisor.health(1000,2000,400000,true,120,300)==ClientSupervisor.Health.HELD,"Deliberate hold suppresses automatic recovery");
        ClientSupervisor.run(file);
        check(Files.readString(marker).equals("crashed"), "A nonzero exit restarted; zero exit stopped");
        p.remove("arg.2");
        try (var out = Files.newOutputStream(file)) { p.store(out, "invalid"); }
        try { ClientSupervisor.read(file); throw new AssertionError("Accepted incomplete command"); }
        catch (java.io.IOException expected) { /* fail closed */ }
        System.out.println("Local Minecraft supervisor passed");
    }
}
