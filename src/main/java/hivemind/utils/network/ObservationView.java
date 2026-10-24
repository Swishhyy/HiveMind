/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;
import java.util.*;
/** ESP contacts are live hints, separate from historical visual observations and hotspots. */
public final class ObservationView {
    private ObservationView(){}
    public static String json(String atlas,Collection<ControlPackets.Contact> contacts,long now){StringJoiner list=new StringJoiner(",","[","]");contacts.stream().filter(c->c.valid(now)).limit(512).forEach(c->{var p=c.point();list.add("{\"id\":"+NetworkAtlas.quote(c.player().toString())+",\"name\":"+NetworkAtlas.quote(c.playerName())+",\"source\":"+NetworkAtlas.quote(c.reporterName())+",\"d\":"+NetworkAtlas.quote(p.dimension())+",\"x\":"+p.x()+",\"y\":"+p.y()+",\"z\":"+p.z()+",\"t\":"+c.observedAt()+",\"evidence\":\"esp\",\"ageMs\":"+Math.max(0,now-c.observedAt())+",\"confidence\":\"server-loaded hint\"}");});return atlas.substring(0,atlas.length()-1)+",\"contacts\":"+list+"}";}
}
