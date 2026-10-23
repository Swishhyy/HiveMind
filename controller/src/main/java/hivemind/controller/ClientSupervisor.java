/* HiveMind, LGPL-3.0. */
package hivemind.controller;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Runs a single explicitly configured foreground Minecraft process on the same machine. */
public final class ClientSupervisor {
    public record Profile(Path workdir, Path instance, List<String> command, int initialDelaySeconds,int watchdogSeconds,int startupGraceSeconds,int maxRestarts) {
        public Profile { command = List.copyOf(command); }
        public Profile(Path workdir,Path instance,List<String> command,int delay){this(workdir,instance,command,delay,120,300,5);}
    }
    public enum Health { STARTING, HEALTHY, FROZEN, HELD }
    public static Health health(long started,long heartbeat,long now,boolean held,int timeoutSeconds,int graceSeconds){
        if(heartbeat<started)return now-started<graceSeconds*1000L?Health.STARTING:Health.FROZEN;
        if(held)return Health.HELD;
        return now-heartbeat<=timeoutSeconds*1000L?Health.HEALTHY:Health.FROZEN;
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
        int watchdog=number(p,"watchdogSeconds",120,10,3600),grace=number(p,"startupGraceSeconds",300,10,3600),limit=number(p,"maxRestarts",5,1,100);
        return new Profile(workdir, instance, command, delay,watchdog,grace,limit);
    }
    private static int number(Properties p,String key,int fallback,int min,int max)throws IOException{try{int value=Integer.parseInt(p.getProperty(key,""+fallback));if(value<min||value>max)throw new NumberFormatException();return value;}catch(NumberFormatException error){throw new IOException(key+" must be "+min+"–"+max,error);}}

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
            long installerWait=System.currentTimeMillis();
            while (Files.exists(updatePlan)) {
                if(System.currentTimeMillis()-installerWait>300000)throw new IOException("Update installer did not finish within five minutes; inspect install.properties and the retained verified build before restarting");
                System.out.println("Waiting for client update installer: " + updatePlan);
                TimeUnit.SECONDS.sleep(5);
            }
            Process child = new ProcessBuilder(profile.command()).directory(profile.workdir().toFile()).inheritIO().start();
            long started = System.nanoTime();
            long boot=System.currentTimeMillis();
            int code;
            boolean held=false;
            try {
                while(!child.waitFor(2,TimeUnit.SECONDS)){
                    Path heartbeat=profile.instance().resolve("hivemind/runtime-health.properties");
                    Properties state=hivemind.utils.bot.AtomicProperties.read(heartbeat);
                    long tick=Long.parseLong(state.getProperty("tick","0")),pid=Long.parseLong(state.getProperty("pid","-1"));
                    boolean belongs=pid==child.pid()||child.descendants().anyMatch(p->p.pid()==pid);
                    Health health=health(boot,belongs?tick:0,System.currentTimeMillis(),belongs&&Boolean.parseBoolean(state.getProperty("held","false")),profile.watchdogSeconds(),profile.startupGraceSeconds());
                    held=health==Health.HELD;
                    if(health==Health.FROZEN){System.err.println("Minecraft heartbeat stopped; terminating this configured instance before recovery");child.destroy();if(!child.waitFor(10,TimeUnit.SECONDS))child.destroyForcibly();break;}
                }
                code=child.waitFor();
            }
            catch (InterruptedException interrupted) { child.destroy(); Thread.currentThread().interrupt(); return; }
            if (code == 0) { System.out.println("Minecraft launcher exited normally; supervisor stopped."); return; }
            if(held){System.out.println("Bot was deliberately held; automatic restart suppressed.");return;}
            if (TimeUnit.NANOSECONDS.toMinutes(System.nanoTime() - started) >= 10) crashes = 0;
            int delay = delaySeconds(profile.initialDelaySeconds(), ++crashes);
            if(crashes>profile.maxRestarts())throw new IOException("Client recovery paused after "+profile.maxRestarts()+" repeated failures; saved work and diagnostics are retained");
            System.err.println("Minecraft exited with " + code + "; retrying in " + delay + " seconds");
            TimeUnit.SECONDS.sleep(delay);
        }
    }
}
