/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;
import hivemind.utils.network.ControlPackets.*;
import hivemind.utils.network.NetworkProtocol.Identity;
import java.util.*;
import static hivemind.utils.network.NetworkAtlas.quote;
/** Identical web contract for an embedded client or a standalone controller. */
public final class ControlView {
    public record History(UUID id,String description,List<UUID> expected,long time,List<Receipt> results){}
    private ControlView(){}
    public static String strings(Map<String,String> map){StringJoiner join=new StringJoiner(",","{","}");map.forEach((key,value)->join.add(quote(key)+":"+quote(value)));return join.toString();}
    public static String people(Map<UUID,String> map){Map<String,String> values=new LinkedHashMap<>();map.forEach((id,name)->values.put(id.toString(),name));return strings(values);}
    public static String json(Identity local,boolean host,boolean connected,String key,List<State> states,Set<UUID> live,
                              Map<String,String> defaults,Map<UUID,Boolean> trust,List<History> history,Map<String,String> controller){
        StringBuilder out=new StringBuilder("{\"ready\":true,\"local\":").append(quote(local.id().toString())).append(",\"host\":").append(host)
            .append(",\"connected\":").append(connected).append(",\"networkKey\":").append(quote(key)).append(",\"controller\":").append(strings(controller)).append(",\"bots\":[");
        StringJoiner list=new StringJoiner(",");
        for(State state:states)list.add("{\"id\":"+quote(state.reporter().toString())+",\"name\":"+quote(state.reporterName())+",\"status\":"+quote(state.status())
            +",\"online\":"+live.contains(state.reporter())+",\"health\":"+state.health()+",\"food\":"+state.food()+",\"dimension\":"+quote(state.point().dimension())+",\"x\":"+state.point().x()+",\"y\":"+state.point().y()+",\"z\":"+state.point().z()
            +",\"trusted\":"+people(state.trusted())+",\"settings\":"+strings(state.settings())+",\"players\":"+people(state.players())+",\"update\":"+strings(state.update())+",\"session\":"+strings(state.session())+",\"t\":"+state.observedAt()+"}");
        out.append(list).append("],\"schema\":[");list=new StringJoiner(",");
        for(var spec:NetworkSettings.all())list.add("{\"name\":"+quote(spec.name())+",\"group\":"+quote(spec.group())+",\"type\":"+quote(spec.type())+",\"min\":"+spec.min()+",\"max\":"+spec.max()+",\"choices\":["+spec.choices().stream().map(NetworkAtlas::quote).reduce((a,b)->a+","+b).orElse("")+"]}");
        out.append(list).append("],\"defaults\":").append(strings(defaults)).append(",\"trustDefaults\":{");StringJoiner trustList=new StringJoiner(",");trust.forEach((id,add)->trustList.add(quote(id.toString())+":"+add));
        out.append(trustList).append("},\"actions\":[");list=new StringJoiner(",");
        for(History request:history){StringJoiner replies=new StringJoiner(",");for(Receipt receipt:request.results())replies.add("{\"bot\":"+quote(receipt.reporter().toString())+",\"name\":"+quote(receipt.reporterName())+",\"ok\":"+receipt.success()+",\"message\":"+quote(receipt.message())+"}");
            list.add("{\"id\":"+quote(request.id().toString())+",\"description\":"+quote(request.description())+",\"t\":"+request.time()+",\"expected\":["+request.expected().stream().map(id->quote(id.toString())).reduce((a,b)->a+","+b).orElse("")+"],\"results\":["+replies+"]}");}
        return out.append(list).append("]}").toString();
    }
}
