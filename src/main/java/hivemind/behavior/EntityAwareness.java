/* This file is part of HiveMind, distributed under the LGPL-3.0 license. */
package hivemind.behavior;

import hivemind.HiveMind;
import hivemind.api.HiveMindAPI;
import hivemind.api.event.events.RenderEvent;
import hivemind.api.event.events.TickEvent;
import hivemind.api.event.events.type.EventState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.monster.Enemy;
import hivemind.utils.IRenderer;
import hivemind.utils.bot.AwarenessPolicy;
import java.awt.Color;
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
        next=now+40;
        int radius=AwarenessPolicy.range(HiveMind.settings().botEntityAwarenessRange.value);
        UUID watched=hivemind.getStalkProcess().target();
        contacts.clear();
        ctx.entitiesStream().filter(entity->entity!=ctx.player()&&entity instanceof LivingEntity&&entity.isAlive()&&!entity.isSpectator()
                        &&AwarenessPolicy.within(entity.distanceToSqr(ctx.player()),radius))
                .sorted(Comparator.comparingDouble(entity->AwarenessPolicy.priority(entity instanceof Player,entity.getUUID().equals(watched),entity.distanceToSqr(ctx.player())))).limit(1024)
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
    public List<Player> players(){refresh();return contacts.values().stream().filter(this::current).map(Contact::entity).filter(entity->entity instanceof Player).map(entity->(Player)entity).toList();}
    @Override public void onRenderPass(RenderEvent event){
        if(!HiveMind.settings().botRenderEntityESP.value||ctx.player()==null||ctx.world()==null
                ||HiveMindAPI.getProvider().getPrimaryHiveMind().getPlayerContext().world()!=ctx.world())return;
        refresh();UUID watched=hivemind.getStalkProcess().target();
        List<LivingEntity> entities=contacts.values().stream().filter(this::current).map(Contact::entity)
                .filter(entity->entity instanceof Player||HiveMind.settings().botRenderMobESP.value&&entity instanceof Enemy).toList();
        for(int category=0;category<3;category++){
            final int group=category;
            List<LivingEntity> selected=entities.stream().filter(entity->{
                int kind=entity.getUUID().equals(watched)||entity instanceof Enemy?0:
                        hivemind.getNetwork().isTeammate(entity.getUUID())||hivemind.getBotControl().isTrusted(entity.getUUID())?1:2;
                return kind==group;
            }).toList();
            if(selected.isEmpty())continue;
            Color color=category==0?new Color(255,90,100):category==1?new Color(110,235,175):new Color(255,195,95);
            var buffer=IRenderer.startLines(color,.9f);
            for(LivingEntity entity:selected){
                var box=entity.getBoundingBox().move(entity.getPosition(event.getPartialTicks()).subtract(entity.position())).inflate(.06);
                IRenderer.emitAABB(buffer,event.getModelViewStack(),box,.002,Math.max(2,HiveMind.settings().pathRenderLineWidthPixels.value));
            }
            IRenderer.endLines(buffer,true);
        }
    }
    @Override public void onTick(TickEvent event){
        if(event.getState()!=EventState.PRE)return;
        if(event.getType()==TickEvent.Type.OUT){contacts.clear();world=null;next=0;return;}
        refresh();
    }
}
