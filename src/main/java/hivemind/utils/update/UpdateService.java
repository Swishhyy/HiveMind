/* HiveMind, LGPL-3.0. */
package hivemind.utils.update;

import hivemind.utils.bot.AtomicProperties;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-client consent and background downloads. A separate helper installs only after this JVM exits. */
public final class UpdateService implements AutoCloseable {
    @FunctionalInterface public interface Fetcher{byte[] get(URI uri,int limit)throws Exception;}
    private final Path instance,currentJar,file;
    private final String version,distribution;
    private final Fetcher fetcher;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"HiveMind-updater");thread.setDaemon(true);return thread;});
    private final AtomicBoolean busy=new AtomicBoolean();
    private volatile boolean asked,automatic,restart;
    private volatile String status="Not checked",available="";
    private volatile long checked;
    private volatile String pinned="";
    private volatile boolean restartAuthorized;
    public UpdateService(Path instance,Path currentJar,String version){this(instance,currentJar,version,UpdateService::download);}
    public UpdateService(Path instance,Path currentJar,String version,Fetcher fetcher){
        this.instance=instance.toAbsolutePath().normalize();this.currentJar=currentJar==null?null:currentJar.toAbsolutePath().normalize();this.version=version;this.fetcher=fetcher;this.distribution=hivemind.utils.network.ClientRole.distribution();
        file=this.instance.resolve("hivemind/updater.properties");Properties p=AtomicProperties.read(file);asked=Boolean.parseBoolean(p.getProperty("asked","false"));automatic=Boolean.parseBoolean(p.getProperty("automatic","false"));
        try{pinned=UpdateHealth.pinned(this.instance);}catch(IOException error){status="Update recovery state could not be read";}
    }
    public static String installedVersion(){Properties p=new Properties();try(InputStream in=UpdateService.class.getResourceAsStream("/hivemind-build.properties")){if(in!=null)p.load(in);}catch(IOException ignored){}return p.getProperty("version","0.1.0.0");}
    public boolean asked(){return asked;}
    public synchronized void consent(boolean enable){
        Properties p=new Properties();p.setProperty("asked","true");p.setProperty("automatic",""+enable);AtomicProperties.write(file,p);asked=true;automatic=enable;
        if(enable)check(true,"",false);
    }
    public Map<String,String> snapshot(){return Map.of("version",version,"available",available,"status",status,"automatic",""+automatic,"restart",""+restart,"checked",""+checked,"distribution",distribution,"pinned",pinned,"authorized",""+restartAuthorized);}
    public String summary(){return "HiveMind "+version+": "+status;}
    public void pulse(){if(asked&&System.currentTimeMillis()-checked>6*3600000L&&!busy.get()&&!restart)check(automatic,"",false);}
    public void check(boolean install){
        check(install,"");
    }
    public void check(boolean install,String expectedVersion){
        check(install,expectedVersion,true);
    }
    private void check(boolean install,String expectedVersion,boolean requested){
        if(install&&requested){if(restart&&!expectedVersion.isBlank()&&!expectedVersion.equals(available)){status="Staged version changed; recheck rollout";return;}restartAuthorized=true;}
        if(restart||!busy.compareAndSet(false,true))return;status="Checking releases";
        worker.execute(()->{try{
            UpdateManifest release=UpdateManifest.read(fetcher.get(UpdateManifest.latest(distribution),16384));UpdateInstaller.compatible(distribution,release.distribution());available=release.version();
            if(!expectedVersion.isBlank()&&!release.version().equals(expectedVersion))throw new IOException("Release changed during rollout; recheck before continuing");
            pinned=UpdateHealth.pinned(instance);if(pinned.equals(release.version())){status="Held after failed startup: "+pinned+"; a newer release or update unpin can retry";return;}
            if(!release.newerThan(version)){status="Up to date";return;}
            status="Available: "+release.version();if(install)stage(release);
        }catch(Exception e){status=shorten("Update failed: "+Objects.toString(e.getMessage(),e.getClass().getSimpleName()));}
        finally{checked=System.currentTimeMillis();busy.set(false);}});
    }
    public boolean restartAuthorized(boolean fleet){return restartAuthorized||!fleet;}
    public void unpin(){try{UpdateHealth.unpin(instance);pinned="";status="Update hold cleared";}catch(IOException error){throw new IllegalStateException(error);}}
    public void healthy(){try{UpdateHealth.healthy(instance,version);}catch(IOException error){status=shorten("Update health: "+error.getMessage());}}
    public boolean supervised(){
        try{Properties p=AtomicProperties.read(instance.resolve("hivemind/supervisor.properties"));if(!Boolean.parseBoolean(p.getProperty("automaticUpdateRestart","false")))return false;long pid=Long.parseLong(p.getProperty("pid","-1"));var parent=ProcessHandle.current().parent();for(int i=0;i<8&&parent.isPresent();i++){if(parent.get().pid()==pid)return parent.get().isAlive();parent=parent.get().parent();}return false;}catch(RuntimeException error){return false;}
    }
    public void requestRestart(){if(!restart||!supervised())throw new IllegalStateException("A staged update and active local supervisor are required");try{UpdateHealth.requestRestart(instance,available);status="Restarting supervised bot at a safe checkpoint";}catch(IOException error){throw new IllegalStateException(error);}}
    private void stage(UpdateManifest manifest)throws Exception{
        UpdateInstaller.compatible(distribution,manifest.distribution());
        Path mods=instance.resolve("mods").toRealPath();
        if(currentJar==null||!Files.isRegularFile(currentJar)||Files.isSymbolicLink(currentJar)||!currentJar.toRealPath().getParent().equals(mods))throw new IOException("Install this release into the instance mods folder first");
        Path updates=instance.resolve("hivemind/updates");Files.createDirectories(updates);
        Path helper=updates.resolve("update-helper.jar");
        try(InputStream in=UpdateService.class.getResourceAsStream("/assets/hivemind/update-helper.bin")){
            if(in==null)throw new IOException("Update helper is missing");Files.copy(in,helper,StandardCopyOption.REPLACE_EXISTING);
        }
        status="Downloading "+manifest.version();byte[] data=fetcher.get(manifest.url(),(int)manifest.size());
        Path partial=updates.resolve("download.part"),staged=updates.resolve((distribution.equals("player")?"hivemind-player-":"hivemind-")+manifest.version()+".jar");
        Files.write(partial,data);try{UpdateInstaller.verify(partial,manifest);Files.move(partial,staged,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(partial);}
        Properties plan=new Properties();plan.setProperty("instance",instance.toRealPath().toString());plan.setProperty("target",currentJar.toRealPath().toString());plan.setProperty("staged",staged.toRealPath().toString());
        plan.setProperty("pid",""+ProcessHandle.current().pid());plan.setProperty("version",manifest.version());plan.setProperty("commit",manifest.commit());plan.setProperty("sha256",manifest.sha256());plan.setProperty("size",""+manifest.size());plan.setProperty("url",manifest.url().toString());plan.setProperty("distribution",manifest.distribution());
        Path journal=updates.resolve("install.properties");AtomicProperties.write(journal,plan);
        String executable=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java";
        Path java=Path.of(System.getProperty("java.home"),"bin",executable);
        Process installer=new ProcessBuilder(java.toString(),"-jar",helper.toString(),"--apply",journal.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(updates.resolve("install.log").toFile())).start();plan.setProperty("helperPid",""+installer.pid());AtomicProperties.write(journal,plan);
        restart=true;status="Ready; installs when Minecraft closes";
    }
    private static byte[] download(URI uri,int limit)throws Exception{
        if(!uri.getScheme().equals("https")||!uri.getHost().equals("github.com")||!uri.getPath().startsWith("/Swishhyy/HiveMind/releases/"))throw new IOException("Update URL is outside the HiveMind release repository");
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).header("User-Agent","HiveMind-updater").GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        try(InputStream in=response.body()){
            if(response.statusCode()!=200)throw new IOException("Release download returned HTTP "+response.statusCode());
            if(!response.uri().getScheme().equals("https"))throw new IOException("Update redirected away from HTTPS");
            byte[] bytes=in.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("Update exceeds its declared size");return bytes;
        }
    }
    private static String shorten(String value){return value.substring(0,Math.min(128,value.length())).replaceAll("[\\p{Cntrl}]"," ");}
    @Override public void close(){worker.shutdownNow();}
}
