/* HiveMind, LGPL-3.0. */
package hivemind.controller;
import hivemind.utils.network.*;
import hivemind.utils.network.ControlPackets.State;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import java.nio.file.*;
import java.util.*;

public final class RollingUpdateChecks {
    static final UUID A=UUID.nameUUIDFromBytes("Alpha".getBytes()),B=UUID.nameUUIDFromBytes("Bravo".getBytes()),PLAYER=UUID.nameUUIDFromBytes("Human".getBytes());
    static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
    static State state(UUID id,String installed,String available,boolean restart,String status,long checked,long now,String pinned){return new State(id,id.equals(A)?"Alpha":id.equals(B)?"Bravo":"Human","Ready",20,20,new Endpoint("minecraft:overworld",0,64,0),Map.of(),Map.of(),Map.of(),0,now,Map.of("version",installed,"available",available,"restart",""+restart,"status",status,"checked",""+checked,"pinned",pinned),Map.of("role",id.equals(PLAYER)?"player":"bot","connected","true","capabilities","reliability1"));}
    static List<State> states(String av,String bv,boolean staged,long now){return List.of(state(A,av,"0.1.2.1",staged,"Ready",now,now,""),state(B,bv,"0.1.2.1",staged,"Ready",now,now,""),state(PLAYER,"0.1.2.0","0.1.2.1",true,"Ready",now,now,""));}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("hivemind-rollout");RollingUpdates rollout=new RollingUpdates(root);long now=100000;
        rollout.start(states("0.1.2.0","0.1.2.0",true,now),List.of(),now);
        check(rollout.tick(states("0.1.2.0","0.1.2.0",true,now),now)==null&&rollout.phase()==RollingUpdates.Phase.STAGE,"staged automatic download still needs a canary grant");
        var first=rollout.tick(states("0.1.2.0","0.1.2.0",true,now+1),now+1);check(first.target().equals(A)&&first.command().equals("update install 0.1.2.1"),"only the first bot receives a version-pinned install grant");
        rollout.tick(states("0.1.2.0","0.1.2.0",true,now+2),now+2);check(rollout.phase()==RollingUpdates.Phase.WAIT_RESTART,"no second client before verified restart");
        check(new RollingUpdates(root).phase()==RollingUpdates.Phase.PAUSED,"controller restart cannot silently continue a rollout");
        rollout.tick(states("0.1.2.1","0.1.2.0",true,now+1000),now+1000);rollout.tick(states("0.1.2.1","0.1.2.0",true,now+1001),now+1001);
        rollout.tick(states("0.1.2.1","0.1.2.0",true,now+30000),now+30000);check(rollout.phase()==RollingUpdates.Phase.VERIFY,"a brief healthy reconnect is insufficient");
        rollout.tick(states("0.1.2.1","0.1.2.0",true,now+62000),now+62000);rollout.tick(states("0.1.2.1","0.1.2.0",true,now+62001),now+62001);
        var second=rollout.tick(states("0.1.2.1","0.1.2.0",true,now+62002),now+62002);check(second!=null&&second.target().equals(B),"next bot waits until the canary stays healthy for 60 seconds");
        rollout.tick(states("0.1.2.1","0.1.2.0",true,now+62003),now+62003);
        var failed=List.of(state(A,"0.1.2.1","0.1.2.1",false,"Ready",now,now+63000,""),state(B,"0.1.2.0","0.1.2.1",false,"Rolled back",now,now+63000,"0.1.2.1"));
        rollout.tick(failed,now+63000);check(rollout.phase()==RollingUpdates.Phase.PAUSED,"failed canary is held without proceeding to other clients");
        check(rollout.tick(failed,now+70000)==null,"paused rollout issues no further commands");
        System.out.println("Rolling updates passed: canary grants, stable health, restart pause, failed-release hold and manual-player exclusion");
    }
}
