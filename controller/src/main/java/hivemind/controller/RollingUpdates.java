/* HiveMind, LGPL-3.0. */
package hivemind.controller;
import hivemind.utils.bot.AtomicProperties;
import hivemind.utils.network.*;
import hivemind.utils.network.ControlPackets.State;
import java.nio.file.Path;
import java.util.*;

/** One canary at a time. A failed or unverified boot pauses the rollout before any further install. */
public final class RollingUpdates {
    public enum Phase { IDLE, CHECK, WAIT_CHECK, STAGE, WAIT_STAGE, WAIT_RESTART, VERIFY, PAUSED, COMPLETE }
    public record Command(UUID target,String command){}
    private final Path file;
    private List<UUID> queue=List.of();
    private int index;
    private Phase phase=Phase.IDLE;
    private String version="",reason="No rollout requested";
    private long since,healthySince;
    public RollingUpdates(Path root){file=root.resolve("update-rollout.properties");Properties p=AtomicProperties.read(file);String saved=p.getProperty("bots","");if(!saved.isBlank())queue=Arrays.stream(saved.split(",")).map(UUID::fromString).toList();if(queue.size()>64)throw new IllegalStateException("Too many rollout clients");index=Integer.parseInt(p.getProperty("index","0"));version=p.getProperty("version","");if(index<0||index>queue.size())throw new IllegalStateException("Invalid rollout checkpoint");Phase old=Phase.valueOf(p.getProperty("phase","IDLE"));phase=Set.of(Phase.IDLE,Phase.COMPLETE).contains(old)?old:Phase.PAUSED;reason=phase==Phase.PAUSED?"Controller restarted; inspect the canary and resume the rollout":p.getProperty("reason",reason);}
    public Phase phase(){return phase;}
    public String status(){return phase.name().toLowerCase(Locale.ROOT)+" · "+Math.min(index+1,queue.size())+"/"+queue.size()+(version.isBlank()?"":" · "+version)+" · "+reason;}
    public void start(Collection<State> states,Collection<OperationsPackets.Snapshot> observations,long now){
        if(!Set.of(Phase.IDLE,Phase.COMPLETE,Phase.PAUSED).contains(phase))throw new IllegalStateException("A rollout is already running");
        Map<UUID,OperationsPackets.Snapshot> ops=new HashMap<>();observations.stream().filter(p->p.valid(now)).forEach(p->ops.put(p.reporter(),p));
        queue=states.stream().filter(s->s.valid(now)&&!ClientRole.player(s.session())&&Boolean.parseBoolean(s.session().getOrDefault("connected","true")))
            .sorted(Comparator.comparingInt((State s)->s.health()>=14&&s.food()>=14&&ops.containsKey(s.reporter())?0:1).thenComparing(State::reporterName)).map(State::reporter).toList();
        if(queue.isEmpty())throw new IllegalStateException("No connected autonomous clients; player clients update individually");
        index=0;version="";healthySince=0;transition(Phase.CHECK,"Checking the first canary",now);
    }
    public void pause(String why,long now){transition(Phase.PAUSED,why,now);}
    public void resume(long now){if(phase!=Phase.PAUSED)throw new IllegalStateException("Rollout is not paused");healthySince=0;transition(version.isBlank()?Phase.CHECK:Phase.WAIT_RESTART,"Rechecking the canary before proceeding",now);}
    public Command tick(Collection<State> states,long now){
        if(Set.of(Phase.IDLE,Phase.PAUSED,Phase.COMPLETE).contains(phase))return null;
        if(index>=queue.size()){transition(Phase.COMPLETE,"All selected bots verified",now);return null;}
        UUID id=queue.get(index);State bot=states.stream().filter(s->s.reporter().equals(id)&&s.valid(now)).findFirst().orElse(null);
        if(bot==null){healthySince=0;if(now-since>600000)pause("Selected client did not reconnect; remaining clients are untouched",now);return null;}
        String installed=bot.update().getOrDefault("version",""),available=bot.update().getOrDefault("available",""),status=bot.update().getOrDefault("status","");
        if(!version.isBlank()&&version.equals(bot.update().get("pinned"))){pause("Canary rolled back and pinned the failed release",now);return null;}
        switch(phase){
            case CHECK -> {if(Boolean.parseBoolean(bot.update().getOrDefault("restart","false"))&&!available.isBlank()){if(version.isBlank())version=available;if(!version.equals(available)){pause("Staged version changed; inspect the canary release",now);break;}transition(Phase.STAGE,"A verified update is already staged; authorizing one restart",now);}else{transition(Phase.WAIT_CHECK,"Waiting for a fresh release check",now);return new Command(id,"update check");}}
            case WAIT_CHECK -> {
                long checked=parse(bot.update().get("checked"));
                if(checked>=since&&!available.isBlank()){
                    if(version.isBlank())version=available;
                    if(!version.equals(available)){pause("Release changed during rollout; start a new canary check",now);break;}
                    transition(installed.equals(version)?Phase.VERIFY:Phase.STAGE,installed.equals(version)?"Checking an already updated canary":"Release verified; preparing one client",now);healthySince=0;
                }else if(now-since>120000)pause("Release check did not complete",now);
            }
            case STAGE -> {
                if(bot.health()<10||bot.food()<8||!Boolean.parseBoolean(bot.session().getOrDefault("connected","true")))break;
                transition(Phase.WAIT_STAGE,"Downloading one client update",now);
                return new Command(id,"update install"+(bot.session().getOrDefault("capabilities","").contains("reliability1")?" "+version:""));
            }
            case WAIT_STAGE -> {
                if(installed.equals(version)){healthySince=0;transition(Phase.VERIFY,"Updated client reconnected",now);}
                else if(Boolean.parseBoolean(bot.update().getOrDefault("restart","false"))){if(!version.equals(available)){pause("Staged version differs from the canary release",now);break;}transition(Phase.WAIT_RESTART,"Waiting for a safe supervised restart or a manual restart",now);}
                else if(status.toLowerCase(Locale.ROOT).contains("failed")||now-since>180000)pause("Client could not stage the verified release: "+status,now);
            }
            case WAIT_RESTART -> {
                if(installed.equals(version)&&Boolean.parseBoolean(bot.session().getOrDefault("connected","true"))){healthySince=0;transition(Phase.VERIFY,"Checking the updated client for 60 seconds",now);}
                else if(now-since>600000)pause("Canary restart/reconnect was not verified; remaining clients are untouched",now);
            }
            case VERIFY -> {
                boolean healthy=installed.equals(version)&&Boolean.parseBoolean(bot.session().getOrDefault("connected","true"))&&bot.health()>=10&&bot.food()>=8;
                if(!healthy){healthySince=0;if(now-since>300000)pause("Canary did not remain healthy",now);}
                else if(healthySince==0)healthySince=now;
                else if(now-healthySince>=60000){index++;healthySince=0;transition(index>=queue.size()?Phase.COMPLETE:Phase.CHECK,index>=queue.size()?"All selected bots verified":"Canary healthy; proceeding to the next client",now);}
            }
            default -> {}
        }return null;
    }
    private static long parse(String value){try{return Long.parseLong(value);}catch(RuntimeException error){return 0;}}
    private void transition(Phase next,String why,long now){phase=next;reason=why;since=now;save();}
    private void save(){Properties p=new Properties();p.setProperty("schema","1");p.setProperty("bots",queue.stream().map(UUID::toString).reduce((a,b)->a+","+b).orElse(""));p.setProperty("index",""+index);p.setProperty("version",version);p.setProperty("phase",phase.name());p.setProperty("reason",reason);AtomicProperties.write(file,p);}
}
