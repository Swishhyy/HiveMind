/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import baritone.Baritone;
import baritone.utils.mining.MiningRouteAccess;
import baritone.utils.network.*;
import baritone.utils.network.ControlPackets.Packet;
import baritone.utils.network.TeamPackets.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.tags.ItemTags;
import java.util.*;

/** Game-thread team adapter. Membership is independent of the human whisper-controller whitelist. */
public final class NetworkTeamwork {
    private final NetworkBehavior parent;
    private final Baritone bot;
    private Teamwork host=new Teamwork();
    private final Set<UUID> allies=new LinkedHashSet<>();
    private Plan plan;
    private BlockPos mineClaim;
    private long nextSend;
    NetworkTeamwork(NetworkBehavior parent,Baritone bot){this.parent=parent;this.bot=bot;}
    public boolean enabled(){return parent.transport()!=null;}
    public boolean ready(){return !enabled()||plan!=null&&plan.valid(System.currentTimeMillis())&&(parent.transport().isHost()||parent.transport().connected());}
    public void reset(boolean forget){host=new Teamwork(System.currentTimeMillis()+4000+NetworkProtocol.CLOCK_SKEW_MS);plan=null;mineClaim=null;nextSend=0;if(forget)allies.clear();}
    private void remember(UUID id){allies.remove(id);allies.add(id);while(allies.size()>256)allies.remove(allies.iterator().next());}
    public boolean ally(UUID id){
        if(id==null||!enabled())return false;
        if(id.equals(parent.localIdentity().id()))return true;
        parent.peers().forEach(peer->remember(peer.id()));
        return allies.contains(id);
    }
    void accept(Packet packet,long now){
        if(packet instanceof Intent intent&&parent.transport()!=null&&parent.transport().isHost())host.accept(intent,now);
        else if(packet instanceof Plan current&&current.valid(now)&&(plan==null||current.observedAt()>plan.observedAt())){plan=current;current.members().keySet().forEach(this::remember);}
    }
    void tick(long now){
        var wire=parent.transport();if(wire==null)return;
        wire.peers().forEach(peer->remember(peer.id()));
        if(now<nextSend)return;nextSend=now+500;
        var ctx=bot.getPlayerContext();var who=parent.localIdentity();
        var controlled=bot.getPathingControlManager().mostRecentInControl().orElse(null);
        var stalk=bot.getStalkProcess();Kind kind=Kind.IDLE;UUID target=null;BlockPos work=null;boolean explosive=false,engaged=false;
        if(controlled==bot.getMineProcess()&&bot.getMineProcess().isActive()){kind=Kind.MINE;work=mineClaim;}
        else if(stalk.isActive()){
            kind=stalk.hunting()?Kind.HUNT:Kind.STALK;target=stalk.target();work=stalk.teamPoint();
            explosive=controlled==stalk&&stalk.teamExplosives();engaged=controlled==stalk&&stalk.committedCombat();
        }else if(bot.getRoamProcess().isActive()){kind=Kind.ROAM;work=bot.getRoamProcess().destination();}
        if(kind!=Kind.MINE)mineClaim=null;
        Intent intent=new Intent(who.id(),who.name(),kind,target,MiningRouteAccess.endpoint(ctx,ctx.playerFeet()),work==null?null:MiningRouteAccess.endpoint(ctx,work),explosive,engaged,now);
        if(wire.isHost())host.accept(intent,now);else wire.publish(intent);
        if(wire.isHost()){
            plan=host.plan(who,wire.peers(),now);plan.members().keySet().forEach(this::remember);wire.publish(plan);
        }
    }
    public List<BlockPos> mineTargets(List<BlockPos> candidates){
        if(!enabled())return candidates;
        long now=System.currentTimeMillis();var ctx=bot.getPlayerContext();UUID self=ctx.player().getUUID();
        if(mineClaim==null||!candidates.contains(mineClaim)||blocked(mineClaim,self,now)){
            BlockPos next=candidates.stream().filter(point->!blocked(point,self,now)).min(Comparator.comparingDouble(ctx.playerFeet()::distSqr)).orElse(null);
            if(!Objects.equals(mineClaim,next)){mineClaim=next;nextSend=0;}
        }
        if(!ready()||mineClaim==null)return List.of();
        Lease lease=plan.leases().get(self);
        return lease!=null&&lease.until()>now&&lease.point().equals(MiningRouteAccess.endpoint(ctx,mineClaim))?List.of(mineClaim):List.of();
    }
    private boolean blocked(BlockPos point,UUID self,long now){
        if(plan==null)return false;var endpoint=MiningRouteAccess.endpoint(bot.getPlayerContext(),point);
        return plan.leases().entrySet().stream().anyMatch(entry->!entry.getKey().equals(self)&&entry.getValue().until()+NetworkProtocol.CLOCK_SKEW_MS>now&&Teamwork.near(endpoint,entry.getValue().point(),6));
    }
    public boolean roamOccupied(BlockPos point){
        if(plan==null||!ready())return false;var ctx=bot.getPlayerContext();
        return plan.intents().stream().anyMatch(p->p.kind()==Kind.ROAM&&!p.reporter().equals(ctx.player().getUUID())&&p.work()!=null&&Teamwork.near(p.work(),MiningRouteAccess.endpoint(ctx,point),64));
    }
    public double heading(Kind kind,UUID target){
        var ctx=bot.getPlayerContext();if(!ready()||plan==null)return Math.floorMod(ctx.player().getUUID().hashCode(),360)*Math.PI/180;
        List<Intent> group=Teamwork.group(plan,kind,target,MiningRouteAccess.dimension(ctx));
        for(int i=0;i<group.size();i++)if(group.get(i).reporter().equals(ctx.player().getUUID()))return i*2*Math.PI/Math.max(1,group.size());
        return Math.floorMod(ctx.player().getUUID().hashCode(),360)*Math.PI/180;
    }
    public int groupSize(Kind kind,UUID target){return !ready()||plan==null?1:Math.max(1,Teamwork.group(plan,kind,target,MiningRouteAccess.dimension(bot.getPlayerContext())).size());}
    public boolean yieldWatch(BlockPos point,UUID target){
        if(!ready()||plan==null)return false;var ctx=bot.getPlayerContext();UUID self=ctx.player().getUUID();
        return plan.intents().stream().anyMatch(p->p.reporter().toString().compareTo(self.toString())<0&&Objects.equals(p.target(),target)&&p.work()!=null&&Teamwork.near(p.work(),MiningRouteAccess.endpoint(ctx,point),6));
    }
    public double watchPenalty(BlockPos point,UUID target){
        if(!ready()||plan==null)return 0;
        var ctx=bot.getPlayerContext();double penalty=0;
        for(Intent intent:plan.intents())if(!intent.reporter().equals(ctx.player().getUUID())&&Objects.equals(intent.target(),target)&&intent.work()!=null
                &&Teamwork.near(MiningRouteAccess.endpoint(ctx,point),intent.work(),8))penalty+=1600;
        return penalty;
    }
    public boolean blastSupport(UUID target){
        if(!enabled()||!ready()||plan==null)return false;
        UUID lead=Teamwork.blastLeader(plan,target,MiningRouteAccess.dimension(bot.getPlayerContext()));
        return lead!=null&&!lead.equals(bot.getPlayerContext().player().getUUID());
    }
    public int supportDistance(UUID target){
        if(!enabled()||!ready()||plan==null)return 0;
        if(blastSupport(target))return 16;
        if(Teamwork.blastLeader(plan,target,MiningRouteAccess.dimension(bot.getPlayerContext()))!=null)return 0;
        var hunters=Teamwork.group(plan,Kind.HUNT,target,MiningRouteAccess.dimension(bot.getPlayerContext()));
        UUID lead=hunters.stream().filter(Intent::engaged).map(Intent::reporter).findFirst().orElse(null);
        return lead!=null&&!lead.equals(bot.getPlayerContext().player().getUUID())?5:0;
    }
    public boolean alliedExplosive(Entity entity){
        if(!(entity instanceof EndCrystal)||plan==null||!ready())return false;
        var point=MiningRouteAccess.endpoint(bot.getPlayerContext(),entity.blockPosition());
        return plan.intents().stream().anyMatch(intent->ally(intent.reporter())&&intent.kind()==Kind.HUNT&&intent.explosive()&&intent.work()!=null&&Teamwork.near(intent.work(),point,2));
    }
    public boolean clearStrike(Entity target){
        var ctx=bot.getPlayerContext();if(ally(target.getUUID()))return false;
        boolean sweep=ctx.player().getMainHandItem().is(ItemTags.SWORDS);
        for(Player other:ctx.world().players())if(other!=ctx.player()&&other!=target&&other.isAlive()&&!other.isSpectator()
                &&(ally(other.getUUID())||bot.getBotControl().isTrusted(other.getUUID()))&&Math.abs(other.getY()-target.getY())<3
                &&!Teamwork.clearStrike(ctx.player().getX(),ctx.player().getZ(),target.getX(),target.getZ(),other.getX(),other.getZ(),sweep))return false;
        return true;
    }
    public String status(){
        if(!enabled())return "Solo";if(!ready())return "Team: waiting for host";
        UUID target=bot.getStalkProcess().target();
        if(target!=null&&bot.getStalkProcess().hunting())return supportDistance(target)>0?"Team: "+(blastSupport(target)?"blast":"melee")+" support":"Team: attack";
        return "Team: "+plan.members().size()+" allies"+(mineClaim!=null?"; mining claim "+(plan.leases().containsKey(bot.getPlayerContext().player().getUUID())&&plan.leases().get(bot.getPlayerContext().player().getUUID()).until()>System.currentTimeMillis()?"held":"pending"):"");
    }
}
