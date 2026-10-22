/* HiveMind, LGPL-3.0. This class is also packaged as an independent Java 17 update helper. */
package hivemind.utils.update;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

public final class UpdateInstaller {
    private UpdateInstaller(){}
    public static String digest(Path file)throws IOException{
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))>=0;)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static void verify(Path jar,UpdateManifest manifest)throws IOException{
        if(Files.size(jar)!=manifest.size()||!digest(jar).equals(manifest.sha256()))throw new IOException("Downloaded update checksum/size does not match");
        try(ZipFile zip=new ZipFile(jar.toFile())){
            var entry=zip.getEntry("hivemind-build.properties");var fabric=zip.getEntry("fabric.mod.json");
            if(entry==null||fabric==null)throw new IOException("Update is missing HiveMind/Fabric metadata");
            Properties info=new Properties();try(InputStream in=zip.getInputStream(entry)){byte[] bytes=in.readNBytes(16385);if(bytes.length>16384)throw new IOException("Oversized build metadata");info.load(new ByteArrayInputStream(bytes));}
            if(!"hivemind".equals(info.getProperty("application"))||!"26.3".equals(info.getProperty("minecraft"))||!"fabric".equals(info.getProperty("loader"))
                    ||!manifest.version().equals(info.getProperty("version"))||!manifest.commit().equals(info.getProperty("commit")))throw new IOException("Update build identity is not the expected Fabric 26.3 release");
            String json;try(InputStream in=zip.getInputStream(fabric)){byte[] bytes=in.readNBytes(65537);if(bytes.length>65536)throw new IOException("Oversized Fabric metadata");json=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);}
            if(!java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"hivemind\"").matcher(json).find())throw new IOException("Update mod ID is not hivemind");
        }
    }
    public static void apply(Path plan,boolean waitForGame)throws Exception{
        Properties p=new Properties();try(InputStream in=Files.newInputStream(plan)){p.load(in);}
        Path instance=Path.of(p.getProperty("instance")).toRealPath(),mods=instance.resolve("mods").toRealPath(),updates=instance.resolve("hivemind/updates").toRealPath();
        Path target=Path.of(p.getProperty("target")).toAbsolutePath().normalize(),staged=Path.of(p.getProperty("staged")).toRealPath();
        if(!target.getParent().equals(mods)||Files.isSymbolicLink(target)||!target.getFileName().toString().endsWith(".jar")||!staged.getParent().equals(updates)
                ||!plan.toRealPath().getParent().equals(updates))throw new IOException("Update plan leaves this Minecraft instance");
        UpdateManifest expected=new UpdateManifest(p.getProperty("version"),p.getProperty("commit"),Long.parseLong(p.getProperty("size")),p.getProperty("sha256"),java.net.URI.create(p.getProperty("url")));
        long pid=Long.parseLong(p.getProperty("pid"));if(pid<1)throw new IOException("Invalid parent process");
        if(waitForGame){var parent=ProcessHandle.of(pid);if(parent.isPresent())parent.get().onExit().get(7,TimeUnit.DAYS);}
        verify(staged,expected);
        Path backups=updates.resolve("backups");Files.createDirectories(backups);Path backup=backups.resolve("hivemind-"+System.currentTimeMillis()+".jar");
        if(Files.exists(target))Files.copy(target,backup,StandardCopyOption.COPY_ATTRIBUTES);
        Path replacement=mods.resolve(".hivemind-update-"+UUID.randomUUID()+".tmp");
        try{
            Files.copy(staged,replacement);verify(replacement,expected);
            IOException failure=null;
            for(int attempt=0;attempt<30;attempt++)try{
                try{Files.move(replacement,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException e){Files.move(replacement,target,StandardCopyOption.REPLACE_EXISTING);}
                failure=null;break;
            }catch(IOException e){failure=e;Thread.sleep(500);}
            if(failure!=null)throw failure;
            Files.deleteIfExists(staged);Files.deleteIfExists(plan);
            try(var files=Files.list(backups)){var old=files.filter(path->path.getFileName().toString().endsWith(".jar")).sorted(Comparator.reverseOrder()).toList();for(int i=5;i<old.size();i++)Files.deleteIfExists(old.get(i));}
        }finally{Files.deleteIfExists(replacement);}
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2||!args[0].equals("--apply"))throw new IllegalArgumentException("Use --apply <update-plan.properties>");
        apply(Path.of(args[1]),true);
    }
}
