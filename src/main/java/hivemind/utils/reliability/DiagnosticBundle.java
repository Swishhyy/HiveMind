/* HiveMind, LGPL-3.0. */
package hivemind.utils.reliability;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

/** Explicit allowlist: credentials, controller keys, chat, launcher files and arbitrary logs are never included. */
public final class DiagnosticBundle {
    private DiagnosticBundle(){}
    public static Path create(Path destination,Path world,UUID account,Map<String,String> details,List<String> trace)throws java.io.IOException{
        Files.createDirectories(destination);Path output=destination.resolve("hivemind-support-"+System.currentTimeMillis()+".zip");
        try(var zip=new ZipOutputStream(Files.newOutputStream(output,StandardOpenOption.CREATE_NEW))){
            StringBuilder report=new StringBuilder("HiveMind diagnostic report\nGenerated "+Instant.now()+"\n");details.forEach((k,v)->report.append(k).append(": ").append(v).append('\n'));report.append("\nRecent decisions\n");trace.forEach(line->report.append(line).append('\n'));write(zip,"report.txt",report.toString().getBytes(StandardCharsets.UTF_8));
            for(String name:List.of("task-runtime-"+account+".properties","inventory-transaction-"+account+".properties","delivery-runtime-"+account+".properties","shulker-manifest-"+account+".properties","bot-activity-"+account+".properties","route-health.properties","behavior-regions.properties")){
                Path file=world.resolve(name);if(!Files.isRegularFile(file)||Files.isSymbolicLink(file)||Files.size(file)>262144)continue;write(zip,name,Files.readAllBytes(file));
            }
        }return output;
    }
    private static void write(ZipOutputStream zip,String name,byte[] content)throws java.io.IOException{zip.putNextEntry(new ZipEntry(name));zip.write(content);zip.closeEntry();}
}
