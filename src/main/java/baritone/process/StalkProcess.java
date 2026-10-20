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

package baritone.process;

import baritone.Baritone;
import baritone.api.behavior.IBehavior;
import baritone.api.event.events.TickEvent;
import baritone.api.pathing.goals.*;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.mining.MiningRouteAccess;
import baritone.utils.social.*;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** UUID-specific tracking using ordinary loaded-world movement, real sight lines, and real melee. */
public final class StalkProcess extends BaritoneProcessHelper implements IBehavior {
    private enum Phase { SEARCH, WATCH, SCATTER, APPROACH, ATTACK }
    private record Seen(BlockPos position,long time) {}
    private final Map<UUID,Seen> seen=new HashMap<>();
    private final Set<BlockPos> failedVantages=new HashSet<>();
    private Level observedWorld;
    private Path memoryRoot;
    private PlayerMemory memory;
    private UUID targetId,owner;
    private String targetName,lastResult="No stalk target";
    private Phase phase=Phase.SEARCH;
    private boolean hunt,checkingMemoryOnly,watching,endAfterScatter,reachedRear;
    private BlockPos searchAnchor,vantageAnchor,vantage,lastReported,scatterAnchor;
    private Goal searchGoal;
    private Player watchedPlayer;
    private List<PlayerMemory.Place> rememberedPlaces=List.of();
    private int searchStep,memoryStep,legTicks,siteWait,vantageRetry,noVantageTicks,gazeTicks,quietTicks,scatterTicks,scatterQuiet,failedPaths,attempts,previousSlot=-1;
    private boolean checkedLastSeen;
    private long searchStarted,taskStarted,ambushAt,approachStarted,attackStarted,lastRecorded,lastReport,nextMemoryError;
    private float startingTargetHealth;
    private int attackCount;
    private String scatterReason="";

    public StalkProcess(Baritone bot) { super(bot); }
    @Override public boolean isActive() { return targetId!=null; }
    public boolean hunting() { return isActive()&&hunt; }
    public boolean terrainLocked() { return isActive()&&Baritone.settings().stalkMinimalEvidence.value; }
    public boolean quietMovement() { return isActive()&&phase!=Phase.SCATTER&&phase!=Phase.ATTACK&&!baritone.getConflictProcess().isActive(); }
    public boolean scattering() { return isActive()&&phase==Phase.SCATTER; }
    public String status() { return isActive()?displayName0():lastResult; }
    private int distance() { return SocialPolicy.stalkDistance(Baritone.settings().stalkDistance.value); }
    private int retention() { return StealthPolicy.bounded(Baritone.settings().stalkMemoryDays.value,1,90); }

    public PlayerMemory memory() {
        Path current=MiningRouteAccess.root(ctx);
        if(!current.equals(memoryRoot)) { PlayerMemory loaded=new PlayerMemory(current);memory=loaded;memoryRoot=current; }
        return memory;
    }
    public UUID identity(String name) {
        if(ctx.minecraft().getConnection()!=null)for(PlayerInfo info:ctx.minecraft().getConnection().getOnlinePlayers())
            if(info.getProfile().name().equalsIgnoreCase(name))return info.getProfile().id();
        UUID known=memory().resolve(name,System.currentTimeMillis(),retention());
        if(known==null)throw new IllegalStateException("No online profile or saved sighting for "+name);
        return known;
    }
    public List<PlayerMemory.Place> history(String name) {
        UUID id=name==null?null:identity(name);
        return memory().all(System.currentTimeMillis(),retention()).stream().filter(place->id==null||place.player().equals(id)).limit(12).toList();
    }
    public void confirmBase(String name,BlockPos supplied) {
        UUID id=identity(name);Player visible=loaded(id);
        var places=memory().places(id,MiningRouteAccess.dimension(ctx),System.currentTimeMillis(),retention());
        BlockPos point=supplied!=null?supplied:visible!=null?visible.blockPosition():places.isEmpty()?null:MiningRouteAccess.position(places.get(0).point());
        if(point==null)throw new IllegalStateException("Supply coordinates or observe the player in this dimension first");
        String canonical=visible!=null?visible.getGameProfile().name():places.isEmpty()?name:places.get(0).name();
        memory().confirm(id,canonical,MiningRouteAccess.endpoint(ctx,point),System.currentTimeMillis(),retention());
    }
    public void start(String requested) { start(requested,false,false); }
    public void startHunt(String requested) { start(requested,true,false); }
    public void checkPlaces(String requested) { start(requested,false,true); }

    private void start(String requested,boolean kill,boolean checkOnly) {
        if(ctx.minecraft().getConnection()==null||ctx.world()==null||ctx.player()==null||!ctx.player().isAlive())
            throw new IllegalStateException("Join a world with a living bot before tracking");
        if(ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty())
            throw new IllegalStateException("Close containers and clear the cursor before tracking");
        rememberPlayers();memory();
        List<PlayerInfo> online=ctx.minecraft().getConnection().getOnlinePlayers().stream()
                .filter(info->!info.getProfile().id().equals(ctx.player().getUUID())).toList();
        UUID selected;String name;
        boolean random=requested==null||requested.equalsIgnoreCase("random");
        if(kill&&random)throw new IllegalStateException("Name one player explicitly: kill <player>");
        if(checkOnly) {
            selected=identity(requested);
            var known=memory().places(selected,MiningRouteAccess.dimension(ctx),System.currentTimeMillis(),retention());
            if(known.isEmpty())throw new IllegalStateException("No saved places for that player in this dimension");
            name=known.get(0).name();
        } else {
            PlayerInfo profile;
            if(random) {
                List<PlayerInfo> nearby=online.stream().filter(info->loaded(info.getProfile().id())!=null).toList();
                List<PlayerInfo> pool=nearby.isEmpty()?online:nearby;
                if(pool.isEmpty())throw new IllegalStateException("No other players are online");
                profile=pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            } else profile=online.stream().filter(info->info.getProfile().name().equalsIgnoreCase(requested)).findFirst()
                    .orElseThrow(()->new IllegalStateException("That player is not online; use stalk check <name> for saved places"));
            selected=profile.getProfile().id();name=profile.getProfile().name();
        }
        if(selected.equals(ctx.player().getUUID()))throw new IllegalStateException("The bot cannot target itself");
        if(Baritone.settings().stalkMinimalEvidence.value)for(EquipmentSlot slot:EquipmentSlot.values())
            if(ctx.player().getItemBySlot(slot).getEnchantments().keySet().stream().anyMatch(holder->holder.is(Enchantments.FROST_WALKER)))
                throw new IllegalStateException("Remove Frost Walker equipment for minimal-evidence tracking");
        if(kill) {
            if(!Baritone.settings().killAllowTrustedTargets.value&&baritone.getBotControl().isTrusted(selected))
                throw new IllegalStateException("Trusted controllers are protected from kill; change killAllowTrustedTargets locally to opt in");
            if(weapon()<0)throw new IllegalStateException("Carry a usable sword or axe on the hotbar");
            if(!canFight())throw new IllegalStateException("Recover health and hunger before an ambush");
        }
        UUID controller=baritone.getJobManager().requestOwner();baritone.getPathingBehavior().cancelEverything();
        owner=controller;targetId=selected;targetName=name;hunt=kill;checkingMemoryOnly=checkOnly;observedWorld=ctx.world();
        taskStarted=searchStarted=System.currentTimeMillis();searchAnchor=ctx.playerFeet().immutable();
        rememberedPlaces=memory().places(targetId,MiningRouteAccess.dimension(ctx),taskStarted,retention());
        tell((hunt?"Hunting ":"Stalking ")+targetName+"; observing from about "+distance()+" blocks");
        if(loaded(targetId)==null)tell("Checking saved sightings/bases and a bounded local search; target coordinates are not globally available");
    }

    private Player loaded(UUID id) {
        return ctx.world().players().stream().filter(player->player.getUUID().equals(id)&&player.isAlive()&&!player.isSpectator()).findFirst().orElse(null);
    }
    private void rememberPlayers() {
        if(ctx.world()!=observedWorld) { seen.clear();observedWorld=ctx.world(); }
        long now=System.currentTimeMillis();seen.entrySet().removeIf(entry->now-entry.getValue().time()>120000);
        for(Player player:ctx.world().players())if(player!=ctx.player()&&player.isAlive()&&!player.isSpectator())
            seen.put(player.getUUID(),new Seen(player.blockPosition().immutable(),now));
    }
    private double facing(Player target,Vec3 point) {
        Vec3 look=target.getLookAngle(),delta=point.subtract(target.getEyePosition());
        return StealthPolicy.gazeDot(look.x,look.y,look.z,delta.x,delta.y,delta.z);
    }
    private boolean beingWatched(Player target) {
        return StealthPolicy.looking(facing(target,ctx.playerHead()),target.hasLineOfSight(ctx.player()),Baritone.settings().stalkViewConeDegrees.value);
    }
    private int weapon() {
        int axe=-1;
        for(int slot=0;slot<9;slot++) {
            ItemStack stack=ctx.player().getInventory().getItem(slot);
            if(stack.isDamageableItem()&&stack.getMaxDamage()-stack.getDamageValue()<=Math.max(1,Baritone.settings().botToolReserve.value))continue;
            if(stack.is(ItemTags.SWORDS))return slot;if(stack.is(ItemTags.AXES)&&axe<0)axe=slot;
        }return axe;
    }
    private boolean alone(Player target) {
        return ctx.world().players().stream().noneMatch(other->other!=ctx.player()&&!other.getUUID().equals(target.getUUID())&&!other.isSpectator()&&other.isAlive()&&other.distanceToSqr(target)<144);
    }
    private boolean canFight() {
        return StealthPolicy.mayContinueCombat(ctx.player().getHealth(),ctx.player().getFoodData().getFoodLevel(),
                Baritone.settings().killMinimumHealth.value,weapon()>=0,true);
    }
    public boolean handlesCombatAgainst(Entity attacker) {
        Player target=isActive()&&hunt&&phase==Phase.ATTACK?loaded(targetId):null;
        return target!=null&&target.getUUID().equals(attacker.getUUID())&&canFight()&&alone(target)&&!baritone.getSculkProcess().isActive();
    }

    @Override public PathingCommand onTick(boolean failed,boolean safe) {
        watching=false;watchedPlayer=null;
        try {
            if(ctx.player()==null||ctx.world()==null||!ctx.player().isAlive()||ctx.world()!=observedWorld||ctx.minecraft().getConnection()==null)
                return stop("Bot or world changed");
            if(hunt&&System.currentTimeMillis()-taskStarted>StealthPolicy.bounded(Baritone.settings().killTimeoutMS.value,60000L,3600000L))
                return stop("Hunt timed out; no kill is assumed");
            if(hunt&&phase==Phase.ATTACK) {
                Player entity=ctx.world().players().stream().filter(player->player.getUUID().equals(targetId)).findFirst().orElse(null);
                if(entity!=null&&(!entity.isAlive()||entity.getHealth()<=0))return scatter(entity,"Target death observed; withdrawing",true);
            }
            Player target=loaded(targetId);
            if(phase==Phase.SCATTER)return scatterTick(target,failed,safe);
            if(!checkingMemoryOnly&&ctx.minecraft().getConnection().getPlayerInfo(targetId)==null)return stop("Target left the server; no kill is assumed");
            if(target==null) {
                if(phase==Phase.ATTACK)return stop("Target left client view during combat; a kill was not confirmed");
                phase=Phase.SEARCH;quietTicks=0;ambushAt=0;return search(failed,safe);
            }
            if(hunt&&(target.isCreative()||target.isSpectator()))return stop("Target is not a survival-mode opponent");
            if(hunt&&!Baritone.settings().killAllowTrustedTargets.value&&baritone.getBotControl().isTrusted(targetId))
                return stop("Target became a trusted controller");
            if(ctx.player().hasLineOfSight(target))record(target);
            searchAnchor=target.blockPosition().immutable();searchStarted=System.currentTimeMillis();searchGoal=null;
            legTicks=siteWait=searchStep=0;checkedLastSeen=false;memoryStep=0;
            boolean viewed=beingWatched(target);
            gazeTicks=StealthPolicy.gazeTicks(gazeTicks,viewed,Baritone.settings().stalkGazeTicks.value);
            if(phase!=Phase.ATTACK&&gazeTicks>=StealthPolicy.bounded(Baritone.settings().stalkGazeTicks.value,10,200))
                return scatter(target,"Player watched the bot too long; scattering",false);
            if(phase==Phase.ATTACK)return attack(target,failed,safe);
            if(phase==Phase.APPROACH)return approach(target,failed,safe);
            phase=Phase.WATCH;return observe(target,failed,safe,viewed);
        } catch(RuntimeException error) { return stop("Tracking error: "+error.getMessage()); }
    }

    private PathingCommand observe(Player target,boolean failed,boolean safe,boolean viewed) {
        if(vantageAnchor==null||vantageAnchor.distSqr(target.blockPosition())>16) {
            vantageAnchor=target.blockPosition().immutable();vantage=null;failedVantages.clear();vantageRetry=0;
        }
        if(failed&&vantage!=null) { failedVantages.add(vantage);vantage=null;vantageRetry=0;if(++failedPaths>=6)return stop("No reachable open viewing point"); }
        boolean inBand=new GoalStandoff(target.blockPosition(),distance()).isInGoal(ctx.playerFeet())
                &&StealthPolicy.openHeight(ctx.player().getY()-target.getY(),Baritone.settings().stalkVerticalRange.value);
        boolean sight=ctx.player().hasLineOfSight(target);
        boolean open=inBand&&openPoint(ctx.playerFeet());
        if(inBand&&sight&&open&&facing(target,ctx.playerHead())<0.45) {
            noVantageTicks=0;vantage=null;watching=safe;watchedPlayer=target;
            if(safe) { baritone.getInputOverrideHandler().clearAllKeys();ctx.playerController().resetBlockRemoving(); }
            quietTicks=viewed?0:quietTicks+1;
            if(hunt) {
                long now=System.currentTimeMillis();
                if(ambushAt==0) {
                    long minimum=StealthPolicy.bounded(Baritone.settings().killWatchMinimumMS.value,3000L,120000L);
                    long maximum=Math.max(minimum,StealthPolicy.bounded(Baritone.settings().killWatchMaximumMS.value,3000L,180000L));
                    ambushAt=now+ThreadLocalRandom.current().nextLong(minimum,maximum+1);
                }
                if(safe&&now>=ambushAt&&StealthPolicy.readyToAmbush(quietTicks,60,facing(target,ctx.playerHead()),sight,alone(target),weapon()>=0,
                        ctx.player().getHealth(),ctx.player().getFoodData().getFoodLevel(),Baritone.settings().killMinimumHealth.value)) {
                    phase=Phase.APPROACH;watching=false;approachStarted=now;reachedRear=false;attempts++;
                    tell("Ambush opportunity: "+targetName+" is looking away; approaching from behind");return pause();
                }
            }
            return pause();
        }
        quietTicks=0;
        if(++noVantageTicks>1200)return stop("No open line-of-sight viewing point became reachable");
        if(vantageRetry--<=0) { vantage=findVantage(target);vantageRetry=20; }
        if(vantage!=null)return path(new GoalNear(vantage,0));
        if(failed)return stop("No terrain-preserving route to the target");
        if(!inBand)return path(new GoalStandoff(target.blockPosition(),distance()));
        if(safe)baritone.getInputOverrideHandler().clearAllKeys();
        return pause();
    }

    private boolean openPoint(BlockPos pos) {
        if(!Baritone.settings().stalkPreferOpenAir.value)return true;
        for(int dy=2;dy<=12;dy++) {
            BlockPos above=pos.above(dy);var state=ctx.world().getBlockState(above);
            if(!state.isAir()&&!state.is(BlockTags.LEAVES))return false;
        }
        return ctx.world().canSeeSky(pos.above(12));
    }
    private BlockPos findVantage(Player target) {
        CalculationContext context=new CalculationContext(baritone);BlockPos center=target.blockPosition();
        double initial=Math.atan2(ctx.player().getZ()-target.getZ(),ctx.player().getX()-target.getX());
        BlockPos best=null;double bestScore=Double.POSITIVE_INFINITY;
        int vertical=StealthPolicy.bounded(Baritone.settings().stalkVerticalRange.value,2,8);
        for(int step=0;step<16;step++) {
            double angle=initial+step*Math.PI/8;
            int x=(int)Math.floor(target.getX()+Math.cos(angle)*distance()),z=(int)Math.floor(target.getZ()+Math.sin(angle)*distance());
            for(int y=center.getY()-vertical;y<=center.getY()+vertical;y++) {
                BlockPos pos=new BlockPos(x,y,z);
                if(failedVantages.contains(pos)||!ctx.world().hasChunkAt(pos)||!openPoint(pos)
                        ||!MovementHelper.canWalkThrough(context,x,y,z)||!MovementHelper.canWalkThrough(context,x,y+1,z)
                        ||!MovementHelper.canWalkOn(context,x,y-1,z)||!context.get(pos).getFluidState().isEmpty()||!context.get(pos.above()).getFluidState().isEmpty())continue;
                Vec3 eye=new Vec3(x+0.5,y+1.27,z+0.5);
                if(pos.equals(ctx.playerFeet())&&!ctx.player().hasLineOfSight(target))continue;
                if(ctx.world().clip(new ClipContext(eye,target.getEyePosition(),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,ctx.player())).getType()!=HitResult.Type.MISS)continue;
                double score=ctx.playerFeet().distSqr(pos)*0.025+Math.max(0,facing(target,eye)+0.2)*800+Math.abs(y-center.getY())*4;
                if(score<bestScore) { bestScore=score;best=pos; }
            }
        }return best;
    }

    private PathingCommand scatter(Player target,String reason,boolean finish) {
        if(target!=null)scatterAnchor=target.blockPosition().immutable();
        else if(scatterAnchor==null)scatterAnchor=searchAnchor;
        restoreWeapon();phase=Phase.SCATTER;watching=false;gazeTicks=quietTicks=scatterTicks=scatterQuiet=failedPaths=0;
        vantage=null;failedVantages.clear();endAfterScatter=finish;scatterReason=reason;ambushAt=0;
        baritone.getInputOverrideHandler().clearAllKeys();baritone.getPathingBehavior().softCancelIfSafe();tell(reason);return pause();
    }
    private PathingCommand scatterTick(Player target,boolean failed,boolean safe) {
        if(target!=null)scatterAnchor=target.blockPosition().immutable();
        if(scatterAnchor==null)return stop("No retreat anchor was available");
        if(failed&&++failedPaths>=3)return stop("No safe terrain-preserving scatter route");
        if(++scatterTicks>1200)return stop("Scatter limit reached");
        int separation=Math.min(128,Math.max(64,distance()+24));
        GoalRetreat goal=new GoalRetreat(List.of(new GoalRetreat.Threat(scatterAnchor,separation)));
        boolean clear=goal.isInGoal(ctx.playerFeet())&&(target==null||!beingWatched(target));
        scatterQuiet=clear?scatterQuiet+1:0;
        if(clear&&scatterQuiet>=40&&scatterTicks>=StealthPolicy.bounded(Baritone.settings().stalkScatterTicks.value,40,1200)) {
            if(endAfterScatter)return stop(scatterReason);
            if(hunt&&attempts>=StealthPolicy.bounded(Baritone.settings().killMaxAttempts.value,1,10))return stop("Ambush attempt limit reached");
            phase=Phase.SEARCH;searchStarted=System.currentTimeMillis();noVantageTicks=failedPaths=0;vantageRetry=0;
            tell("Lost the stare; resuming observation from a new angle");return pause();
        }
        if(clear) { if(safe)baritone.getInputOverrideHandler().clearAllKeys();return pause(); }
        return path(goal);
    }

    private PathingCommand approach(Player target,boolean failed,boolean safe) {
        if(failed||System.currentTimeMillis()-approachStarted>45000)return scatter(target,"Ambush approach failed; withdrawing",false);
        if(!canFight()||!alone(target))return scatter(target,"Ambush conditions changed; withdrawing",true);
        if(target.hasLineOfSight(ctx.player())&&facing(target,ctx.playerHead())>0.2)return scatter(target,"Target turned toward the approach; scattering",false);
        if(target.distanceToSqr(ctx.player())<=8&&ctx.player().hasLineOfSight(target)&&safe) {
            phase=Phase.ATTACK;attackStarted=System.currentTimeMillis();startingTargetHealth=target.getHealth();attackCount=0;
            return attack(target,false,true);
        }
        if(!reachedRear) {
            Vec3 look=target.getLookAngle();double length=Math.hypot(look.x,look.z);
            if(length<0.01)return scatter(target,"Cannot establish a rear approach",false);
            BlockPos rear=BlockPos.containing(target.getX()-look.x/length*6,target.getY(),target.getZ()-look.z/length*6);
            GoalNear rearGoal=new GoalNear(rear,1);if(!rearGoal.isInGoal(ctx.playerFeet()))return path(rearGoal);reachedRear=true;
        }
        return path(new GoalNear(target.blockPosition(),1));
    }
    private PathingCommand attack(Player target,boolean failed,boolean safe) {
        if(failed||!canFight()||!alone(target))return scatter(target,"Combat no longer favorable; retreating",true);
        if(System.currentTimeMillis()-attackStarted>StealthPolicy.bounded(Baritone.settings().killAttackTimeoutMS.value,3000L,60000L))
            return scatter(target,"Combat timed out; target death was not confirmed",true);
        if(attackCount>=3&&System.currentTimeMillis()-attackStarted>6000&&target.getHealth()>=startingTargetHealth)
            return scatter(target,"No confirmed health reduction; PvP may be disabled or blocked",true);
        if(target.distanceToSqr(ctx.player())>8||!ctx.player().hasLineOfSight(target))return path(new GoalNear(target.blockPosition(),1));
        if(!safe||ctx.minecraft().gameMode==null)return pause();
        int selected=weapon();if(selected<0)return scatter(target,"Weapon unavailable",true);
        baritone.getInputOverrideHandler().clearAllKeys();ctx.playerController().resetBlockRemoving();
        if(previousSlot<0)previousSlot=ctx.player().getInventory().getSelectedSlot();
        ctx.player().getInventory().setSelectedSlot(selected);ctx.playerController().syncHeldItem();
        var rotation=RotationUtils.calcRotationFromVec3d(ctx.playerHead(),target.getEyePosition(),ctx.playerRotations());baritone.getLookBehavior().updateTarget(rotation,true);
        if(ctx.playerRotations().isReallyCloseTo(rotation)&&ctx.player().getAttackStrengthScale(0)>=0.95f) {
            ctx.minecraft().gameMode.attack(ctx.player(),target);ctx.player().swing(InteractionHand.MAIN_HAND,SwingAnimation.DEFAULT,false);attackCount++;
        }return pause();
    }

    private PathingCommand search(boolean failed,boolean safe) {
        long timeout=StealthPolicy.bounded(Baritone.settings().stalkSearchTimeoutMS.value,10000L,600000L);
        if(System.currentTimeMillis()-searchStarted>timeout)return stop("Target not found within the search limit");
        if(searchGoal!=null) {
            if(failed||++legTicks>600) { searchGoal=null;legTicks=siteWait=0; }
            else if(searchGoal.isInGoal(ctx.playerFeet())) {
                if(++siteWait<40) { if(safe)baritone.getInputOverrideHandler().clearAllKeys();return pause(); }
                searchGoal=null;legTicks=siteWait=0;
            }
        }
        if(searchGoal==null) {
            Seen last=seen.get(targetId);
            if(!checkingMemoryOnly&&!checkedLastSeen&&last!=null) { checkedLastSeen=true;searchGoal=new GoalStandoff(last.position(),distance()); }
            else if(memoryStep<rememberedPlaces.size()) {
                var place=rememberedPlaces.get(memoryStep++);searchGoal=new GoalStandoff(MiningRouteAccess.position(place.point()),distance());
                tell("Checking "+place.label()+" for "+targetName+" at "+coordinates(place.point().dimension(),MiningRouteAccess.position(place.point())));
            } else {
                if(checkingMemoryOnly)return stop("Saved places checked; no player visually confirmed");
                int radius=SocialPolicy.searchRadius(Baritone.settings().stalkSearchRadius.value);
                if(searchStep>=SocialPolicy.searchSteps(radius))return stop("Target not found in the bounded local search");
                SocialPolicy.Offset offset=SocialPolicy.searchOffset(searchStep++,radius);
                searchGoal=new GoalXZ(searchAnchor.getX()+offset.x(),searchAnchor.getZ()+offset.z());
            }
        }return path(searchGoal);
    }

    private void record(Player target) {
        long now=System.currentTimeMillis();BlockPos pos=target.blockPosition();
        if(now-lastRecorded>=15000) {
            int observed=lastRecorded==0||now-lastRecorded>30000?0:(int)((now-lastRecorded)/1000);
            var place=memory().observe(targetId,targetName,MiningRouteAccess.endpoint(ctx,pos),now,observed,retention());
            lastRecorded=now;
            if(place.possibleBase()&&place.observedSeconds()<=210)tell("Repeated sightings suggest a possible base at "+coordinates(place.point().dimension(),MiningRouteAccess.position(place.point()))+"; use stalk base to confirm");
        }
        double moved=lastReported==null?Double.POSITIVE_INFINITY:lastReported.distSqr(pos);
        if(Baritone.settings().stalkReportSightings.value&&StealthPolicy.shouldReport(now,lastReport,moved,Baritone.settings().stalkReportIntervalMS.value)) {
            tell("Found "+targetName+" at "+coordinates(MiningRouteAccess.dimension(ctx),pos));lastReport=now;lastReported=pos.immutable();
        }
    }
    private String coordinates(String dimension,BlockPos point) { return point.getX()+" "+point.getY()+" "+point.getZ()+" ("+dimension+")"; }
    private void tell(String message) {
        logDirect(message);UUID recipient=owner;
        if(recipient==null&&memory!=null)recipient=memory.reportTo();
        if(recipient!=null)baritone.getBotControl().reply(recipient,message);
    }
    private void restoreWeapon() {
        if(previousSlot>=0&&ctx.player()!=null) { ctx.player().getInventory().setSelectedSlot(previousSlot);if(ctx.minecraft().gameMode!=null)ctx.playerController().syncHeldItem(); }
        previousSlot=-1;
    }
    private PathingCommand path(Goal goal) { return new PathingCommand(goal,PathingCommandType.REVALIDATE_GOAL_AND_PATH); }
    private PathingCommand pause() { return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE); }
    private PathingCommand stop(String reason) {
        tell((hunt?"Hunt":"Stalk")+" stopped: "+reason);lastResult=reason;onLostControl();return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);
    }
    @Override public void onTick(TickEvent event) {
        if(event.getType()==TickEvent.Type.OUT||ctx.world()==null||ctx.player()==null) { onLostControl();seen.clear();observedWorld=null;return; }
        if(observedWorld!=null&&observedWorld!=ctx.world())onLostControl();rememberPlayers();
        if(!isActive())return;
        boolean controlled=baritone.getPathingControlManager().mostRecentInControl().orElse(null)==this;
        if(!controlled) { watching=false;quietTicks=gazeTicks=0;return; }
        if(quietMovement()&&(watching||Baritone.settings().stalkSneakWhileMoving.value))
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK,true);
        else baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK,false);
        if(watching&&watchedPlayer!=null) {
            baritone.getInputOverrideHandler().clearAllKeys();baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK,true);
            baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(),watchedPlayer.getEyePosition(),ctx.playerRotations()),true);
        }
    }
    @Override public void onLostControl() {
        restoreWeapon();targetId=owner=null;targetName=null;hunt=checkingMemoryOnly=watching=endAfterScatter=reachedRear=checkedLastSeen=false;
        phase=Phase.SEARCH;watchedPlayer=null;searchAnchor=vantageAnchor=vantage=lastReported=scatterAnchor=null;searchGoal=null;
        searchStep=memoryStep=legTicks=siteWait=vantageRetry=noVantageTicks=gazeTicks=quietTicks=scatterTicks=scatterQuiet=failedPaths=attempts=attackCount=0;
        ambushAt=approachStarted=attackStarted=lastRecorded=lastReport=0;rememberedPlaces=List.of();failedVantages.clear();
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK,false);baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT,false);
    }
    @Override public void onPlayerDeath() { onLostControl(); }
    @Override public String displayName0() { return (hunt?"Hunt ":"Stalk ")+targetName+": "+phase.name().toLowerCase(Locale.ROOT)+" (view distance "+distance()+")"; }
}
