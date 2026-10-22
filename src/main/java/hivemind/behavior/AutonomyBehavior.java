/*
 * This file is part of HiveMind.
 *
 * HiveMind is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * HiveMind is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with HiveMind.  If not, see <https://www.gnu.org/licenses/>.
 */

package hivemind.behavior;

import hivemind.HiveMind;
import hivemind.api.utils.Helper;
import hivemind.utils.social.CombatStyle;
import hivemind.api.event.events.TickEvent;
import hivemind.api.event.events.type.EventState;
import hivemind.utils.bot.*;
import hivemind.utils.bot.TrackingOrderStore.Order;
import hivemind.utils.mining.MiningRouteAccess;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.world.entity.player.Player;
import java.nio.file.Path;
import java.util.*;

/** Main-thread supervisor: durable intent survives the loss of a live path or player entity. */
public final class AutonomyBehavior extends Behavior {
    private Path root;
    private TrackingOrderStore store;
    private long nextTry,nextReport,deadAt,nextRespawn,nextError,lastRoster;
    private int respawnRequests;
    private Object world;
    private Set<UUID> online=Set.of();
    private UUID seenPlayer;
    private Endpoint seenPoint;
    private long seenAt;
    private String reason="Ready";
    public AutonomyBehavior(HiveMind bot){super(bot);}
    private void load(){
        Path here=MiningRouteAccess.root(ctx);if(here.equals(root))return;
        store=new TrackingOrderStore(here);root=here;nextTry=0;nextReport=System.currentTimeMillis()+AutonomyPolicy.reportInterval(HiveMind.settings().botReportIntervalMS.value);
        online=Set.of();world=null;seenPlayer=null;seenPoint=null;seenAt=0;reason="Loaded saved orders";
    }
    public Order order(){load();return store.order();}
    public void arm(Order order){load();store.clear();store.set(order);nextTry=0;reason="Order saved for "+order.name();}
    public void clear(){if(ctx.world()!=null&&ctx.player()!=null)load();if(store!=null){store.clear();nextTry=0;reason="No tracking order";}}
    public void pause(boolean held){load();store.pause(held);}
    public boolean paused(){load();return store.paused();}
    public void defer(String message,long delay){reason=message;nextTry=System.currentTimeMillis()+Math.max(1000,delay);}
    public void complete(){clear();reason="Target death observed; order completed";}
    public String status(){
        load();Order o=store.order();return (store.paused()?"Paused · ":"")+(o==null?reason:(o.hunt()?"Hunt order: ":"Watch order: ")+o.name()+" · "+reason);
    }
    /** Called only for a new work command, never for a query or a setting change. */
    public void replaceWork(){load();clear();hivemind.getFlight().cancel();}
    private boolean workCommand(String label,String first){
        if(Set.of("status","list","history","sightings","report","supported").contains(first))return false;
        if(label.equals("job"))return Set.of("resume","start").contains(first);
        return Set.of("scout","mine","follow","goto","path","explore","farm","build","gettoblock","deposit","deliver","escort","recover","gather","repair","elytra").contains(label);
    }
    public void beforeCommand(String label,String first){if(workCommand(label,first)||Set.of("stalk","kill","stop","cancel").contains(label)&&!Set.of("status","history","sightings","report","base","forget").contains(first))hivemind.getFlight().cancel();}
    public void afterCommand(String label,String first){if(workCommand(label,first)){clear();if(hivemind.getStalkProcess().isActive())hivemind.getStalkProcess().onLostControl();}}
    @Override public void onPlayerDeath(){
        try{
            load();deadAt=System.currentTimeMillis();nextRespawn=deadAt+AutonomyPolicy.respawnDelay(HiveMind.settings().botRespawnDelayMS.value);respawnRequests=0;
            reason="Died; waiting to respawn";nextTry=deadAt+10000;
            hivemind.getNetwork().addMarker("death","Bot death","Death observed; this location may be dangerous",MiningRouteAccess.endpoint(ctx,ctx.playerFeet()),ctx.player().getUUID(),deadAt);
        }catch(RuntimeException e){error(e);}
    }
    @Override public void onTick(TickEvent event){
        if(event.getState()!=EventState.PRE)return;
        if(event.getType()==TickEvent.Type.OUT||ctx.player()==null||ctx.world()==null||ctx.minecraft().getConnection()==null){online=Set.of();world=null;seenPlayer=null;seenPoint=null;seenAt=0;return;}
        long now=System.currentTimeMillis();
        try{
            load();
            if(world!=ctx.world()){world=ctx.world();online=Set.of();seenPlayer=null;seenPoint=null;seenAt=0;lastRoster=0;nextTry=Math.max(nextTry,now+3000);}
            if(!ctx.player().isAlive()){
                if(deadAt==0){deadAt=now;nextRespawn=now+AutonomyPolicy.respawnDelay(HiveMind.settings().botRespawnDelayMS.value);}
                if(HiveMind.settings().botAutoRespawn.value&&!store.paused()&&now>=nextRespawn&&ctx.minecraft().gui.screen() instanceof DeathScreen){
                    ctx.player().respawn();respawnRequests=Math.min(10,respawnRequests+1);nextRespawn=now+Math.min(30000,10000+respawnRequests*2000L);reason="Respawn requested";
                }
                return;
            }
            if(deadAt!=0){if(ctx.minecraft().gui.screen() instanceof DeathScreen)ctx.minecraft().gui.setScreen(null);deadAt=0;respawnRequests=0;nextTry=Math.max(nextTry,now+3000);reason="Respawned; checking supplies before resuming";}
            if(now-lastRoster>=500){roster(now);lastRoster=now;}
            if(now>=nextReport){report(now);nextReport=now+AutonomyPolicy.reportInterval(HiveMind.settings().botReportIntervalMS.value);}
            Order order=store.order();
            if(order==null||store.paused()||!HiveMind.settings().botPersistentTracking.value||now<nextTry||hivemind.getStalkProcess().isActive()||BotWork.foreground(hivemind)||hivemind.getConflictProcess().isActive()||hivemind.getSculkProcess().isActive()||hivemind.getElytraProcess().isActive()||ctx.minecraft().gui.screen()!=null)return;
            if(order.owner()!=null&&!hivemind.getBotControl().isTrusted(order.owner())){clear();reason="Order removed: controller is no longer trusted";return;}
            Order requested=order;
            var info=order.target()==null?ctx.minecraft().getConnection().getOnlinePlayers().stream().filter(p->p.getProfile().name().equalsIgnoreCase(requested.name())).findFirst().orElse(null):ctx.minecraft().getConnection().getPlayerInfo(order.target());
            if(info!=null&&order.target()==null){store.set(order.resolved(info.getProfile().id(),info.getProfile().name()));order=store.order();}
            if(order.target()==null){reason="Waiting for "+order.name()+" to join; identity/location not observed yet";nextTry=now+2000;return;}
            if(order.hunt()&&hivemind.getStalkProcess().prepareSupplies(CombatStyle.parse(order.style()))){reason="Preparing combat hotbar";nextTry=now+500;return;}
            if(hivemind.getSurvivalProcess().beforeMining()){reason="Preparing supplies: "+hivemind.getSurvivalProcess().status();nextTry=now+5000;return;}
            try{hivemind.getStalkProcess().resume(order);reason="Tracking active";nextTry=now+5000;}
            catch(IllegalStateException e){defer(e.getMessage(),10000);}
        }catch(RuntimeException e){error(e);}
    }
    private void roster(long now){
        Set<UUID> current=new HashSet<>();for(var info:ctx.minecraft().getConnection().getOnlinePlayers())current.add(info.getProfile().id());
        UUID target=hivemind.getStalkProcess().target();if(target==null&&store.order()!=null)target=store.order().target();
        if(target!=null){
            if(!target.equals(seenPlayer)){seenPlayer=target;seenPoint=null;seenAt=0;}
            UUID id=target;Player visible=ctx.world().players().stream().filter(p->p.getUUID().equals(id)&&p.isAlive()&&!p.isSpectator()&&!p.isInvisible()&&ctx.player().hasLineOfSight(p)).findFirst().orElse(null);
            if(visible!=null){seenPoint=MiningRouteAccess.endpoint(ctx,visible.blockPosition());seenAt=now;}
            if(seenPoint!=null&&AutonomyPolicy.logoutEvidence(online.contains(target),current.contains(target),true,seenAt,now)){
                String name=store.order()!=null?store.order().name():"Player";
                hivemind.getNetwork().addMarker("logout",name+" logout","Left player list; last visible position (not a confirmed exact logout block)",seenPoint,target,seenAt);
                reason=name+" logged out; watching last known area";
                UUID owner=store.order()==null?null:store.order().owner();if(owner!=null)hivemind.getBotControl().reply(owner,name+" logged out; last seen "+seenPoint.x()+" "+seenPoint.y()+" "+seenPoint.z()+" ("+seenPoint.dimension()+")");
            }
        }
        online=Set.copyOf(current);
    }
    private void report(long now){
        if(!HiveMind.settings().botPeriodicReports.value)return;
        String task=hivemind.getPathingControlManager().mostRecentInControl().map(p->p.displayName()).orElse("Idle");
        if(store.order()!=null&&!hivemind.getStalkProcess().isActive())task=status();
        if(task.length()>100)task=task.substring(0,97)+"...";
        String d=MiningRouteAccess.dimension(ctx).replace("minecraft:","");String message=task+" | "+d+" "+ctx.playerFeet().getX()+" "+ctx.playerFeet().getY()+" "+ctx.playerFeet().getZ()+" | HP "+Math.round(ctx.player().getHealth())+", food "+ctx.player().getFoodData().getFoodLevel();
        Set<UUID> recipients=HiveMind.settings().botReportAllTrusted.value?hivemind.getBotControl().config().players():store.order()!=null&&store.order().owner()!=null?Set.of(store.order().owner()):Set.of();
        for(UUID recipient:recipients)if(ctx.minecraft().getConnection().getPlayerInfo(recipient)!=null)hivemind.getBotControl().report(recipient,message,now);
    }
    private void error(RuntimeException e){if(System.currentTimeMillis()>=nextError){Helper.HELPER.logDirect("Autonomy: "+e.getMessage());nextError=System.currentTimeMillis()+30000;}nextTry=System.currentTimeMillis()+10000;}
}
