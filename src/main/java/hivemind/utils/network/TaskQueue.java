/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;
import hivemind.utils.bot.AtomicProperties;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import java.nio.file.Path;
import java.util.*;
/** Durable, bounded map itinerary. Mutations commit before changing the in-memory queue. */
public final class TaskQueue {
    public record Task(long id,String kind,Endpoint point,int radius){
        public Task {Objects.requireNonNull(point);if(id<1||!Set.of("go","explore","check","return").contains(kind)||kind.equals("explore")&&(radius<32||radius>2048)||!kind.equals("explore")&&radius!=0)throw new IllegalArgumentException("Invalid map task");}
    }
    private final Path file;
    private List<Task> tasks;
    private boolean paused;
    private long next;
    public TaskQueue(Path root){file=root.resolve("map-itinerary.properties");Properties p=AtomicProperties.read(file);paused=Boolean.parseBoolean(p.getProperty("paused","false"));next=Long.parseLong(p.getProperty("next","1"));int count=Integer.parseInt(p.getProperty("count","0"));if(count<0||count>32||next<1)throw new IllegalStateException("Invalid map itinerary");List<Task> loaded=new ArrayList<>();Set<Long> ids=new HashSet<>();
        for(int i=0;i<count;i++){String[] v=p.getProperty("task."+i,"").split(" ");if(v.length!=7)throw new IllegalStateException("Invalid saved map task");Task t=new Task(Long.parseLong(v[0]),v[1],new Endpoint(v[2],Integer.parseInt(v[3]),Integer.parseInt(v[4]),Integer.parseInt(v[5])),Integer.parseInt(v[6]));if(!ids.add(t.id())||t.id()>=next)throw new IllegalStateException("Invalid task identity");loaded.add(t);}tasks=List.copyOf(loaded);
    }
    public List<Task> tasks(){return tasks;}public boolean paused(){return paused;}public Task first(){return tasks.isEmpty()?null:tasks.get(0);}
    private void save(List<Task> list,boolean held,long sequence){Properties p=new Properties();p.setProperty("paused",""+held);p.setProperty("next",""+sequence);p.setProperty("count",""+list.size());for(int i=0;i<list.size();i++){Task t=list.get(i);Endpoint e=t.point();p.setProperty("task."+i,t.id()+" "+t.kind()+" "+e.dimension()+" "+e.x()+" "+e.y()+" "+e.z()+" "+t.radius());}AtomicProperties.write(file,p);tasks=List.copyOf(list);paused=held;next=sequence;}
    public Task add(String kind,Endpoint p,int radius,boolean replace){if(!replace&&tasks.size()>=32)throw new IllegalStateException("Map queue is full (32)");Task t=new Task(next,kind,p,radius);List<Task> list=new ArrayList<>(replace?List.of():tasks);list.add(t);save(list,replace?false:paused,next+1);return t;}
    public void pause(boolean held){save(tasks,held,next);}public void clear(){save(List.of(),true,next);}
    public void remove(long id){List<Task> list=new ArrayList<>(tasks);if(!list.removeIf(t->t.id()==id))throw new IllegalArgumentException("Unknown task ID");save(list,paused,next);}
    public void move(long id,int index){if(index<1||index>tasks.size())throw new IllegalArgumentException("Position must be 1–"+tasks.size());List<Task> list=new ArrayList<>(tasks);Task task=list.stream().filter(t->t.id()==id).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown task ID"));list.remove(task);list.add(index-1,task);save(list,paused,next);}
    public void complete(long id){if(first()!=null&&first().id()==id)remove(id);}
}
