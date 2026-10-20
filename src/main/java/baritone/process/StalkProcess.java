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
import baritone.api.event.events.BlockChangeEvent;
import baritone.api.pathing.goals.*;
import baritone.api.pathing.movement.IMovement;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.path.PathExecutor;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.mining.MiningRouteAccess;
import baritone.utils.social.*;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
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
    private enum Phase { SEARCH, WATCH, DRINK, SCATTER, APPROACH, ATTACK }
    private record Seen(BlockPos position,long time) {}
    private final Map<UUID,Seen> seen=new HashMap<>();
    private final ExplosiveCombat explosives;
    private CombatStyle combatStyle=CombatStyle.MELEE;
    private boolean meleeFallback;
    private final Set<BlockPos> failedVantages=new HashSet<>();
    private Level observedWorld;
    private Path memoryRoot;
    private PlayerMemory memory;
    private UUID targetId,owner;
    private String targetName,lastResult="No stalk target";
    private Phase phase=Phase.SEARCH,afterDrink=Phase.SEARCH;
    private boolean hunt,checkingMemoryOnly,watching,endAfterScatter,reachedRear,perceptible,ownsUse,drinkStarted,invisibilityFailed,damageConfirmed;
    private BlockPos searchAnchor,vantageAnchor,vantage,lastReported,scatterAnchor,scatterWaypoint;
    private int scatterDistance,scatterReplan;
    private double scatterAngle;
    private Goal searchGoal;
    private Player watchedPlayer;
    private List<PlayerMemory.Place> rememberedPlaces=List.of();
    private int searchStep,memoryStep,legTicks,siteWait,vantageRetry,noVantageTicks,gazeTicks,quietTicks,scatterTicks,scatterQuiet,failedPaths,attempts,previousSlot=-1;
    private int drinkTicks,drinkWait,totemDelay,explosiveRecheck;
    private boolean checkedLastSeen;
    private long searchStarted,taskStarted,ambushAt,approachStarted,attackStarted,lastProgress,lastRecorded,lastReport,nextMemoryError;
    private float lastTargetHealth;
    private int attackCount;
    private String scatterReason="";

    public StalkProcess(Baritone bot) { super(bot);explosives=new ExplosiveCombat(bot); }
    @Override public boolean isActive() { return targetId!=null; }
    public boolean hunting() { return isActive()&&hunt; }
    public boolean terrainLocked() { return isActive()&&Baritone.settings().stalkMinimalEvidence.value; }
    /** Quiet (no sprint, crouch where allowed) only while another player could hear or possibly see the bot. */
    public boolean quietMovement() { return isActive()&&perceptible&&quietPhase()&&!baritone.getConflictProcess().isActive(); }
    private boolean quietPhase() { return phase==Phase.SEARCH||phase==Phase.WATCH||phase==Phase.DRINK||phase==Phase.APPROACH&&!reachedRear; }
    /** An engaged hunt keeps fighting; only low health or being outnumbered withdraws it. */
    public boolean committedCombat() { return isActive()&&hunt&&(phase==Phase.APPROACH||phase==Phase.ATTACK); }
    public boolean holdsUse() { return ownsUse&&baritone.getPathingControlManager().mostRecentInControl().orElse(null)==this; }
    public boolean scattering() { return isActive()&&phase==Phase.SCATTER; }
    public BlockPos avoidanceCenter() {
        if(!isActive()||ctx.world()==null||phase==Phase.APPROACH||phase==Phase.ATTACK)return null;
        Player target=loaded(targetId);return target==null?null:target.blockPosition().immutable();
    }
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
    public void start(String requested) { start(requested,false,false,CombatStyle.MELEE); }
    public void startHunt(String requested) { startHunt(requested,CombatStyle.parse(Baritone.settings().killCombatStyle.value)); }
    public void startHunt(String requested,CombatStyle style) { start(requested,true,false,style); }
    public void checkPlaces(String requested) { start(requested,false,true,CombatStyle.MELEE); }

    private void start(String requested,boolean kill,boolean checkOnly,CombatStyle style) {
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
            if(style==CombatStyle.ANCHOR&&!BlastPolicy.anchorDimension(MiningRouteAccess.dimension(ctx)))
                throw new IllegalStateException("Use crystal, melee, or auto here; explosive anchor combat is supported in the Overworld and End");
            if(!Baritone.settings().killAllowTrustedTargets.value&&baritone.getBotControl().isTrusted(selected))
                throw new IllegalStateException("Trusted controllers are protected from kill; change killAllowTrustedTargets locally to opt in");
            if(!equipmentReady(style))throw new IllegalStateException("Carry usable hotbar supplies for "+style.name().toLowerCase(Locale.ROOT)+" combat; anchor needs anchors, glowstone, and a non-glowstone trigger hand");
            if(!canStart(style))throw new IllegalStateException("Recover health and hunger before an ambush");
        }
        UUID controller=baritone.getJobManager().requestOwner();baritone.getPathingBehavior().cancelEverything();
        owner=controller;targetId=selected;targetName=name;hunt=kill;combatStyle=style;meleeFallback=style==CombatStyle.AUTO&&!explosives.supplies(CombatStyle.AUTO);checkingMemoryOnly=checkOnly;observedWorld=ctx.world();
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
    private boolean equipmentReady(CombatStyle style) {
        return style==CombatStyle.MELEE?weapon()>=0:explosives.inProgress()||explosives.supplies(style)||(style==CombatStyle.AUTO&&weapon()>=0);
    }
    private boolean canStart(CombatStyle style) {
        return StealthPolicy.mayStartCombat(ctx.player().getHealth(),ctx.player().getFoodData().getFoodLevel(),
                Baritone.settings().killMinimumHealth.value,equipmentReady(style));
    }
    private boolean totemReady() { return ctx.player().getOffhandItem().is(Items.TOTEM_OF_UNDYING)||ctx.player().getMainHandItem().is(Items.TOTEM_OF_UNDYING); }
    private int opponentsNear(Player target) {
        int count=0;
        for(Player other:ctx.world().players())
            if(other!=ctx.player()&&other.isAlive()&&!other.isSpectator()&&(other.getUUID().equals(targetId)||!baritone.getBotControl().isTrusted(other.getUUID()))
                    &&(other.distanceToSqr(ctx.player())<=256||other.distanceToSqr(target)<=144))count++;
        return count;
    }
    private String retreatReason(Player target) {
        if(StealthPolicy.lowHealth(ctx.player().getHealth(),ctx.player().getAbsorptionAmount(),Baritone.settings().killRetreatHealth.value,totemReady()))
            return "Health is low; retreating";
        if(StealthPolicy.outnumbered(opponentsNear(target),Baritone.settings().killRetreatPlayers.value))return "Several players are near the fight; retreating";
        if(weapon()<0&&(combatStyle==CombatStyle.MELEE||!explosives.inProgress()&&!explosives.supplies(combatStyle)))
            return "No usable weapon or explosive supplies remain; withdrawing";
        return null;
    }
    public boolean handlesCombatAgainst(Entity attacker) {
        Player target=committedCombat()?loaded(targetId):null;
        if(target==null||baritone.getSculkProcess().isActive()||retreatReason(target)!=null)return false;
        // Hits from the target, its own crystals, and mobs do not end an engaged fight; another player's attack still does.
        return target.getUUID().equals(attacker.getUUID())||explosives.expectedCrystal(attacker)||!(attacker instanceof Player);
    }
    public void onEntityDamaged(int entityId) {
        Player target=committedCombat()&&ctx.world()!=null?loaded(targetId):null;
        if(target!=null&&target.getId()==entityId) { damageConfirmed=true;lastProgress=System.currentTimeMillis(); }
    }

    @Override public PathingCommand onTick(boolean failed,boolean safe) {
        watching=false;watchedPlayer=null;
        try {
            if(ctx.player()==null||ctx.world()==null||!ctx.player().isAlive()||ctx.world()!=observedWorld||ctx.minecraft().getConnection()==null)
                return stop("Bot or world changed");
            if(phase!=Phase.DRINK)releaseUse();
            if(hunt&&!committedCombat()&&System.currentTimeMillis()-taskStarted>StealthPolicy.bounded(Baritone.settings().killTimeoutMS.value,60000L,3600000L))
                return stop("Hunt timed out; no kill is assumed");
            if(hunt&&phase==Phase.ATTACK) {
                Player entity=ctx.world().players().stream().filter(player->player.getUUID().equals(targetId)).findFirst().orElse(null);
                if(entity!=null&&(!entity.isAlive()||entity.getHealth()<=0))return scatter(entity,"Target death observed; withdrawing",true);
            }
            Player target=loaded(targetId);
            if(hunt&&(committedCombat()||phase==Phase.SCATTER))equipTotem();
            if(phase==Phase.SCATTER)return scatterTick(target,failed,safe);
            if(!checkingMemoryOnly&&ctx.minecraft().getConnection().getPlayerInfo(targetId)==null)return stop("Target left the server; no kill is assumed");
            if(phase==Phase.DRINK)return drink(target,safe);
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
            if(!committedCombat()&&gazeTicks>=StealthPolicy.bounded(Baritone.settings().stalkGazeTicks.value,10,200))
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
        boolean sight=ctx.player().hasLineOfSight(target);
        quietTicks=sight&&!viewed?quietTicks+1:0;
        if(hunt) { PathingCommand ambush=ambush(target,sight,safe);if(ambush!=null)return ambush; }
        boolean inBand=new GoalStandoff(target.blockPosition(),distance()).isInGoal(ctx.playerFeet())
                &&StealthPolicy.openHeight(ctx.player().getY()-target.getY(),Baritone.settings().stalkVerticalRange.value);
        boolean open=inBand&&openPoint(ctx.playerFeet());
        if(inBand&&sight&&open&&facing(target,ctx.playerHead())<0.45) {
            noVantageTicks=0;vantage=null;watching=safe;watchedPlayer=target;
            if(safe) { baritone.getInputOverrideHandler().clearAllKeys();ctx.playerController().resetBlockRemoving(); }
            return pause();
        }
        if(++noVantageTicks>1200)return stop("No open line-of-sight viewing point became reachable");
        if(vantageRetry--<=0) { vantage=findVantage(target);vantageRetry=20; }
        if(vantage!=null)return path(new GoalNear(vantage,0));
        if(failed)return stop("No terrain-preserving route to the target");
        if(!inBand)return path(new GoalStandoff(target.blockPosition(),distance()));
        if(safe)baritone.getInputOverrideHandler().clearAllKeys();
        return pause();
    }

    /** A hunt starts from wherever it already sees the target instead of first settling into the viewing band. */
    private PathingCommand ambush(Player target,boolean sight,boolean safe) {
        if(!sight||Math.hypot(target.getX()-ctx.player().getX(),target.getZ()-ctx.player().getZ())>distance()+8)return null;
        long now=System.currentTimeMillis();
        if(ambushAt==0) {
            long minimum=StealthPolicy.bounded(Baritone.settings().killWatchMinimumMS.value,0L,120000L);
            long maximum=Math.max(minimum,StealthPolicy.bounded(Baritone.settings().killWatchMaximumMS.value,0L,180000L));
            ambushAt=now+ThreadLocalRandom.current().nextLong(minimum,maximum+1);
        }
        if(!safe||now<ambushAt||!StealthPolicy.readyToAmbush(quietTicks,20,facing(target,ctx.playerHead()),invisible()?0.45:0,sight,alone(target),
                equipmentReady(combatStyle),ctx.player().getHealth(),ctx.player().getFoodData().getFoodLevel(),Baritone.settings().killMinimumHealth.value))return null;
        watching=false;
        if(wantsInvisibility())return startDrink(Phase.APPROACH,"Drinking invisibility before the approach");
        return enterApproach(target);
    }
    private PathingCommand enterApproach(Player target) {
        phase=Phase.APPROACH;approachStarted=System.currentTimeMillis();reachedRear=false;failedPaths=0;attempts++;
        tell("Ambush opportunity: "+targetName+" is looking away; approaching");return pause();
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
        String remaining=explosives.remaining();if(remaining!=null)tell(remaining);
        releaseUse();restoreWeapon();explosives.reset();phase=Phase.SCATTER;watching=false;gazeTicks=quietTicks=scatterTicks=scatterQuiet=failedPaths=0;
        scatterWaypoint=null;scatterReplan=0;scatterDistance=Math.min(128,Math.max(64,distance()+24));
        scatterAngle=Math.atan2(ctx.player().getZ()-scatterAnchor.getZ(),ctx.player().getX()-scatterAnchor.getX())+(ThreadLocalRandom.current().nextBoolean()?1:-1)*Math.PI/2;
        vantage=null;failedVantages.clear();endAfterScatter=finish;scatterReason=reason;ambushAt=0;
        baritone.getInputOverrideHandler().clearAllKeys();baritone.getPathingBehavior().softCancelIfSafe();tell(reason);return pause();
    }
    private PathingCommand scatterTick(Player target,boolean failed,boolean safe) {
        if(target!=null)scatterAnchor=target.blockPosition().immutable();
        if(scatterAnchor==null)return stop("No retreat anchor was available");
        if(failed&&++failedPaths>=3)return stop("No safe terrain-preserving scatter route");
        if(++scatterTicks>1200)return stop("Scatter limit reached");
        GoalRetreat goal=new GoalRetreat(List.of(new GoalRetreat.Threat(scatterAnchor,scatterDistance)));
        boolean viewed=target!=null&&beingWatched(target);
        if(goal.isInGoal(ctx.playerFeet())&&viewed&&scatterTicks%40==0) {
            scatterDistance=Math.min(128,scatterDistance+16);scatterWaypoint=null;scatterReplan=0;
            goal=new GoalRetreat(List.of(new GoalRetreat.Threat(scatterAnchor,scatterDistance)));
        }
        boolean clear=goal.isInGoal(ctx.playerFeet())&&!viewed;
        scatterQuiet=clear?scatterQuiet+1:0;
        if(clear&&scatterQuiet>=40&&scatterTicks>=StealthPolicy.bounded(Baritone.settings().stalkScatterTicks.value,40,1200)) {
            if(endAfterScatter)return stop(scatterReason);
            if(hunt&&attempts>=StealthPolicy.bounded(Baritone.settings().killMaxAttempts.value,1,10))return stop("Ambush attempt limit reached");
            if(wantsInvisibility())return startDrink(Phase.SEARCH,"Out of sight; drinking invisibility before re-approaching");
            phase=Phase.SEARCH;searchStarted=System.currentTimeMillis();noVantageTicks=failedPaths=0;vantageRetry=0;
            tell("Out of sight; resuming observation from a new angle");return pause();
        }
        if(clear) { if(safe)baritone.getInputOverrideHandler().clearAllKeys();return pause(); }
        if(failed) { scatterWaypoint=null;scatterReplan=0;scatterAngle+=Math.PI/4; }
        if(scatterReplan--<=0||scatterWaypoint!=null&&new GoalNear(scatterWaypoint,0).isInGoal(ctx.playerFeet())) {
            scatterWaypoint=findEscapePoint(target);scatterReplan=40;
        }
        return path(scatterWaypoint==null?goal:new GoalNear(scatterWaypoint,0));
    }
    private BlockPos findEscapePoint(Player target) {
        CalculationContext context=new CalculationContext(baritone);BlockPos best=null;double score=Double.POSITIVE_INFINITY;
        for(int step=0;step<8;step++) {
            double angle=scatterAngle+step*Math.PI/4;
            int x=(int)Math.floor(scatterAnchor.getX()+Math.cos(angle)*(scatterDistance+2));
            int z=(int)Math.floor(scatterAnchor.getZ()+Math.sin(angle)*(scatterDistance+2));
            for(int y=ctx.playerFeet().getY()-3;y<=ctx.playerFeet().getY()+3;y++) {
                BlockPos pos=new BlockPos(x,y,z);
                if(!ctx.world().hasChunkAt(pos)||!openPoint(pos)||!MovementHelper.canWalkThrough(context,x,y,z)
                        ||!MovementHelper.canWalkThrough(context,x,y+1,z)||!MovementHelper.canWalkOn(context,x,y-1,z)
                        ||!context.get(pos).getFluidState().isEmpty()||!context.get(pos.above()).getFluidState().isEmpty())continue;
                Vec3 eye=new Vec3(x+0.5,y+1.27,z+0.5);
                boolean visible=target!=null&&ctx.world().clip(new ClipContext(eye,target.getEyePosition(),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,ctx.player())).getType()==HitResult.Type.MISS;
                double candidate=ctx.playerFeet().distSqr(pos)*0.03+(visible?200:0)+(target==null?0:Math.max(0,facing(target,eye))*400);
                if(candidate<score) { score=candidate;best=pos; }
            }
        }return best;
    }

    private PathingCommand approach(Player target,boolean failed,boolean safe) {
        if(failed&&++failedPaths>=3||System.currentTimeMillis()-approachStarted>45000)return scatter(target,"Ambush approach failed; withdrawing",false);
        String retreat=retreatReason(target);if(retreat!=null)return scatter(target,retreat,true);
        double facing=facing(target,ctx.playerHead());
        if(!reachedRear&&target.hasLineOfSight(ctx.player())&&facing>0.2) { reachedRear=true;tell("Spotted during the approach; engaging "+targetName); }
        double attackRange=combatStyle==CombatStyle.MELEE||meleeFallback?8:25;
        if(target.distanceToSqr(ctx.player())<=attackRange&&ctx.player().hasLineOfSight(target)&&safe) {
            phase=Phase.ATTACK;attackStarted=lastProgress=System.currentTimeMillis();attackCount=failedPaths=explosiveRecheck=0;damageConfirmed=false;
            lastTargetHealth=target.getHealth()+target.getAbsorptionAmount();
            return attack(target,false,true);
        }
        if(!reachedRear) {
            Vec3 look=target.getLookAngle();double length=Math.hypot(look.x,look.z);
            // Already close behind the target (or no usable facing): skip the rear waypoint and strike.
            if(length<0.01||facing<-0.3&&target.distanceToSqr(ctx.player())<=64)reachedRear=true;
            else {
                BlockPos rear=BlockPos.containing(target.getX()-look.x/length*6,target.getY(),target.getZ()-look.z/length*6);
                GoalNear rearGoal=new GoalNear(rear,1);if(!rearGoal.isInGoal(ctx.playerFeet()))return path(rearGoal);reachedRear=true;
            }
        }
        return path(new GoalNear(target.blockPosition(),1));
    }
    private PathingCommand attack(Player target,boolean failed,boolean safe) {
        long now=System.currentTimeMillis();
        String retreat=retreatReason(target);if(retreat!=null)return scatter(target,retreat,true);
        float health=target.getHealth()+target.getAbsorptionAmount();
        if(health<lastTargetHealth) { damageConfirmed=true;lastProgress=now; }
        lastTargetHealth=health;
        if(failed&&++failedPaths>=4)return scatter(target,"Cannot reach the target; returning to stalking",false);
        if(!damageConfirmed&&(attackCount>=4||explosives.confirmedCycles()>=2)&&now-attackStarted>6000)
            return scatter(target,"No confirmed damage; PvP may be disabled or blocked",true);
        if(now-lastProgress>StealthPolicy.bounded(Baritone.settings().killAttackTimeoutMS.value,5000L,600000L))
            return scatter(target,"No hit landed for a while; returning to stalking",false);
        if(combatStyle!=CombatStyle.MELEE&&meleeFallback&&++explosiveRecheck>=40) {
            explosiveRecheck=0;
            if(explosives.available(target,combatStyle)) { meleeFallback=false;tell("A blast position opened up; switching back to explosives"); }
        }
        if(combatStyle!=CombatStyle.MELEE&&!meleeFallback&&!explosives.inProgress()&&!explosives.supplies(combatStyle)&&weapon()>=0) {
            meleeFallback=true;explosives.reset();tell("Out of explosive supplies; switching to melee");
        }
        if(combatStyle!=CombatStyle.MELEE&&!meleeFallback) {
            if(target.distanceToSqr(ctx.player())>36)return path(new GoalNear(target.blockPosition(),4));
            if(!safe||ctx.minecraft().gameMode==null)return pause();
            baritone.getInputOverrideHandler().clearAllKeys();ctx.playerController().resetBlockRemoving();
            if(previousSlot<0)previousSlot=ctx.player().getInventory().getSelectedSlot();
            var step=explosives.tick(target,combatStyle);
            String warning=explosives.takeWarning();if(warning!=null)tell(warning);
            if(step.problem()!=null) {
                if(weapon()<0)return scatter(target,step.problem(),true);
                meleeFallback=true;explosives.reset();tell(step.problem()+"; fighting in melee until a blast position opens");
            } else if(step.fallback()) {
                if(weapon()<0)return scatter(target,"No eligible explosive action and no usable melee fallback",true);
                meleeFallback=true;explosives.reset();tell("No eligible explosive position/supply remains; switching to melee");
            } else return step.goal()==null?pause():path(step.goal());
        }
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

    private PathingCommand startDrink(Phase next,String message) {
        phase=Phase.DRINK;afterDrink=next;drinkStarted=false;drinkTicks=drinkWait=0;watching=false;tell(message);return pause();
    }
    private PathingCommand drink(Player target,boolean safe) {
        if(invisible()||++drinkTicks>100)return finishDrink(target);
        if(drinkStarted) {
            if(ctx.player().isUsingItem())return pause();
            releaseUse();return ++drinkWait>20?finishDrink(target):pause(); // the effect arrives from the server after the drink finishes
        }
        int slot=invisibilityPotion();
        if(slot<0||drinkTicks>20)return finishDrink(target);
        if(!safe||ctx.minecraft().gameMode==null)return pause();
        baritone.getInputOverrideHandler().clearAllKeys();ctx.playerController().resetBlockRemoving();
        if(previousSlot<0)previousSlot=ctx.player().getInventory().getSelectedSlot();
        ctx.player().getInventory().setSelectedSlot(slot);ctx.playerController().syncHeldItem();ownsUse=true;
        if(ctx.player().isUsingItem()&&ctx.player().getUseItem().is(Items.POTION))drinkStarted=true;
        else if(!ctx.player().isUsingItem())ctx.playerController().processRightClick(ctx.player(),ctx.world(),InteractionHand.MAIN_HAND);
        return pause();
    }
    private PathingCommand finishDrink(Player target) {
        releaseUse();restoreWeapon();drinkStarted=false;drinkTicks=drinkWait=0;
        if(!invisible()) { invisibilityFailed=true;tell("Invisibility was not confirmed; continuing without potions"); }
        if(afterDrink==Phase.APPROACH&&target!=null&&ctx.player().hasLineOfSight(target))return enterApproach(target);
        phase=Phase.SEARCH;searchStarted=System.currentTimeMillis();noVantageTicks=failedPaths=vantageRetry=0;ambushAt=0;return pause();
    }
    private void releaseUse() {
        boolean release=ownsUse;ownsUse=false;
        if(release&&ctx.player()!=null&&ctx.player().isUsingItem()&&ctx.minecraft().gameMode!=null)ctx.minecraft().gameMode.releaseUsingItem(ctx.player());
    }
    private boolean wantsInvisibility() {
        return Baritone.settings().stalkUseInvisibility.value&&!invisibilityFailed&&!invisible()&&invisibilityPotion()>=0;
    }
    /** Re-drinking is allowed once less than 30 seconds of invisibility remain. */
    private boolean invisible() {
        MobEffectInstance effect=ctx.player().getEffect(MobEffects.INVISIBILITY);
        return effect!=null&&(effect.isInfiniteDuration()||effect.getDuration()>600);
    }
    /** Invisible players still show worn armor and held items; only a fully bare bot is hidden from sight. */
    private boolean visuallyHidden() {
        return ctx.player().hasEffect(MobEffects.INVISIBILITY)&&Arrays.stream(EquipmentSlot.values()).allMatch(slot->ctx.player().getItemBySlot(slot).isEmpty());
    }
    private int invisibilityPotion() {
        for(int slot=0;slot<9;slot++) {
            ItemStack stack=ctx.player().getInventory().getItem(slot);PotionContents contents=stack.is(Items.POTION)?stack.get(DataComponents.POTION_CONTENTS):null;
            if(contents!=null)for(MobEffectInstance effect:contents.getAllEffects())if(effect.is(MobEffects.INVISIBILITY))return slot;
        }return -1;
    }
    /** Keeps an inventory totem in the offhand; a popped totem is replaced on a following tick. */
    private void equipTotem() {
        if(!Baritone.settings().killUseTotems.value||ctx.player().getOffhandItem().is(Items.TOTEM_OF_UNDYING))return;
        if(totemDelay>0) { totemDelay--;return; }
        if(ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty())return;
        for(int slot=0;slot<36;slot++)if(ctx.player().getInventory().getItem(slot).is(Items.TOTEM_OF_UNDYING)) {
            ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,slot<9?slot+36:slot,40,ContainerInput.SWAP,ctx.player());
            totemDelay=4;return;
        }
    }
    private boolean noticeable() {
        boolean hidden=visuallyHidden();
        for(Player other:ctx.world().players()) {
            if(other==ctx.player()||!other.isAlive()||other.isSpectator())continue;
            if(StealthPolicy.mayBeNoticed(Math.sqrt(other.distanceToSqr(ctx.player())),facing(other,ctx.playerHead()),hidden,Baritone.settings().stalkSneakHearingRange.value,
                    Baritone.settings().stalkSneakViewAngle.value,()->other.hasLineOfSight(ctx.player())))return true;
        }return false;
    }
    /** Crouching stops a player at ledges, so crouch is released near the edge of a planned one-block drop and while falling. */
    private boolean steppingDown() {
        PathExecutor current=baritone.getPathingBehavior().getCurrent();
        if(current==null||!baritone.getPathingBehavior().isPathing())return false;
        List<IMovement> movements=current.getPath().movements();int index=current.getPosition();
        if(index<0||index>=movements.size())return false;
        BetterBlockPos src=movements.get(index).getSrc(),dest=movements.get(index).getDest();
        if(dest.y!=src.y-1)return false;
        if(ctx.player().getY()<src.y-0.01)return !ctx.player().onGround();
        return (ctx.player().getX()-(src.x+0.5))*(dest.x-src.x)+(ctx.player().getZ()-(src.z+0.5))*(dest.z-src.z)>=0.25;
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
        if(!controlled) { watching=perceptible=false;quietTicks=gazeTicks=0;releaseUse();return; }
        perceptible=quietPhase()&&noticeable();
        boolean sneak=quietMovement()&&(watching||Baritone.settings().stalkSneakWhileMoving.value)&&!steppingDown();
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK,sneak);
        if(watching&&watchedPlayer!=null) {
            baritone.getInputOverrideHandler().clearAllKeys();baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK,sneak);
            baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(),watchedPlayer.getEyePosition(),ctx.playerRotations()),true);
        }
    }
    @Override public void onBlockChange(BlockChangeEvent event) { explosives.onBlockChange(event); }
    @Override public void onLostControl() {
        if(isActive()) { String remaining=explosives.remaining();if(remaining!=null)tell(remaining); }
        releaseUse();restoreWeapon();explosives.reset();combatStyle=CombatStyle.MELEE;meleeFallback=false;targetId=owner=null;targetName=null;hunt=checkingMemoryOnly=watching=endAfterScatter=reachedRear=checkedLastSeen=false;
        perceptible=drinkStarted=invisibilityFailed=damageConfirmed=false;drinkTicks=drinkWait=totemDelay=explosiveRecheck=0;lastProgress=0;afterDrink=Phase.SEARCH;
        phase=Phase.SEARCH;watchedPlayer=null;searchAnchor=vantageAnchor=vantage=lastReported=scatterAnchor=scatterWaypoint=null;searchGoal=null;scatterDistance=scatterReplan=0;
        searchStep=memoryStep=legTicks=siteWait=vantageRetry=noVantageTicks=gazeTicks=quietTicks=scatterTicks=scatterQuiet=failedPaths=attempts=attackCount=0;
        ambushAt=approachStarted=attackStarted=lastRecorded=lastReport=0;rememberedPlaces=List.of();failedVantages.clear();
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK,false);baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT,false);
    }
    @Override public void onPlayerDeath() { onLostControl(); }
    @Override public String displayName0() { return (hunt?"Hunt ["+(meleeFallback?"melee":combatStyle.name().toLowerCase(Locale.ROOT))+"] ":"Stalk ")+targetName+": "+phase.name().toLowerCase(Locale.ROOT)+" (view distance "+distance()+")"; }
}
