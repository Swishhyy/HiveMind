/* HiveMind, LGPL-3.0. Shared by the Java 17 installer and local supervisor. */
package hivemind.utils.update;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** A verified previous build remains available until the new client has demonstrated a healthy boot. */
public final class UpdateHealth {
    private UpdateHealth(){}
    private static Path root(Path instance){return instance.resolve("hivemind/updates");}
    private static Properties read(Path file)throws IOException{Properties p=new Properties();if(Files.exists(file))try(var in=Files.newInputStream(file)){p.load(in);}return p;}
    private static void write(Path file,Properties p)throws IOException{Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),"update-state-",".tmp");try{try(var out=Files.newOutputStream(temp)){p.store(out,"HiveMind update health");}move(temp,file);}finally{Files.deleteIfExists(temp);}}
    private static void move(Path from,Path to)throws IOException{try{Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);}}
    public static void installed(Path instance,Path target,Path backup,UpdateManifest manifest)throws IOException{
        Path updates=root(instance),config=updates.resolve("config-"+System.currentTimeMillis());Files.createDirectories(config);Path data=instance.resolve("hivemind");int[] count={0};
        Files.walkFileTree(data,new SimpleFileVisitor<Path>(){
            @Override public FileVisitResult preVisitDirectory(Path directory,java.nio.file.attribute.BasicFileAttributes attrs){return directory.startsWith(updates)||directory.getFileName().toString().equals("cache")?FileVisitResult.SKIP_SUBTREE:FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException{
                String name=file.getFileName().toString();if(!attrs.isRegularFile()||Files.isSymbolicLink(file)||!Set.of("bot-web-settings.properties","bot-network-controls.properties","settings.txt").contains(name)||attrs.size()>262144)return FileVisitResult.CONTINUE;
                if(++count[0]>128)throw new IOException("Too many managed configuration snapshots");Path copy=config.resolve(data.relativize(file));Files.createDirectories(copy.getParent());Files.copy(file,copy,StandardCopyOption.REPLACE_EXISTING);return FileVisitResult.CONTINUE;
            }
        });
        Properties p=new Properties();p.setProperty("schema","1");p.setProperty("target",target.toAbsolutePath().normalize().toString());p.setProperty("backup",backup.toRealPath().toString());p.setProperty("previousSha",UpdateInstaller.digest(backup));p.setProperty("nextSha",manifest.sha256());p.setProperty("version",manifest.version());p.setProperty("distribution",manifest.distribution());p.setProperty("config",config.toRealPath().toString());p.setProperty("attempts","0");write(updates.resolve("boot-check.properties"),p);
        try(var paths=Files.list(updates)){var old=paths.filter(f->Files.isDirectory(f)&&f.getFileName().toString().startsWith("config-")).sorted(Comparator.reverseOrder()).toList();for(int i=5;i<old.size();i++)deleteTree(old.get(i));}
    }
    private static void deleteTree(Path path)throws IOException{try(var all=Files.walk(path)){for(Path file:all.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    public static boolean bootAttempt(Path instance,long now)throws IOException{
        Path file=root(instance).resolve("boot-check.properties");Properties p=read(file);if(p.isEmpty())return false;
        Path target=Path.of(p.getProperty("target")).toAbsolutePath().normalize(),mods=instance.resolve("mods").toRealPath();
        if(!target.getParent().equals(mods)||Files.isSymbolicLink(target)||!Files.isRegularFile(target))throw new IOException("Update health target leaves the configured mods folder");
        if(!UpdateInstaller.digest(target).equals(p.getProperty("nextSha")))throw new IOException("Installed client differs from the pending verified build; automatic replacement refused");
        int attempts=Integer.parseInt(p.getProperty("attempts","0"))+1;if(attempts>3)return rollback(instance,"Updated client failed to become healthy after repeated launches");
        p.setProperty("attempts",""+attempts);p.setProperty("launched",""+now);write(file,p);return false;
    }
    public static void healthy(Path instance,String version)throws IOException{
        Path file=root(instance).resolve("boot-check.properties");Properties p=read(file);if(p.isEmpty()||!version.equals(p.getProperty("version")))return;
        Path target=Path.of(p.getProperty("target"));if(!UpdateInstaller.digest(target).equals(p.getProperty("nextSha")))throw new IOException("Healthy boot identity differs from the installed update");Files.delete(file);
    }
    public static boolean rollback(Path instance,String reason)throws IOException{
        Path updates=root(instance).toAbsolutePath().normalize(),file=updates.resolve("boot-check.properties");Properties p=read(file);if(p.isEmpty())return false;
        Path target=Path.of(p.getProperty("target")).toAbsolutePath().normalize(),backup=Path.of(p.getProperty("backup")).toRealPath(),mods=instance.resolve("mods").toRealPath();
        if(!target.getParent().equals(mods)||Files.isSymbolicLink(target)||!backup.getParent().equals(updates.resolve("backups"))||Files.isSymbolicLink(backup)||!p.getProperty("previousSha","").matches("[a-f0-9]{64}")||!UpdateInstaller.digest(backup).equals(p.getProperty("previousSha")))throw new IOException("Previous client backup could not be verified");
        UpdateInstaller.compatible(UpdateInstaller.distribution(backup),p.getProperty("distribution"));
        if(!UpdateInstaller.digest(target).equals(p.getProperty("nextSha")))throw new IOException("Client changed while rollback was pending");
        Path config=Path.of(p.getProperty("config")).toAbsolutePath().normalize();if(!config.getParent().equals(updates)||Files.isSymbolicLink(config))throw new IOException("Configuration snapshot leaves the update folder");
        if(Files.isDirectory(config))try(var files=Files.walk(config)){for(Path saved:files.toList())if(Files.isSymbolicLink(saved))throw new IOException("Configuration snapshot contains a symbolic link");}
        Path replacement=Files.createTempFile(mods,".hivemind-rollback-",".tmp");try{Files.copy(backup,replacement,StandardCopyOption.REPLACE_EXISTING);if(!UpdateInstaller.digest(replacement).equals(p.getProperty("previousSha")))throw new IOException("Rollback copy checksum failed");move(replacement,target);}finally{Files.deleteIfExists(replacement);}
        if(Files.isDirectory(config))try(var files=Files.walk(config)){for(Path saved:files.filter(Files::isRegularFile).toList()){
            if(Files.isSymbolicLink(saved))continue;Path restored=instance.resolve("hivemind").resolve(config.relativize(saved)).normalize();if(!restored.startsWith(instance.resolve("hivemind").toAbsolutePath().normalize()))throw new IOException("Invalid configuration rollback path");Files.createDirectories(restored.getParent());Files.copy(saved,restored,StandardCopyOption.REPLACE_EXISTING);
        }}
        Properties pin=new Properties();pin.setProperty("version",p.getProperty("version"));pin.setProperty("reason",reason.substring(0,Math.min(240,reason.length())));pin.setProperty("time",""+System.currentTimeMillis());write(updates.resolve("pinned-release.properties"),pin);Files.delete(file);Files.deleteIfExists(updates.resolve("restart-request.properties"));return true;
    }
    public static String pinned(Path instance)throws IOException{return read(root(instance).resolve("pinned-release.properties")).getProperty("version","");}
    public static void unpin(Path instance)throws IOException{Files.deleteIfExists(root(instance).resolve("pinned-release.properties"));}
    public static void requestRestart(Path instance,String version)throws IOException{Properties p=new Properties();p.setProperty("version",version);p.setProperty("time",""+System.currentTimeMillis());write(root(instance).resolve("restart-request.properties"),p);}
    public static boolean restartRequested(Path instance,long now)throws IOException{Properties p=read(root(instance).resolve("restart-request.properties"));return !p.isEmpty()&&now-Long.parseLong(p.getProperty("time","0"))<600000;}
    public static void restarted(Path instance)throws IOException{Files.deleteIfExists(root(instance).resolve("restart-request.properties"));}
}
