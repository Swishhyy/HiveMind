/* HiveMind, LGPL-3.0. */
package hivemind.controller;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
/** Runs before sockets open. A separate JVM replaces the unlocked JAR, then relaunches with the original arguments. */
public final class ControllerUpdater {
    @FunctionalInterface interface Fetcher { byte[] get(URI uri,int limit)throws Exception; }
    @FunctionalInterface interface Starter { void start(List<String> command,Path cwd)throws IOException; }
    private static FileChannel handoffChannel;
    private static FileLock handoffLock;
    private ControllerUpdater(){}
    static void releaseHandoff()throws IOException {if(handoffLock!=null){handoffLock.close();handoffLock=null;}if(handoffChannel!=null){handoffChannel.close();handoffChannel=null;}}
    private static void holdHandoff(Path file)throws IOException {
        handoffChannel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        handoffLock=handoffChannel.tryLock();if(handoffLock==null){releaseHandoff();throw new IOException("Another controller update is completing");}
    }
    static byte[] fetch(URI uri,int limit)throws Exception {
        if(!uri.toString().startsWith(ControllerUpdateManifest.REPOSITORY+"/releases/")||!uri.getScheme().equals("https"))throw new IOException("Controller update URL leaves its release repository");
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
        var response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(limit<=16384?10:60)).header("User-Agent","HiveMind-controller-updater").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
        try(var in=response.body()){
            if(response.statusCode()!=200||!response.uri().getScheme().equals("https"))throw new IOException("Controller download returned HTTP "+response.statusCode());
            byte[] bytes=in.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("Controller download is oversized");return bytes;
        }
    }
    static String digest(Path file)throws IOException {
        try{var hash=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)){byte[] b=new byte[65536];for(int n;(n=in.read(b))>=0;)hash.update(b,0,n);}return HexFormat.of().formatHex(hash.digest());}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static void verify(Path jar,ControllerUpdateManifest manifest)throws IOException {
        if(Files.size(jar)!=manifest.size()||!digest(jar).equals(manifest.sha256()))throw new IOException("Controller checksum/size mismatch");
        try(var zip=new ZipFile(jar.toFile())){
            var entry=zip.getEntry("hivemind-controller.properties");if(entry==null||zip.getEntry("hivemind/controller/Main.class")==null||zip.getEntry("hivemind/controller/ControllerUpdater.class")==null)throw new IOException("Not a controller JAR");
            Properties p=new Properties();try(var in=zip.getInputStream(entry)){byte[] b=in.readNBytes(16385);if(b.length>16384)throw new IOException("Controller identity is oversized");p.load(new ByteArrayInputStream(b));}
            if(!"hivemind-controller".equals(p.getProperty("application"))||!"17".equals(p.getProperty("java"))||!manifest.version().equals(p.getProperty("version"))||!manifest.commit().equals(p.getProperty("commit")))throw new IOException("Controller identity mismatch");
        }
    }
    static Path installedJar()throws Exception {return Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();}
    static Path javaExecutable(){return Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java");}
    static List<String> restartCommand(Path target,String[] args){
        // jpackage's native process is the same running JVM, so preserve its executable on portable Windows installs.
        String process=ProcessHandle.current().info().command().orElse("");
        List<String> command=new ArrayList<>();
        if(Path.of(process.isEmpty()?"unknown":process).getFileName().toString().equalsIgnoreCase("HiveMindController.exe"))command.add(process);
        else{command.add(javaExecutable().toString());command.add("-jar");command.add(target.toString());}
        command.addAll(List.of(args));return command;
    }
    static boolean beforeStart(String[] args){
        if(System.getenv("HIVEMIND_CONTROLLER_UPDATED")!=null)return false;
        try{return prepare(installedJar(),Main.VERSION,args,ControllerUpdater::fetch,(command,cwd)->new ProcessBuilder(command).directory(cwd.toFile()).inheritIO().start());}
        catch(Exception e){System.err.println("Controller update unavailable; starting installed version: "+Objects.toString(e.getMessage(),e.getClass().getSimpleName()));return false;}
    }
    static boolean prepare(Path target,String version,String[] args,Fetcher fetcher,Starter starter)throws Exception {
        if(!Files.isRegularFile(target)||Files.isSymbolicLink(target)||!target.toString().endsWith(".jar")){System.out.println("Controller development build: startup updater skipped");return false;}
        System.out.println("Checking for controller updates...");var manifest=ControllerUpdateManifest.read(fetcher.get(ControllerUpdateManifest.LATEST,16384));
        if(ControllerUpdateManifest.compare(manifest.version(),version)<=0){System.out.println("Controller "+version+" is up to date");return false;}
        target=target.toRealPath();Path updates=target.getParent().resolve(".hivemind-controller-updates");Files.createDirectories(updates);
        if(Files.isSymbolicLink(updates))throw new IOException("Update directory must not be a symlink");
        Path pin=updates.resolve("pinned-release.properties");if(Files.isRegularFile(pin)&&manifest.version().equals(readPlan(pin).getProperty("version"))){System.out.println("Controller release "+manifest.version()+" is held after a failed startup; running "+version);return false;}
        try(var channel=FileChannel.open(updates.resolve("update.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
            if(lock==null)throw new IOException("Another controller updater is running");
            Path plan=updates.resolve("install.properties");
            if(Files.exists(plan)){
                long pid=Long.parseLong(readPlan(plan).getProperty("pid","0"));
                if(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)||System.currentTimeMillis()-Files.getLastModifiedTime(plan).toMillis()<120000)throw new IOException("A controller update is still pending: "+plan);
                Files.delete(plan); // Recover a stale journal left by a crashed helper on a later launch.
            }
            System.out.println("Updating controller "+version+" to "+manifest.version()+" before startup...");
            Path staged=updates.resolve("download.jar");Files.write(staged,fetcher.get(manifest.url(),(int)manifest.size()));verify(staged,manifest);
            Path helper=updates.resolve("installer.jar");Files.copy(staged,helper,StandardCopyOption.REPLACE_EXISTING);
            Properties p=new Properties();p.setProperty("target",target.toString());p.setProperty("staged",staged.toRealPath().toString());p.setProperty("pid",""+ProcessHandle.current().pid());p.setProperty("cwd",Path.of("").toAbsolutePath().toString());
            p.setProperty("application","hivemind-controller");p.setProperty("java","17");p.setProperty("version",manifest.version());p.setProperty("commit",manifest.commit());p.setProperty("size",""+manifest.size());p.setProperty("sha256",manifest.sha256());p.setProperty("url",manifest.url().toString());
            var restart=restartCommand(target,args);p.setProperty("argc",""+restart.size());for(int i=0;i<restart.size();i++)p.setProperty("arg."+i,restart.get(i));
            try(var out=Files.newOutputStream(plan,StandardOpenOption.CREATE_NEW)){p.store(out,"HiveMind controller startup update");}
            try{holdHandoff(updates.resolve("parent.lock"));starter.start(List.of(javaExecutable().toString(),"-cp",helper.toString(),ControllerUpdater.class.getName(),"--apply",plan.toString()),Path.of("").toAbsolutePath());}
            catch(Exception e){releaseHandoff();Files.deleteIfExists(plan);throw e;}
            return true;
        }
    }
    static Properties readPlan(Path plan)throws IOException {Properties p=new Properties();try(var in=Files.newInputStream(plan)){p.load(in);}return p;}
    static void healthy(){
        try{Path jar=installedJar();if(!Files.isRegularFile(jar)||!jar.toString().endsWith(".jar"))return;Path updates=jar.getParent().resolve(".hivemind-controller-updates");Files.createDirectories(updates);Properties p=new Properties();p.setProperty("version",Main.VERSION);p.setProperty("pid",""+ProcessHandle.current().pid());p.setProperty("time",""+System.currentTimeMillis());writeState(updates.resolve("healthy.properties"),p);}catch(Exception error){System.err.println("Controller health marker unavailable: "+error.getMessage());}
    }
    static void unpin()throws Exception{Path jar=installedJar();Files.deleteIfExists(jar.getParent().resolve(".hivemind-controller-updates/pinned-release.properties"));}
    private static void writeState(Path file,Properties p)throws IOException{Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),"controller-state-",".tmp");try{try(var out=Files.newOutputStream(temp)){p.store(out,"HiveMind controller recovery");}move(temp,file);}finally{Files.deleteIfExists(temp);}}
    static void rollback(Path updates,Path target,String previousSha,String nextSha,String version)throws IOException{
        Path backup=updates.resolve("previous.jar");if(!updates.getParent().equals(target.getParent())||Files.isSymbolicLink(target)||!digest(backup).equals(previousSha)||!digest(target).equals(nextSha))throw new IOException("Controller rollback identity could not be verified");
        Path temp=target.resolveSibling(".hivemind-controller-rollback.tmp");try{Files.copy(backup,temp,StandardCopyOption.REPLACE_EXISTING);if(!digest(temp).equals(previousSha))throw new IOException("Controller rollback copy failed verification");move(temp,target);}finally{Files.deleteIfExists(temp);}
        Properties p=new Properties();p.setProperty("version",version);p.setProperty("reason","Updated controller did not become healthy");writeState(updates.resolve("pinned-release.properties"),p);
    }
    static void replace(Path plan)throws Exception {
        var p=readPlan(plan);var manifest=ControllerUpdateManifest.read(propertiesBytes(p));Path updates=plan.toRealPath().getParent();
        Path target=Path.of(p.getProperty("target")).toAbsolutePath().normalize(),staged=Path.of(p.getProperty("staged")).toRealPath();
        if(!updates.getFileName().toString().equals(".hivemind-controller-updates")||!updates.getParent().equals(target.getParent())||Files.isSymbolicLink(target)||!target.toString().endsWith(".jar")||!staged.getParent().equals(updates))throw new IOException("Controller update plan leaves its install directory");
        verify(staged,manifest);Path backup=updates.resolve("previous.jar"),temporary=target.resolveSibling(".hivemind-controller-replacement.tmp");
        Files.copy(target,backup,StandardCopyOption.REPLACE_EXISTING);try{
            Files.copy(staged,temporary,StandardCopyOption.REPLACE_EXISTING);verify(temporary,manifest);
            IOException failure=null;for(int i=0;i<30;i++)try{move(temporary,target);failure=null;break;}catch(IOException e){failure=e;Thread.sleep(250);}
            if(failure!=null)throw failure;
            try{verify(target,manifest);}catch(Exception e){move(backup,target);throw e;}
            Files.deleteIfExists(plan);Files.deleteIfExists(staged);
        }finally{Files.deleteIfExists(temporary);}
    }
    private static byte[] propertiesBytes(Properties p)throws IOException {var out=new ByteArrayOutputStream();p.store(out,"");return out.toByteArray();}
    private static void move(Path from,Path to)throws IOException {try{Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);}}
    static List<String> plannedCommand(Properties p)throws IOException {int count=Integer.parseInt(p.getProperty("argc"));if(count<1||count>128)throw new IOException("Invalid restart argument count");var result=new ArrayList<String>();for(int i=0;i<count;i++)result.add(Objects.requireNonNull(p.getProperty("arg."+i)));return result;}
    public static void main(String[] args)throws Exception {
        if(args.length!=2||!args[0].equals("--apply"))throw new IllegalArgumentException("Use --apply <controller-plan>");
        Path plan=Path.of(args[1]);Properties p=readPlan(plan);long pid=Long.parseLong(p.getProperty("pid"));if(pid<1||pid==ProcessHandle.current().pid())throw new IOException("Invalid parent process");
        // An OS file lock also works when the helper and parent see different container PID namespaces.
        // The parent holds it until the JVM exits, including Windows' final JAR-handle cleanup.
        try(var channel=FileChannel.open(plan.toRealPath().getParent().resolve("parent.lock"),StandardOpenOption.WRITE)){
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            while(true){var lock=channel.tryLock();if(lock!=null){lock.close();break;}if(System.nanoTime()>=deadline)throw new IOException("Controller did not exit before update timeout");Thread.sleep(100);}
        }
        Path target=Path.of(p.getProperty("target")).toAbsolutePath().normalize(),updates=plan.toRealPath().getParent();String previousSha=digest(target);boolean installed=false;
        Path pin=updates.resolve("pinned-release.properties");
        if(Files.isRegularFile(pin)&&p.getProperty("version").equals(readPlan(pin).getProperty("version"))&&!previousSha.equals(p.getProperty("sha256"))){System.err.println("Keeping the previous controller; this release is held after a failed startup");Files.deleteIfExists(plan);}
        else try{replace(plan);installed=true;}catch(Exception e){System.err.println("Controller update failed; retaining installed version: "+e);Files.deleteIfExists(plan);}
        // No shell: quoted paths, spaces, tokens and custom server flags remain separate arguments.
        var restart=new ProcessBuilder(plannedCommand(p)).directory(Path.of(p.getProperty("cwd")).toFile()).inheritIO();
        restart.environment().put("HIVEMIND_CONTROLLER_UPDATED",p.getProperty("version"));Process child=restart.start();if(!installed)return;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);boolean good=false;
        while(System.nanoTime()<deadline){
            Path health=updates.resolve("healthy.properties");if(Files.isRegularFile(health)){Properties state=readPlan(health);if(p.getProperty("version").equals(state.getProperty("version"))&&Long.toString(child.pid()).equals(state.getProperty("pid"))){good=true;break;}}
            if(child.waitFor(1,TimeUnit.SECONDS)){good=child.exitValue()==0;break;}
        }
        if(good)return;
        if(child.isAlive()){child.destroy();if(!child.waitFor(5,TimeUnit.SECONDS))child.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
        rollback(updates,target,previousSha,p.getProperty("sha256"),p.getProperty("version"));System.err.println("Updated controller failed startup; previous verified controller restored and failed release held.");restart.start();
    }
}
