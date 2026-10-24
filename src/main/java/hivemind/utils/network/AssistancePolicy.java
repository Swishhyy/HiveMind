/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;
import hivemind.utils.network.ControlPackets.State;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import java.util.*;
/** Assistance only borrows a healthy, idle autonomous client in the requester's dimension. */
public final class AssistancePolicy {
    private AssistancePolicy(){}
    private static int number(String text){try{return Math.max(0,Integer.parseInt(text));}catch(RuntimeException e){return 0;}}
    public static UUID supply(Collection<State> states,Collection<OperationsPackets.Snapshot> snapshots,Endpoint point,String item,int quantity,long now){
        Map<UUID,OperationsPackets.Snapshot> ops=new HashMap<>();snapshots.stream().filter(p->p.valid(now)).forEach(p->ops.put(p.reporter(),p));
        return states.stream().filter(s->s.valid(now)&&!ClientRole.player(s.session())&&Boolean.parseBoolean(s.session().getOrDefault("connected","true"))&&s.health()>=14&&s.food()>=14&&s.point().dimension().equals(point.dimension()))
            .filter(s->Math.hypot((double)s.point().x()-point.x(),(double)s.point().z()-point.z())<=4096)
            .filter(s->{var p=ops.get(s.reporter());return p!=null&&Boolean.parseBoolean(p.details().getOrDefault("available","false"))&&(item.equals("minecraft:firework_rocket")?number(p.details().get("spareRockets")):item.equals(p.details().get("foodItem"))?number(p.details().get("spareFood")):0)>=quantity;})
            .min(Comparator.comparingDouble(s->{var p=ops.get(s.reporter());double distance=Math.hypot((double)s.point().x()-point.x(),(double)s.point().z()-point.z());return distance/(Boolean.parseBoolean(p.details().getOrDefault("flightReady","false"))?3:1)+(20-s.health())*16;})).map(State::reporter).orElseThrow(()->new IllegalStateException("No idle bot has spare confirmed supplies"));
    }
    public static UUID choose(Collection<State> states,Collection<OperationsPackets.Snapshot> snapshots,Endpoint point,long now){Map<UUID,OperationsPackets.Snapshot> ops=new HashMap<>();snapshots.forEach(p->ops.put(p.reporter(),p));return states.stream().filter(s->s.valid(now)&&!ClientRole.player(s.session())&&Boolean.parseBoolean(s.session().getOrDefault("connected","true"))&&s.health()>=10&&s.food()>=8&&s.point().dimension().equals(point.dimension()))
        .filter(s->{var p=ops.get(s.reporter());return p!=null&&p.valid(now)&&Boolean.parseBoolean(p.details().getOrDefault("available","false"));})
        .min(Comparator.comparingDouble(s->Math.hypot((double)s.point().x()-point.x(),(double)s.point().z()-point.z()))).map(State::reporter).orElseThrow(()->new IllegalStateException("No healthy idle bot is available in this dimension"));}
}
