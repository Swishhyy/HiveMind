/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;
import hivemind.utils.bot.AtomicProperties;
import java.nio.file.Path;
import java.util.*;
/** Local, persistent consent to automation. Never controlled by network defaults. */
public final class ClientRole {
    private final Path file;
    private boolean player;
    private final boolean locked;
    public ClientRole(Path root){this(root,distribution());}
    public ClientRole(Path root,String distribution){
        if(!Set.of("legacy","bot","player").contains(distribution))throw new IllegalArgumentException("Invalid client distribution");
        locked=distribution.equals("player");file=root.resolve("client-role.properties");String role=AtomicProperties.read(file).getProperty("role","bot");player=locked||!role.equals("bot");
    }
    public boolean locked(){return locked;}
    public void requireRole(String role){if(!Set.of("player","bot").contains(role))throw new IllegalArgumentException("Use client player/bot/status");if(locked&&!role.equals("player"))throw new IllegalArgumentException("The player JAR is locked to manual sharing. Install the standard HiveMind JAR on a bot account for automation.");}
    public static String distribution(){
        Properties p=new Properties();try(var in=ClientRole.class.getResourceAsStream("/hivemind-build.properties")){if(in!=null)p.load(in);}catch(java.io.IOException e){throw new IllegalStateException("Cannot read client distribution",e);}
        String value=p.getProperty("distribution","legacy");if(!Set.of("legacy","bot","player").contains(value))throw new IllegalStateException("Invalid client distribution");return value;
    }
    public boolean player(){return player;}
    public String mode(){return player?"player":"bot";}
    public void set(String role){requireRole(role);if(locked)return;Properties p=new Properties();p.setProperty("role",role);AtomicProperties.write(file,p);player=role.equals("player");}
    public static boolean player(Map<String,String> session){return session.getOrDefault("role","bot").equals("player");}
    public static boolean remoteAllowed(String label){return Set.of("status","update").contains(label);}
    public static boolean localAllowed(String label){return Set.of("backup","meet","bring","client","network","net","set","modified","mod","hivemind","modifiedsettings","reset","reloadall","saveall","help","version","status","trust","mark","mapmark","update","stop","cancel","pause","paused").contains(label);}
}
