/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;
import hivemind.utils.network.ControlPackets.State;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import java.util.*;
/** Assistance only borrows a healthy, idle autonomous client in the requester's dimension. */
public final class AssistancePolicy {
    private AssistancePolicy(){}
    public static UUID choose(Collection<State> states,Collection<OperationsPackets.Snapshot> snapshots,Endpoint point,long now){Map<UUID,OperationsPackets.Snapshot> ops=new HashMap<>();snapshots.forEach(p->ops.put(p.reporter(),p));return states.stream().filter(s->s.valid(now)&&!ClientRole.player(s.session())&&Boolean.parseBoolean(s.session().getOrDefault("connected","true"))&&s.health()>=10&&s.food()>=8&&s.point().dimension().equals(point.dimension()))
        .filter(s->{var p=ops.get(s.reporter());return p!=null&&p.valid(now)&&Boolean.parseBoolean(p.details().getOrDefault("available","false"));})
        .min(Comparator.comparingDouble(s->Math.hypot((double)s.point().x()-point.x(),(double)s.point().z()-point.z()))).map(State::reporter).orElseThrow(()->new IllegalStateException("No healthy idle bot is available in this dimension"));}
}
