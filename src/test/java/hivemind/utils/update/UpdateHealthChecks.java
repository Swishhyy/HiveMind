/* HiveMind, LGPL-3.0. */
package hivemind.utils.update;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

public final class UpdateHealthChecks {
    static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
    static void jar(Path file,String version,String role)throws Exception{try(var zip=new ZipOutputStream(Files.newOutputStream(file))){zip.putNextEntry(new ZipEntry("hivemind-build.properties"));zip.write(("application=hivemind\nminecraft=26.3\nloader=fabric\nversion="+version+"\ncommit="+"a".repeat(40)+"\ndistribution="+role+"\n").getBytes());zip.closeEntry();zip.putNextEntry(new ZipEntry("fabric.mod.json"));zip.write("{\"id\":\"hivemind\"}".getBytes());zip.closeEntry();zip.putNextEntry(new ZipEntry("padding.bin"));byte[] padding=new byte[4096];new Random(1).nextBytes(padding);zip.write(padding);zip.closeEntry();}}
    static byte[] metadata(Path file,String version)throws Exception{Properties p=new Properties();p.setProperty("application","hivemind");p.setProperty("minecraft","26.3");p.setProperty("loader","fabric");p.setProperty("version",version);p.setProperty("commit","a".repeat(40));p.setProperty("size",""+Files.size(file));p.setProperty("sha256",UpdateInstaller.digest(file));p.setProperty("url","https://github.com/Swishhyy/HiveMind/releases/download/v"+version+"/hivemind-fabric.jar");p.setProperty("distribution","bot");var out=new java.io.ByteArrayOutputStream();p.store(out,"");return out.toByteArray();}
    public static void main(String[] args)throws Exception{
        Path instance=Files.createTempDirectory("hivemind-update-health"),mods=Files.createDirectory(instance.resolve("mods")),updates=Files.createDirectories(instance.resolve("hivemind/updates/backups")),target=mods.resolve("original-bot-name.jar"),backup=updates.resolve("previous.jar"),candidate=instance.resolve("candidate.jar");
        jar(target,"0.1.2.0","bot");String previous=UpdateInstaller.digest(target);Files.copy(target,backup);jar(candidate,"0.1.2.1","bot");byte[] metadata=metadata(candidate,"0.1.2.1");var manifest=UpdateManifest.read(metadata);
        Path settings=Files.createDirectories(instance.resolve("hivemind/server-one")).resolve("bot-web-settings.properties");Files.writeString(settings,"stalkDistance=40\n");
        UpdateHealth.installed(instance,target,backup,manifest);Files.copy(candidate,target,StandardCopyOption.REPLACE_EXISTING);Files.writeString(settings,"futureSetting=true\n");
        check(!UpdateHealth.bootAttempt(instance,1000)&&!UpdateHealth.bootAttempt(instance,2000)&&!UpdateHealth.bootAttempt(instance,3000),"three launches receive a chance to become healthy");
        check(UpdateHealth.bootAttempt(instance,4000),"repeated failed boots restore a verified previous build");check(UpdateInstaller.digest(target).equals(previous)&&Files.readString(settings).contains("stalkDistance=40"),"rollback preserves the original mod filename and restores compatible managed settings");check(UpdateHealth.pinned(instance).equals("0.1.2.1"),"failed release is held");
        AtomicInteger calls=new AtomicInteger();try(var service=new UpdateService(instance,target,"0.1.2.0",(u,l)->{calls.incrementAndGet();return metadata;})){service.check(true);long deadline=System.nanoTime()+3_000_000_000L;while(service.snapshot().get("checked").equals("0")&&System.nanoTime()<deadline)Thread.sleep(10);check(calls.get()==1&&service.summary().contains("Held"),"held release is not downloaded or installed again");}
        UpdateHealth.unpin(instance);UpdateHealth.installed(instance,target,backup,manifest);Files.copy(candidate,target,StandardCopyOption.REPLACE_EXISTING);UpdateHealth.healthy(instance,"0.1.2.0");check(Files.exists(instance.resolve("hivemind/updates/boot-check.properties")),"wrong build cannot confirm a healthy boot");UpdateHealth.healthy(instance,"0.1.2.1");check(!Files.exists(instance.resolve("hivemind/updates/boot-check.properties")),"matching verified build clears its pending boot check");
        Files.copy(backup,target,StandardCopyOption.REPLACE_EXISTING);UpdateHealth.installed(instance,target,backup,manifest);Files.copy(candidate,target,StandardCopyOption.REPLACE_EXISTING);String current=UpdateInstaller.digest(target);Files.writeString(backup,"tampered");try{UpdateHealth.rollback(instance,"test");throw new AssertionError("Unverified rollback accepted");}catch(java.io.IOException expected){}check(UpdateInstaller.digest(target).equals(current),"tampered previous build cannot replace the current client");
        System.out.println("Update health passed: verified rollback, original filenames, configuration recovery, release hold and healthy boot identity");
    }
}
