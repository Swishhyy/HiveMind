/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;
import hivemind.utils.bot.AtomicProperties;
import java.nio.file.Path;
import java.util.*;
/** Local, persistent consent to automation. Never controlled by network defaults. */
public final class ClientRole {
    private final Path file;
    private boolean player;
    public ClientRole(Path root){file=root.resolve("client-role.properties");String role=AtomicProperties.read(file).getProperty("role","bot");player=!role.equals("bot");}
    public boolean player(){return player;}
    public String mode(){return player?"player":"bot";}
    public void set(String role){if(!Set.of("player","bot").contains(role))throw new IllegalArgumentException("Use client player/bot/status");Properties p=new Properties();p.setProperty("role",role);AtomicProperties.write(file,p);player=role.equals("player");}
    public static boolean player(Map<String,String> session){return session.getOrDefault("role","bot").equals("player");}
    public static boolean remoteAllowed(String label){return Set.of("status","update").contains(label);}
    public static boolean localAllowed(String label){return Set.of("client","network","net","set","modified","mod","hivemind","modifiedsettings","reset","reloadall","saveall","help","version","status","trust","mark","mapmark","update","stop","cancel","pause","paused").contains(label);}
}
