/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;

import java.nio.file.Path;
import java.util.*;

/** A bounded, account-scoped explanation trail for bot work and recovery. No chat, keys or player contents. */
public final class ActivityJournal {
    public record Event(long time,String kind,String message) {}
    private final Path file;
    private final Deque<Event> events=new ArrayDeque<>();
    private final Map<String,String> last=new HashMap<>();
    public ActivityJournal(Path worldRoot,UUID account){
        file=worldRoot.resolve("bot-activity-"+account+".properties");Properties p=AtomicProperties.read(file);
        int count;try{count=Math.min(20,Integer.parseInt(p.getProperty("count","0")));}catch(NumberFormatException ignored){count=0;}
        for(int i=0;i<count;i++)try{
            long time=Long.parseLong(p.getProperty("event."+i+".time"));String kind=p.getProperty("event."+i+".kind"),message=p.getProperty("event."+i+".message");
            if(time<0||time>System.currentTimeMillis()+60000||kind==null||message==null||kind.length()>24||message.length()>220)continue;
            events.addLast(new Event(time,kind,message));last.put(kind,message);
        }catch(RuntimeException ignored){}
    }
    public synchronized void note(String kind,String message){
        if(kind==null||message==null||kind.isBlank()||message.isBlank())return;
        kind=kind.replaceAll("[\\p{Cntrl}|]"," ");message=message.replaceAll("[\\p{Cntrl}|]"," ");
        kind=kind.substring(0,Math.min(kind.length(),24));message=message.substring(0,Math.min(message.length(),220));
        if(message.equals(last.get(kind)))return;
        last.put(kind,message);events.addLast(new Event(System.currentTimeMillis(),kind,message));while(events.size()>20)events.removeFirst();save();
    }
    public synchronized List<Event> recent(int limit){List<Event> newest=new ArrayList<>(events);Collections.reverse(newest);return newest.stream().limit(Math.max(0,Math.min(20,limit))).toList();}
    private void save(){Properties p=new Properties();p.setProperty("count",""+events.size());int i=0;for(Event event:events){String prefix="event."+(i++)+".";p.setProperty(prefix+"time",""+event.time());p.setProperty(prefix+"kind",event.kind());p.setProperty(prefix+"message",event.message());}AtomicProperties.write(file,p);}
}
