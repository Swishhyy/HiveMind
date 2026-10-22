/* HiveMind, LGPL-3.0. */
package hivemind.utils.update;
import hivemind.utils.bot.AtomicProperties;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class UpdateChecks {
    private static final String COMMIT="a".repeat(40);
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void rejected(Throwing operation,String message)throws Exception{try{operation.run();}catch(IOException|IllegalArgumentException e){return;}throw new AssertionError(message);}
    private interface Throwing{void run()throws Exception;}
    private static Path release(Path directory,String version)throws Exception{return release(directory,version,"legacy");}
    private static Path release(Path directory,String version,String distribution)throws Exception{
        Path jar=directory.resolve("release-"+distribution+".jar");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(jar))){
            zip.putNextEntry(new ZipEntry("hivemind-build.properties"));zip.write(("application=hivemind\nversion="+version+"\nminecraft=26.3\nloader=fabric\ncommit="+COMMIT+"\ndistribution="+distribution+"\n").getBytes());zip.closeEntry();
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));zip.write("{\"id\":\"hivemind\"}".getBytes());zip.closeEntry();
            zip.putNextEntry(new ZipEntry("payload"));byte[] bytes=new byte[4096];new Random(42).nextBytes(bytes);zip.write(bytes);zip.closeEntry();
        }
        return jar;
    }
    private static void edition(String edition)throws Exception{
        check(hivemind.utils.network.ClientRole.distribution().equals(edition),"Runtime did not read packaged edition metadata");
        Path root=Files.createTempDirectory("hivemind-edition-"+edition);Path mods=Files.createDirectories(root.resolve("mods")),updates=Files.createDirectories(root.resolve("hivemind/updates"));
        var local=new hivemind.utils.network.ClientRole(root);check(local.player()==edition.equals("player"),"Wrong fresh-install role");
        if(edition.equals("player"))rejected(()->local.set("bot"),"Player package allowed automation");
        Path target=mods.resolve("custom-installed-name.jar");Files.move(release(mods,"0.1.0.0",edition),target);byte[] old=Files.readAllBytes(target);
        Path staged=release(updates,"0.1.0.1",edition);byte[] data=Files.readAllBytes(staged);
        var manifest=new UpdateManifest("0.1.0.1",COMMIT,Files.size(staged),UpdateInstaller.digest(staged),URI.create(UpdateManifest.REPOSITORY+"/releases/download/v0.1.0.1/"+(edition.equals("player")?"hivemind-player-":"hivemind-")+"0.1.0.1.jar"),edition);
        java.util.concurrent.atomic.AtomicReference<URI> requested=new java.util.concurrent.atomic.AtomicReference<>();
        try(UpdateService service=new UpdateService(root,target,"0.1.0.0",(uri,limit)->{requested.set(uri);return ("application=hivemind\nloader=fabric\nminecraft=26.3\nversion="+manifest.version()+"\ncommit="+COMMIT+"\nsize="+manifest.size()+"\nsha256="+manifest.sha256()+"\nurl="+manifest.url()+"\ndistribution="+edition+"\n").getBytes();})){
            service.check(false);for(int i=0;i<400&&!service.snapshot().get("status").startsWith("Available");i++)Thread.sleep(5);
            check(UpdateManifest.latest(edition).equals(requested.get())&&service.snapshot().get("available").equals(manifest.version()),"Updater fetched the wrong edition channel");
        }
        Properties plan=new Properties();plan.setProperty("instance",root.toString());plan.setProperty("target",target.toString());plan.setProperty("staged",staged.toString());plan.setProperty("pid",""+ProcessHandle.current().pid());plan.setProperty("version",manifest.version());plan.setProperty("commit",COMMIT);plan.setProperty("size",""+manifest.size());plan.setProperty("sha256",manifest.sha256());plan.setProperty("url",manifest.url().toString());plan.setProperty("distribution",edition);
        Path journal=updates.resolve("install.properties");AtomicProperties.write(journal,plan);UpdateInstaller.apply(journal,false);
        check(Arrays.equals(data,Files.readAllBytes(target))&&!Arrays.equals(old,data)&&UpdateInstaller.distribution(target).equals(edition),"Edition update did not replace the actual installed filename");
        try(var files=Files.list(updates.resolve("backups"))){check(Arrays.equals(old,Files.readAllBytes(files.findFirst().orElseThrow())),"Edition backup differs from original");}
        System.out.println("Edition checks passed: "+edition+" packaged role, updater channel and verified install preserving the current filename");
    }
    public static void main(String[] args)throws Exception{
        if(args.length==2&&args[0].equals("--edition")){edition(args[1]);return;}

        Path root=Files.createTempDirectory("hivemind-updates");Path mods=Files.createDirectories(root.resolve("mods")),updates=Files.createDirectories(root.resolve("hivemind/updates"));
        Path release=release(updates,"0.1.0.1");
        UpdateManifest manifest=new UpdateManifest("0.1.0.1",COMMIT,Files.size(release),UpdateInstaller.digest(release),URI.create(UpdateManifest.REPOSITORY+"/releases/download/v0.1.0.1/hivemind-0.1.0.1.jar"));
        check(manifest.newerThan("0.1.0.0")&&!manifest.newerThan("0.1.0.1")&&!manifest.newerThan("0.2.0.0"),"No downgrades or duplicate updates");
        check(UpdateManifest.compare("0.1.0.10","0.1.0.9")>0,"Numeric version comparison");
        UpdateInstaller.verify(release,manifest);
        check(UpdateManifest.latest("bot").equals(UpdateManifest.LATEST)&&!UpdateManifest.latest("player").equals(UpdateManifest.LATEST),"Bot update URL must stay compatible while player updates use a separate channel");
        UpdateInstaller.compatible("legacy","bot");UpdateInstaller.compatible("player","player");
        rejected(()->UpdateInstaller.compatible("player","bot"),"Player switched to bot during update");rejected(()->UpdateInstaller.compatible("bot","player"),"Bot switched to player during update");
        var wrongRole=new UpdateManifest(manifest.version(),COMMIT,manifest.size(),manifest.sha256(),manifest.url(),"player");rejected(()->UpdateInstaller.verify(release,wrongRole),"Manifest accepted a jar from the wrong distribution");
        rejected(()->new UpdateManifest(manifest.version(),COMMIT,manifest.size(),manifest.sha256(),URI.create("https://example.com/update.jar")),"Foreign release URL accepted");
        rejected(()->UpdateManifest.read("application=hivemind\nminecraft=26.2\nloader=fabric\n".getBytes()),"Wrong Minecraft version accepted");
        byte[] data=Files.readAllBytes(release);byte[] corrupt=data.clone();corrupt[corrupt.length/2]^=1;Path bad=updates.resolve("bad.jar");Files.write(bad,corrupt);
        rejected(()->UpdateInstaller.verify(bad,manifest),"Tampered download accepted");
        Path target=mods.resolve("hivemind.jar");Files.move(release(mods,"0.1.0.0"),target);byte[] old=Files.readAllBytes(target);
        Properties plan=new Properties();plan.setProperty("instance",root.toString());plan.setProperty("target",target.toString());plan.setProperty("staged",release.toString());plan.setProperty("pid",""+ProcessHandle.current().pid());
        plan.setProperty("version",manifest.version());plan.setProperty("commit",COMMIT);plan.setProperty("size",""+manifest.size());plan.setProperty("sha256",manifest.sha256());plan.setProperty("url",manifest.url().toString());
        Path journal=updates.resolve("install.properties");AtomicProperties.write(journal,plan);
        plan.setProperty("target",root.resolve("outside.jar").toString());AtomicProperties.write(journal,plan);
        rejected(()->UpdateInstaller.apply(journal,false),"Outside-mods replacement accepted");
        check(Arrays.equals(Files.readAllBytes(target),old),"Invalid plan changed installed mod");
        plan.setProperty("target",target.toString());AtomicProperties.write(journal,plan);UpdateInstaller.apply(journal,false);
        check(Arrays.equals(Files.readAllBytes(target),data)&&!Files.exists(journal)&&!Files.exists(release),"Verified update not atomically installed/cleaned");
        try(var backups=Files.list(updates.resolve("backups"))){check(Arrays.equals(Files.readAllBytes(backups.findFirst().orElseThrow()),old),"Old mod not backed up");}
        AtomicInteger requests=new AtomicInteger();UpdateService.Fetcher fetcher=(uri,limit)->{requests.incrementAndGet();return ("application=hivemind\nloader=fabric\nminecraft=26.3\nversion=0.1.0.1\ncommit="+COMMIT+"\nsize="+manifest.size()+"\nsha256="+manifest.sha256()+"\nurl="+manifest.url()+"\n").getBytes();};
        try(UpdateService service=new UpdateService(root,target,"0.1.0.0",fetcher)){
            service.pulse();check(requests.get()==0&&!service.asked(),"Updates ran without first-join consent");service.consent(false);check(service.asked(),"Consent was not recorded");service.check(false);
            for(int i=0;i<200&&requests.get()==0;i++)Thread.sleep(5);
            for(int i=0;i<200&&!service.snapshot().get("status").startsWith("Available");i++)Thread.sleep(5);
            check(service.snapshot().get("available").equals("0.1.0.1")&&!Boolean.parseBoolean(service.snapshot().get("restart")),"Manual check installed update");
        }
        try(UpdateService service=new UpdateService(root,target,"0.1.0.0",fetcher)){check(service.asked()&&service.snapshot().get("automatic").equals("false"),"Per-client choice did not survive restart");}
        for(String edition:List.of("bot","player")){
            Path identity=Files.createDirectories(root.resolve("identity-"+edition));Files.writeString(identity.resolve("hivemind-build.properties"),"distribution="+edition+"\n");
            Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java");
            int code=new ProcessBuilder(java.toString(),"-cp",identity+File.pathSeparator+System.getProperty("java.class.path"),UpdateChecks.class.getName(),"--edition",edition).inheritIO().start().waitFor();check(code==0,"Edition checks failed: "+edition);
        }
        System.out.println("Update checks passed: consent persistence, numeric versions, manifest compatibility, checksum tampering, path restrictions, atomic replacement and backup");
    }
}
