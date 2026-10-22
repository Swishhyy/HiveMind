/* This file is part of HiveMind, distributed under the LGPL-3.0 license. */
package hivemind.behavior;

import hivemind.HiveMind;
import hivemind.api.event.events.TickEvent;
import hivemind.api.event.events.type.EventState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import java.util.*;

/** Transient ESP-style contacts from entities actually loaded by this client. Never a visual sighting. */
public final class EntityAwareness extends Behavior {
    private record Contact(LivingEntity entity,long sampledAt) {}
    private final Map<UUID,Contact> contacts=new LinkedHashMap<>();
    private Object world;
    private long next;
    public EntityAwareness(HiveMind bot){super(bot);}
    private void refresh(){
        long now=System.currentTimeMillis();
        if(world!=ctx.world()||ctx.player()==null||!ctx.player().isAlive()||!HiveMind.settings().botEntityAwareness.value){
            contacts.clear();next=0;world=ctx.world();
        }
        if(ctx.player()==null||ctx.world()==null||!ctx.player().isAlive()||!HiveMind.settings().botEntityAwareness.value||now<next)return;
        next=now+100;
        int radius=Math.max(64,Math.min(512,HiveMind.settings().botEntityAwarenessRange.value));
        contacts.clear();
        ctx.entitiesStream().filter(entity->entity!=ctx.player()&&entity instanceof LivingEntity&&entity.isAlive()&&!entity.isSpectator()
                        &&entity.distanceToSqr(ctx.player())<=radius*radius)
                .sorted(Comparator.comparingDouble(entity->entity.distanceToSqr(ctx.player()))).limit(256)
                .forEach(entity->contacts.put(entity.getUUID(),new Contact((LivingEntity)entity,now)));
    }
    private boolean current(Contact contact){
        return contact!=null&&world==ctx.world()&&ctx.player()!=null&&System.currentTimeMillis()-contact.sampledAt()<=1000
                &&contact.entity().isAlive()&&!contact.entity().isSpectator()&&ctx.world().getEntity(contact.entity().getId())==contact.entity();
    }
    /** Turning awareness off restores visual-only acquisition. Attacks always require their own reach/LOS checks. */
    public Player player(UUID id){
        if(id==null||ctx.world()==null||ctx.player()==null)return null;
        refresh();
        if(!HiveMind.settings().botEntityAwareness.value)return ctx.world().players().stream()
                .filter(p->p.getUUID().equals(id)&&p.isAlive()&&!p.isSpectator()&&ctx.player().hasLineOfSight(p)).findFirst().orElse(null);
        Contact contact=contacts.get(id);
        return current(contact)&&contact.entity() instanceof Player p?p:null;
    }
    public List<Mob> mobs(){
        refresh();return contacts.values().stream().filter(this::current).map(Contact::entity).filter(entity->entity instanceof Mob).map(entity->(Mob)entity).toList();
    }
    @Override public void onTick(TickEvent event){
        if(event.getState()!=EventState.PRE)return;
        if(event.getType()==TickEvent.Type.OUT){contacts.clear();world=null;next=0;return;}
        refresh();
    }
}
