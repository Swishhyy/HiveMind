/* HiveMind, LGPL-3.0. */
package hivemind.utils.reliability;

import hivemind.utils.bot.AtomicProperties;
import java.nio.file.Path;
import java.util.*;

/** Durable intent and interruptions; saved observations never authorize replay of inventory clicks. */
public final class TaskCheckpoint {
    public record Interruption(String owner,String reason,String resumeWhen,long since) {}
    private final Path file;
    private long generation;
    private boolean held;
    private String intent="",result="";
    private List<Interruption> stack=List.of();
    public TaskCheckpoint(Path root,UUID account){
        file=root.resolve("task-runtime-"+account+".properties");Properties p=AtomicProperties.read(file);
        generation=Long.parseLong(p.getProperty("generation","0"));held=Boolean.parseBoolean(p.getProperty("held","false"));
        intent=p.getProperty("intent","");result=p.getProperty("result","");
        if(generation<0||intent.length()>512)throw new IllegalStateException("Invalid task checkpoint");
        int count=Integer.parseInt(p.getProperty("count","0"));if(count<0||count>12)throw new IllegalStateException("Invalid task interruptions");
        List<Interruption> saved=new ArrayList<>();for(int i=0;i<count;i++){String k="interrupt."+i+".";saved.add(new Interruption(p.getProperty(k+"owner",""),p.getProperty(k+"reason",""),p.getProperty(k+"resume",""),Long.parseLong(p.getProperty(k+"since","0"))));}stack=List.copyOf(saved);
    }
    public long generation(){return generation;}public boolean held(){return held;}public String intent(){return intent;}public String result(){return result;}public List<Interruption> stack(){return stack;}
    public void start(String command){intent=limit(command,512);result="";generation=Math.addExact(generation,1);stack=List.of();held=false;save();}
    public void hold(boolean value){if(held!=value){held=value;generation=Math.addExact(generation,1);save();}}
    public void cancel(){intent="";result="Cancelled by controller";stack=List.of();held=true;generation=Math.addExact(generation,1);save();}
    public void observed(List<Interruption> next){
        if(next.size()>12)throw new IllegalArgumentException("Too many interruptions");
        // Preserve start times while a blocker remains; do not rewrite a checkpoint every tick.
        List<Interruption> stable=new ArrayList<>();for(var n:next){var old=stack.stream().filter(i->i.owner().equals(n.owner())&&i.reason().equals(n.reason())&&i.resumeWhen().equals(n.resumeWhen())).findFirst().orElse(n);stable.add(old);}
        if(!stack.equals(stable)){stack=List.copyOf(stable);save();}
    }
    public void completed(String proof){result=limit(proof,512);intent="";stack=List.of();save();}
    private static String limit(String text,int length){Objects.requireNonNull(text);return text.substring(0,Math.min(length,text.length()));}
    private void save(){Properties p=new Properties();p.setProperty("schema","1");p.setProperty("generation",""+generation);p.setProperty("held",""+held);p.setProperty("intent",intent);p.setProperty("result",result);p.setProperty("count",""+stack.size());for(int i=0;i<stack.size();i++){String k="interrupt."+i+".";var n=stack.get(i);p.setProperty(k+"owner",n.owner());p.setProperty(k+"reason",n.reason());p.setProperty(k+"resume",n.resumeWhen());p.setProperty(k+"since",""+n.since());}AtomicProperties.write(file,p);}
}
