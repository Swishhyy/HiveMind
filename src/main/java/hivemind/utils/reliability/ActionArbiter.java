/* HiveMind, LGPL-3.0. */
package hivemind.utils.reliability;

import java.util.*;
import java.util.function.LongSupplier;

/** Short, renewable action leases. Emergency owners can preempt; equal priorities cannot steal a lease. */
public final class ActionArbiter {
    public enum Channel { MOVEMENT, LOOK, HAND, INVENTORY, USE }
    public record Owner(String name, int priority) {
        public Owner { if(name==null||name.isBlank()||name.length()>80)throw new IllegalArgumentException("Invalid action owner"); }
    }
    private record Lease(Owner owner,long until) {}
    public final class Scope implements AutoCloseable {
        private final Owner before;
        private Scope(Owner owner){before=current.get();current.set(owner);}
        @Override public void close(){if(before==null)current.remove();else current.set(before);}
    }
    private final ThreadLocal<Owner> current=new ThreadLocal<>();
    private final EnumMap<Channel,Lease> leases=new EnumMap<>(Channel.class);
    private final LongSupplier clock;
    private String blocked="";
    private long blockedAt;
    private boolean manual;
    public ActionArbiter(){this(System::currentTimeMillis);}
    public ActionArbiter(LongSupplier clock){this.clock=Objects.requireNonNull(clock);}
    public Scope scope(String name,int priority){return new Scope(new Owner(name,priority));}
    public Owner current(){return current.get();}
    public void manual(boolean value){manual=value;if(value)leases.clear();}
    public boolean allow(Channel... channels){
        Owner owner=current.get();
        // No scope means an actual user command or a lifecycle cleanup, never a bot tick.
        if(owner==null)return true;
        if(manual)return false;
        long now=clock.getAsLong();
        for(Channel channel:channels){Lease old=leases.get(channel);if(old!=null&&old.until()>now&&!old.owner().name().equals(owner.name())&&old.owner().priority()>=owner.priority()){
            blocked=owner.name()+" waiting for "+channel.name().toLowerCase(Locale.ROOT)+" owned by "+old.owner().name();blockedAt=now;return false;
        }}
        for(Channel channel:channels)leases.put(channel,new Lease(owner,now+150));
        return true;
    }
    public boolean owns(Channel channel,Owner owner){
        if(owner==null)return !manual;
        Lease lease=leases.get(channel);return !manual&&lease!=null&&lease.until()>clock.getAsLong()&&lease.owner().name().equals(owner.name());
    }
    public void release(){Owner owner=current.get();if(owner==null)leases.clear();else leases.values().removeIf(l->l.owner().name().equals(owner.name()));}
    public boolean canClear(Owner owner){Owner caller=current.get();return caller==null||owner==null||caller.name().equals(owner.name())||caller.priority()>owner.priority();}
    public Map<String,String> snapshot(){
        long now=clock.getAsLong();Map<String,String> result=new LinkedHashMap<>();
        leases.forEach((channel,lease)->{if(lease.until()>now)result.put(channel.name().toLowerCase(Locale.ROOT),lease.owner().name());});return Map.copyOf(result);
    }
    public String blocker(){return clock.getAsLong()-blockedAt<5000?blocked:"";}
    public void reset(){leases.clear();blocked="";current.remove();}

    /** Shared helpers inherit their caller; only top-level listeners/processes establish an owner. */
    public static Owner owner(String simpleName){
        if(simpleName.contains("BucketClutch"))return new Owner("fall rescue",100);
        if(simpleName.contains("Conflict")||simpleName.contains("Combat")||simpleName.contains("Melee")||simpleName.contains("Crystal"))return new Owner("combat",90);
        if(simpleName.contains("Survival"))return new Owner("survival",85);
        if(simpleName.contains("Shulker"))return new Owner("portable supplies",75);
        if(simpleName.contains("Deposit"))return new Owner("storage",70);
        if(simpleName.contains("Workstation"))return new Owner("production",65);
        if(simpleName.contains("Repair"))return new Owner("maintenance",65);
        if(simpleName.contains("Elytra")||simpleName.contains("Flight"))return new Owner("flight",60);
        if(simpleName.contains("Stalk"))return new Owner("tracking",55);
        if(simpleName.contains("Operations"))return new Owner("recovery",50);
        if(simpleName.contains("Inventory"))return new Owner("equipment",10);
        return new Owner("navigation",30);
    }
}
