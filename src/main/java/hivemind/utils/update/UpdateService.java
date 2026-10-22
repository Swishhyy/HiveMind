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
    private final String version;
    private final Fetcher fetcher;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"HiveMind-updater");thread.setDaemon(true);return thread;});
    private final AtomicBoolean busy=new AtomicBoolean();
    private volatile boolean asked,automatic,restart;
    private volatile String status="Not checked",available="";
    private volatile long checked;
    public UpdateService(Path instance,Path currentJar,String version){this(instance,currentJar,version,UpdateService::download);}
    public UpdateService(Path instance,Path currentJar,String version,Fetcher fetcher){
        this.instance=instance.toAbsolutePath().normalize();this.currentJar=currentJar==null?null:currentJar.toAbsolutePath().normalize();this.version=version;this.fetcher=fetcher;
        file=this.instance.resolve("hivemind/updater.properties");Properties p=AtomicProperties.read(file);asked=Boolean.parseBoolean(p.getProperty("asked","false"));automatic=Boolean.parseBoolean(p.getProperty("automatic","false"));
    }
    public static String installedVersion(){Properties p=new Properties();try(InputStream in=UpdateService.class.getResourceAsStream("/hivemind-build.properties")){if(in!=null)p.load(in);}catch(IOException ignored){}return p.getProperty("version","0.1.0.0");}
    public boolean asked(){return asked;}
    public synchronized void consent(boolean enable){
        Properties p=new Properties();p.setProperty("asked","true");p.setProperty("automatic",""+enable);AtomicProperties.write(file,p);asked=true;automatic=enable;
        if(enable)check(true);
    }
    public Map<String,String> snapshot(){return Map.of("version",version,"available",available,"status",status,"automatic",""+automatic,"restart",""+restart,"checked",""+checked);}
    public String summary(){return "HiveMind "+version+": "+status;}
    public void pulse(){if(asked&&System.currentTimeMillis()-checked>6*3600000L&&!busy.get()&&!restart)check(automatic);}
    public void check(boolean install){
        if(restart||!busy.compareAndSet(false,true))return;status="Checking releases";
        worker.execute(()->{try{
            UpdateManifest release=UpdateManifest.read(fetcher.get(UpdateManifest.LATEST,16384));available=release.version();
            if(!release.newerThan(version)){status="Up to date";return;}
            status="Available: "+release.version();if(install)stage(release);
        }catch(Exception e){status=shorten("Update failed: "+Objects.toString(e.getMessage(),e.getClass().getSimpleName()));}
        finally{checked=System.currentTimeMillis();busy.set(false);}});
    }
    private void stage(UpdateManifest manifest)throws Exception{
        Path mods=instance.resolve("mods").toRealPath();
        if(currentJar==null||!Files.isRegularFile(currentJar)||Files.isSymbolicLink(currentJar)||!currentJar.toRealPath().getParent().equals(mods))throw new IOException("Install this release into the instance mods folder first");
        Path updates=instance.resolve("hivemind/updates");Files.createDirectories(updates);
        Path helper=updates.resolve("update-helper.jar");
        try(InputStream in=UpdateService.class.getResourceAsStream("/assets/hivemind/update-helper.jar")){
            if(in==null)throw new IOException("Update helper is missing");Files.copy(in,helper,StandardCopyOption.REPLACE_EXISTING);
        }
        status="Downloading "+manifest.version();byte[] data=fetcher.get(manifest.url(),(int)manifest.size());
        Path partial=updates.resolve("download.part"),staged=updates.resolve("hivemind-"+manifest.version()+".jar");
        Files.write(partial,data);try{UpdateInstaller.verify(partial,manifest);Files.move(partial,staged,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(partial);}
        Properties plan=new Properties();plan.setProperty("instance",instance.toRealPath().toString());plan.setProperty("target",currentJar.toRealPath().toString());plan.setProperty("staged",staged.toRealPath().toString());
        plan.setProperty("pid",""+ProcessHandle.current().pid());plan.setProperty("version",manifest.version());plan.setProperty("commit",manifest.commit());plan.setProperty("sha256",manifest.sha256());plan.setProperty("size",""+manifest.size());plan.setProperty("url",manifest.url().toString());
        Path journal=updates.resolve("install.properties");AtomicProperties.write(journal,plan);
        String executable=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java";
        Path java=Path.of(System.getProperty("java.home"),"bin",executable);
        new ProcessBuilder(java.toString(),"-jar",helper.toString(),"--apply",journal.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(updates.resolve("install.log").toFile())).start();
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
