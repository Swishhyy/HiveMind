/* HiveMind, LGPL-3.0. */
package hivemind.utils.reliability;

import java.util.*;

/** A persistent goal changes strategy after failure and retries only when its conditions permit progress. */
public final class FailureRecovery {
    public enum Kind { PATH, UNLOADED, SERVER_REJECTED, FULL_INVENTORY, RESOURCE, OWNERSHIP, DANGER, CONNECTION }
    public enum Strategy { RETRY, REPATH, APPROACH, ALTERNATE, GROUND_ROUTE, WAIT }
    public record Decision(Kind kind,Strategy strategy,int failures,long retryAt,String reason) {}
    private record Failure(int count,long retryAt,int progress) {}
    private final Map<String,Failure> failures=new LinkedHashMap<>();
    public Decision failed(String key,Kind kind,int progress,String reason,long now){
        Failure old=failures.get(key);int count=old==null||progress>old.progress()?1:old.count()+1;
        long delay=kind==Kind.OWNERSHIP?250:kind==Kind.UNLOADED?2000:Math.min(120000,1000L<<Math.min(7,count-1));
        failures.put(key,new Failure(count,now+delay,progress));while(failures.size()>256)failures.remove(failures.keySet().iterator().next());
        Strategy strategy=switch(kind){
            case PATH -> count==1?Strategy.REPATH:count==2?Strategy.APPROACH:count==3?Strategy.GROUND_ROUTE:Strategy.ALTERNATE;
            case SERVER_REJECTED -> count<3?Strategy.APPROACH:Strategy.ALTERNATE;
            case RESOURCE -> Strategy.ALTERNATE;
            case FULL_INVENTORY,DANGER,CONNECTION,UNLOADED,OWNERSHIP -> Strategy.WAIT;
        };
        return new Decision(kind,strategy,count,now+delay,reason);
    }
    public boolean ready(String key,int progress,long now){Failure f=failures.get(key);return f==null||progress>f.progress()||now>=f.retryAt();}
    public void success(String key){failures.remove(key);}
    public static Kind classify(String reason){String s=reason.toLowerCase(Locale.ROOT);if(s.contains("chunk")||s.contains("unloaded"))return Kind.UNLOADED;if(s.contains("full")||s.contains("pickup slot"))return Kind.FULL_INVENTORY;if(s.contains("owned by")||s.contains("busy"))return Kind.OWNERSHIP;if(s.contains("offline")||s.contains("disconnect"))return Kind.CONNECTION;if(s.contains("danger")||s.contains("hostile")||s.contains("combat"))return Kind.DANGER;if(s.contains("stock")||s.contains("supplies")||s.contains("rocket"))return Kind.RESOURCE;if(s.contains("reject")||s.contains("confirm")||s.contains("permission"))return Kind.SERVER_REJECTED;return Kind.PATH;}
}
