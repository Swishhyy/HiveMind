/* HiveMind, LGPL-3.0. */
package hivemind.controller;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
public final class ControllerUpdateChecks {
    static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
    interface Checked {void run()throws Exception;}
    static void rejects(Checked task)throws Exception {try{task.run();throw new AssertionError("Bad update accepted");}catch(IOException|IllegalArgumentException expected){}}
    static byte[] properties(Properties p)throws Exception {var out=new ByteArrayOutputStream();p.store(out,"");return out.toByteArray();}
    static Properties manifest(Path jar,String version)throws Exception {var p=new Properties();p.setProperty("application","hivemind-controller");p.setProperty("java","17");p.setProperty("version",version);p.setProperty("commit","a".repeat(40));p.setProperty("size",""+Files.size(jar));p.setProperty("sha256",ControllerUpdater.digest(jar));p.setProperty("url","https://github.com/Swishhyy/HiveMind/releases/download/v0.1.0.10/hivemind-controller.jar");return p;}
    static void candidate(Path file,String application)throws Exception {
        try(var input=new ZipFile("build/distributions/hivemind-controller.jar");var out=new ZipOutputStream(Files.newOutputStream(file))){
            for(var it=input.entries();it.hasMoreElements();){var entry=it.nextElement();out.putNextEntry(new ZipEntry(entry.getName()));
                if(entry.getName().equals("hivemind-controller.properties"))out.write(("application="+application+"\njava=17\nversion=0.1.0.10\ncommit="+"a".repeat(40)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));else try(var in=input.getInputStream(entry)){in.transferTo(out);}out.closeEntry();
            }
        }
    }
    public static void main(String[] args)throws Exception {
        if(args.length>0&&args[0].equals("--hold")){try(var channel=java.nio.channels.FileChannel.open(Path.of(args[2]),StandardOpenOption.WRITE);var lock=channel.lock()){Files.writeString(Path.of(args[1]),"ready");Thread.sleep(30000);}return;}
        if(args.length>0&&args[0].equals("--probe")){Path marker=Path.of(args[1]),partial=Path.of(args[1]+".tmp");Files.writeString(partial,String.join("\n",Arrays.copyOfRange(args,2,args.length)));Files.move(partial,marker,StandardCopyOption.ATOMIC_MOVE);return;}
        Path root=Files.createTempDirectory("hivemind controller update ");try{
            Path jar=root.resolve("my controller.jar"),candidate=root.resolve("candidate.jar");Files.copy(Path.of("build/distributions/hivemind-controller.jar"),jar);byte[] original=Files.readAllBytes(jar);candidate(candidate,"hivemind-controller");var p=manifest(candidate,"0.1.0.10");byte[] metadata=properties(p),data=Files.readAllBytes(candidate);
            var expected=ControllerUpdateManifest.read(metadata);ControllerUpdater.verify(candidate,expected);
            check(ControllerUpdateManifest.compare("0.1.0.10","0.1.0.9")>0,"Versions must compare numerically");
            var bad=new Properties();bad.putAll(p);bad.setProperty("application","hivemind");rejects(()->ControllerUpdateManifest.read(properties(bad)));bad.setProperty("application","hivemind-controller");bad.setProperty("url","https://example.org/controller.jar");rejects(()->ControllerUpdateManifest.read(properties(bad)));
            Path wrong=root.resolve("wrong.jar");candidate(wrong,"hivemind");rejects(()->ControllerUpdater.verify(wrong,ControllerUpdateManifest.read(properties(manifest(wrong,"0.1.0.10")))));
            final int[] requests={0};ControllerUpdater.Fetcher fetch=(url,limit)->{requests[0]++;return url.equals(ControllerUpdateManifest.LATEST)?metadata:data;};
            check(!ControllerUpdater.prepare(jar,"0.1.0.10",new String[0],fetch,(c,w)->{throw new AssertionError("Unchanged controller restarted");})&&requests[0]==1,"Same component version must never download/restart");
            requests[0]=0;check(!ControllerUpdater.prepare(jar,"0.1.0.11",new String[0],fetch,(c,w)->{throw new AssertionError("Downgrade");})&&requests[0]==1,"Older releases must not install");
            rejects(()->ControllerUpdater.prepare(jar,"0.1.0.9",new String[0],(u,l)->u.equals(ControllerUpdateManifest.LATEST)?metadata:new byte[2048],(c,w)->{throw new AssertionError("Corrupt helper launched");}));check(Arrays.equals(original,Files.readAllBytes(jar)),"Bad download changed installed controller");
            Path updates=root.resolve(".hivemind-controller-updates"),plan=updates.resolve("install.properties");
            rejects(()->ControllerUpdater.prepare(jar,"0.1.0.9",new String[0],fetch,(c,w)->{throw new IOException("Cannot start helper");}));check(!Files.exists(plan),"Failed helper start left a blocking journal");
            List<List<String>> commands=new ArrayList<>();String[] flags={"--data",root.resolve("saved data").toString(),"--scope","my-server"};
            check(ControllerUpdater.prepare(jar,"0.1.0.9",flags,fetch,(c,w)->commands.add(c)),"New controller should hand off before startup");
            check(commands.size()==1&&commands.get(0).contains(ControllerUpdater.class.getName()),"Replacement must run outside current JVM");
            Properties saved=ControllerUpdater.readPlan(plan);var restart=ControllerUpdater.plannedCommand(saved);check(restart.subList(restart.size()-flags.length,restart.size()).equals(List.of(flags)),"Restart lost original flags/spaces");
            check(Arrays.equals(original,Files.readAllBytes(jar)),"Preparing update must leave the locked JAR alone");
            // Exercise the actual copied helper in a separate process, including backup and restart without a shell.
            Path marker=root.resolve("restart marker.txt");List<String> probe=List.of(ControllerUpdater.javaExecutable().toString(),"-cp",System.getProperty("java.class.path"),ControllerUpdateChecks.class.getName(),"--probe",marker.toString(),"argument with spaces","--scope","my-server");
            ControllerUpdater.releaseHandoff();Path ready=root.resolve("parent ready.txt");var parent=new ProcessBuilder(ControllerUpdater.javaExecutable().toString(),"-cp",System.getProperty("java.class.path"),ControllerUpdateChecks.class.getName(),"--hold",ready.toString(),updates.resolve("parent.lock").toString()).inheritIO().start();
            long readyUntil=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!Files.exists(ready)&&parent.isAlive()&&System.nanoTime()<readyUntil)Thread.sleep(25);check(Files.exists(ready)&&parent.isAlive(),"Test parent failed to start");
            saved.setProperty("pid",""+parent.pid());saved.setProperty("argc",""+probe.size());for(int i=0;i<probe.size();i++)saved.setProperty("arg."+i,probe.get(i));Files.write(plan,properties(saved));
            var helper=new ProcessBuilder(commands.get(0)).inheritIO().start();
            try{Thread.sleep(250);check(Arrays.equals(original,Files.readAllBytes(jar))&&!Files.exists(marker),"Helper replaced/restarted before its parent exited; parent alive="+parent.isAlive()+", jarEqual="+Arrays.equals(original,Files.readAllBytes(jar))+", marker="+Files.exists(marker));}finally{parent.destroyForcibly();parent.waitFor(5,TimeUnit.SECONDS);}
            check(helper.waitFor(30,TimeUnit.SECONDS)&&helper.exitValue()==0,"Real controller helper failed");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!Files.exists(marker)&&System.nanoTime()<deadline)Thread.sleep(25);
            check(Files.readString(marker).equals("argument with spaces\n--scope\nmy-server"),"Real restart did not preserve arguments");
            ControllerUpdater.verify(jar,expected);check(Arrays.equals(original,Files.readAllBytes(updates.resolve("previous.jar"))),"Previous controller was not backed up");check(!Files.exists(plan),"Successful helper left a pending plan");
            // A failed install must relaunch the existing controller once, without an update loop.
            Files.delete(marker);Files.write(plan,properties(saved));Files.write(updates.resolve("download.jar"),new byte[2048]);
            var failed=new ProcessBuilder(commands.get(0)).inheritIO().start();check(failed.waitFor(30,TimeUnit.SECONDS)&&failed.exitValue()==0,"Failed install did not relaunch the controller");
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!Files.exists(marker)&&System.nanoTime()<deadline)Thread.sleep(25);
            check(Files.exists(marker)&&!Files.exists(plan),"Failed install did not clean its journal and restart");ControllerUpdater.verify(jar,expected);
            // Malicious endpoint plans cannot replace an unrelated file.
            saved.setProperty("target",root.resolve("outside/other.jar").toString());Files.write(plan,properties(saved));Files.write(updates.resolve("download.jar"),data);rejects(()->ControllerUpdater.replace(plan));
            System.out.println("Controller updater checks passed: channel isolation, no-op/downgrade, corrupted downloads, failed handoff, real helper replacement/backup/restart and argument preservation");
        }finally{ControllerUpdater.releaseHandoff();try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
