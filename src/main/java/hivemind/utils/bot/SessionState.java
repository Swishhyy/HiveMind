/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;
import java.nio.file.Path;
import java.util.*;
/** Client-local connection intent survives server disconnects and game restarts. No credentials are stored. */
public final class SessionState {
    private final Path file;
    private String server,name,status="Waiting for first server join";
    private boolean connected,disconnectRequested,connectRequested;
    private long holdUntil,nextAttempt,damageAt,joinedAt;
    private int attempts,hostilePlayers;
    private boolean automatic=true;
    public SessionState(Path instance){
        file=instance.resolve("hivemind/session.properties");Properties p=AtomicProperties.read(file);
        server=p.getProperty("server","");name=p.getProperty("name","Minecraft server");
        holdUntil=Long.parseLong(p.getProperty("holdUntil","0"));nextAttempt=0;
    }
    public String server(){return server;}public String name(){return name;}
    public boolean connected(){return connected;}
    public String status(){return status;}
    public void automatic(boolean value){automatic=value;}
    public void observe(boolean online,String address,String label,long now,long retryDelay){
        if(online){
            if(address!=null&&!address.isBlank()&&(!address.equals(server)||!Objects.equals(label,name))){server=address;name=label;save();}
            if(!connected){joinedAt=now;holdUntil=0;connectRequested=false;status="Connected";save();}
            connected=true;
            if(now-joinedAt>60000)attempts=0;
        }else if(connected){connected=false;if(nextAttempt<now)nextAttempt=now+retryDelay;status=holdUntil>now?"Logged out; waiting":"Disconnected; waiting to reconnect";}
    }
    public void damage(long now){damageAt=now;}
    public void hostiles(int count){hostilePlayers=Math.max(0,Math.min(256,count));}
    public String command(String raw,long now){
        String[] parts=raw.strip().replaceFirst("^#?session\\s*", "").split("\\s+");
        String action=parts.length==0?"status":parts[0].toLowerCase(Locale.ROOT);
        if(action.isBlank())action="status";
        switch(action){
            case "status" -> {if(parts.length>1)throw new IllegalArgumentException("Use session status");}
            case "logout","disconnect" -> {
                if(parts.length>2)throw new IllegalArgumentException("Use session logout [seconds]");
                long seconds=parts.length==2?Long.parseLong(parts[1]):-1;
                if(seconds!= -1&&(seconds<5||seconds>86400))throw new IllegalArgumentException("Reconnect wait must be 5-86400 seconds");
                holdUntil=seconds<0?Long.MAX_VALUE:now+seconds*1000;nextAttempt=holdUntil;disconnectRequested=true;connectRequested=false;
                status=seconds<0?"Staying offline until reconnect is requested":"Logging out for "+seconds+" seconds";save();
            }
            case "connect","reconnect","login" -> {
                if(parts.length>1)throw new IllegalArgumentException("Use session reconnect");
                if(server.isBlank())throw new IllegalStateException("Join a multiplayer server once before reconnecting remotely");
                if(connected)return "Already connected";
                holdUntil=0;nextAttempt=now;connectRequested=true;disconnectRequested=false;status="Reconnect requested";save();
            }
            default -> throw new IllegalArgumentException("Use session status/logout [seconds]/reconnect");
        }
        return status;
    }
    public boolean takeDisconnect(){boolean value=disconnectRequested;disconnectRequested=false;return value;}
    public boolean shouldConnect(long now,boolean controllerConnected){return !connected&&!server.isBlank()&&now>=holdUntil&&now>=nextAttempt&&(connectRequested||automatic&&!controllerConnected);}
    public void attempted(long now,long baseDelay){
        attempts=Math.min(10,attempts+1);connectRequested=false;
        nextAttempt=now+Math.min(300000,Math.max(5000,baseDelay)*(1L<<Math.min(5,attempts-1)));
        status="Connecting; retry "+attempts+" if needed";
    }
    public void failed(String message){status=message.substring(0,Math.min(128,message.length()));}
    public Map<String,String> snapshot(){
        return Map.of("connected",""+connected,"server",server.substring(0,Math.min(128,server.length())),"status",status,"holdUntil",""+holdUntil,"nextAttempt",""+nextAttempt,
            "damageAt",""+damageAt,"hostiles",""+hostilePlayers,"attempts",""+attempts,"automatic",""+automatic);
    }
    private void save(){Properties p=new Properties();p.setProperty("server",server);p.setProperty("name",Objects.toString(name,"Minecraft server"));p.setProperty("holdUntil",""+holdUntil);AtomicProperties.write(file,p);}
}
