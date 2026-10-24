/* HiveMind, LGPL-3.0. */
package hivemind.utils.reliability;
import hivemind.utils.bot.AtomicProperties;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import java.nio.file.Path;
import java.util.*;

/** Repeated deaths retain the objective but require a regroup interval, including across client restarts. */
public final class DeathCircuit {
    private final Path file;
    private Endpoint area;
    private final Deque<Long> deaths=new ArrayDeque<>();
    private long until;
    public DeathCircuit(Path root,UUID account){file=root.resolve("death-circuit-"+account+".properties");Properties p=AtomicProperties.read(file);until=Long.parseLong(p.getProperty("until","0"));if(p.containsKey("dimension"))area=new Endpoint(p.getProperty("dimension"),Integer.parseInt(p.getProperty("x")),Integer.parseInt(p.getProperty("y")),Integer.parseInt(p.getProperty("z")));for(String time:p.getProperty("deaths","").split(","))if(!time.isBlank())deaths.add(Long.parseLong(time));if(deaths.size()>10)throw new IllegalStateException("Invalid death history");}
    public long until(){return until;}
    public boolean held(long now){return now<until;}
    public void resume(){until=0;deaths.clear();save();}
    public boolean died(Endpoint point,long now){if(area==null||!area.dimension().equals(point.dimension())||Math.hypot((double)area.x()-point.x(),(double)area.z()-point.z())>96)deaths.clear();area=point;while(!deaths.isEmpty()&&now-deaths.peekFirst()>600000)deaths.removeFirst();deaths.addLast(now);while(deaths.size()>10)deaths.removeFirst();boolean tripped=deaths.size()>=3;if(tripped)until=now+300000;save();return tripped;}
    private void save(){Properties p=new Properties();p.setProperty("schema","1");p.setProperty("until",""+until);if(area!=null){p.setProperty("dimension",area.dimension());p.setProperty("x",""+area.x());p.setProperty("y",""+area.y());p.setProperty("z",""+area.z());}p.setProperty("deaths",deaths.stream().map(Object::toString).reduce((a,b)->a+","+b).orElse(""));AtomicProperties.write(file,p);}
}
