/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import hivemind.utils.bot.StorageRule;
import hivemind.utils.network.ControlPackets.State;
import java.util.*;
/** Explicit live bot selection and dimension-pinned configuration commands from a manual client. */
public final class PlayerBotOrders {
    private PlayerBotOrders(){}
    public static List<State> targets(Collection<State> states,String selector,long now){
        var live=states.stream().filter(s->s.valid(now)&&now-s.observedAt()<=10000&&!ClientRole.player(s.session())&&!s.session().getOrDefault("connected","false").equals("false")).toList();
        if(selector.equalsIgnoreCase("all"))return live;
        var matches=live.stream().filter(s->s.reporterName().equalsIgnoreCase(selector)||s.reporter().toString().equalsIgnoreCase(selector)).toList();
        if(matches.isEmpty())throw new IllegalArgumentException("No fresh online bot named "+selector+"; use #bots list");
        if(matches.size()>1)throw new IllegalArgumentException("Several bots have that name; use the exact UUID from #bots list");return matches;
    }
    private static String point(Endpoint p){return p.dimension()+" "+p.x()+" "+p.y()+" "+p.z();}
    public static String base(Endpoint p,int radius){new BotBaseStore.Base(p,radius);return "botbase set "+point(p)+" "+radius;}
    public static String chest(String action,Endpoint p,String rule){
        if(!Set.of("add","remove").contains(action))throw new IllegalArgumentException("Use chest add/remove");
        if(rule!=null){var kind=StorageRule.parse(rule);if(kind==StorageRule.Kind.ITEMS||!action.equals("add"))throw new IllegalArgumentException("Use any/ores/building/junk/supplies/disabled for a chest rule");rule=kind.name().toLowerCase(Locale.ROOT);}
        return "deposit point "+action+" "+point(p)+(rule==null?"":" "+rule);
    }
}
