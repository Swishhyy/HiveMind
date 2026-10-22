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
import baritone.utils.network.TeamPackets.Kind;
import baritone.utils.bot.TrackingOrderStore.Order;
import baritone.utils.bot.AerialSurvey;
import baritone.utils.bot.SurveyRoute;
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
import baritone.utils.network.NetworkProtocol.Sighting;
import baritone.utils.network.SharedSightings;
import baritone.utils.social.*;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
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

/** UUID-specific tracking using loaded entity contacts, real sight lines for reports, and real melee. */
public final class StalkProcess extends BaritoneProcessHelper implements IBehavior,AerialSurvey {
    private enum Phase { SEARCH, WATCH, DRINK, SCATTER, APPROACH, ATTACK }
    private record Seen(BlockPos position,long time) {}
    private final Map<UUID,Seen> seen=new HashMap<>();
    private Seen nearbyContact;
    private final ExplosiveCombat explosives;
    private final WatchPolicy.Station watchStation=new WatchPolicy.Station();
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
    private Sighting peerSearch;
    private long peerSearchAt;
    private int searchStep,memoryStep,legTicks,siteWait,gazeTicks,quietTicks,scatterTicks,scatterQuiet,failedPaths,attempts,previousSlot=-1;
    private int drinkTicks,drinkWait,totemDelay,explosiveRecheck,explosiveBackoff=5;
    private boolean stalkSneak;
    private String lastWarning;
    private boolean checkedLastSeen;
    private boolean recycleVantages,targetMissing;
    private int retryCount;
    private long retryAt,lastRetryReport;
    private String retryReason="";
    private Order restoring;
    private boolean retainOrder,offlineWaiting;
    private SurveyRoute survey;
    private boolean surveyAir,freshSurveyAnchor;
    private long lastContactReport;
    private long nextSupplyMove;
    private long searchStarted,taskStarted,ambushAt,approachStarted,attackStarted,lastProgress,lastStrike,lastRecorded,lastReport,nextMemoryError;
    private float lastTargetHealth;
    private int attackCount;
    private String scatterReason="";

    public StalkProcess(Baritone bot) { super(bot);explosives=new ExplosiveCombat(bot); }
    @Override public boolean isActive() { return targetId!=null; }
    public boolean hunting() { return isActive()&&hunt; }
    public UUID target(){return targetId;}
    public BlockPos teamPoint(){BlockPos blast=explosives.teamPoint();return blast!=null?blast:vantage!=null?vantage:ctx.player()==null?null:ctx.playerFeet().immutable();}
    public boolean teamExplosives(){return hunting()&&!meleeFallback&&combatStyle!=CombatStyle.MELEE&&phase!=Phase.SCATTER&&equipmentReady(combatStyle)&&ctx.player().getHealth()>Baritone.settings().killRetreatHealth.value;}
    public double terrainPenalty(){return TraversalPolicy.evidencePenalty(isActive(),hunt,Baritone.settings().stalkMinimalEvidence.value,Baritone.settings().stalkTerrainPenalty.value);}
    /** Quiet (no sprint, crouch where allowed) only while another player could hear or possibly see the bot. */
    public boolean quietMovement() { return isActive()&&perceptible&&quietPhase()&&!baritone.getConflictProcess().isActive(); }
    private boolean quietPhase() { return phase==Phase.SEARCH||phase==Phase.WATCH||phase==Phase.DRINK||phase==Phase.APPROACH&&!reachedRear; }
    /** An engaged hunt keeps fighting; only low health or being outnumbered withdraws it. */
    public boolean committedCombat() { return isActive()&&hunt&&(phase==Phase.APPROACH||phase==Phase.ATTACK); }
    /** Eating would stand the bot still beside the target, so care waits until a hunt's fight and retreat are over. */
    public boolean suppressesCare() { return isActive()&&hunt&&(committedCombat()||phase==Phase.SCATTER); }
    public boolean holdsUse() { return ownsUse&&baritone.getPathingControlManager().mostRecentInControl().orElse(null)==this; }
    public boolean scattering() { return isActive()&&phase==Phase.SCATTER; }
    public BlockPos avoidanceCenter() {
        if(!isActive()||ctx.world()==null||phase==Phase.APPROACH||phase==Phase.ATTACK)return null;
        Player target=loaded(targetId);return target==null?null:target.blockPosition().immutable();
    }
    public String status() { return isActive()?displayName0():baritone.getAutonomy()!=null&&ctx.world()!=null?baritone.getAutonomy().status():lastResult; }
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
        if(known==null)known=baritone.getNetwork().resolve(name);
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
        baritone.getNetwork().confirmSite(point,canonical);
    }
    public void resume(Order order) { restoring=order;try{start(order.name(),order.hunt(),false,CombatStyle.parse(order.style()));}finally{restoring=null;} }
    public void transported(){
        long now=System.currentTimeMillis();searchStarted=taskStarted=now;
        if(committedCombat()){approachStarted=lastProgress=now;}
    }
    public void surveyContact(Player target){
        if(!isActive()||!target.getUUID().equals(targetId))return;
        contact(target);searchAnchor=target.blockPosition().immutable();freshSurveyAnchor=true;targetMissing=false;
        phase=Phase.WATCH;searchGoal=null;watchStation.reset();transported();
    }
    public void trackContact(Player target){
        if(!isActive()||!target.getUUID().equals(targetId))return;
        nearbyContact=new Seen(target.blockPosition().immutable(),System.currentTimeMillis());
        searchAnchor=nearbyContact.position();freshSurveyAnchor=true;
    }
    private Seen lastLocal(){
        Seen visual=seen.get(targetId);
        return nearbyContact!=null&&System.currentTimeMillis()-nearbyContact.time()<=120000&&(visual==null||nearbyContact.time()>visual.time())?nearbyContact:visual;
    }
    private void contact(Player target){
        trackContact(target);
        if(ctx.player().hasLineOfSight(target)){record(target);return;}
        long now=System.currentTimeMillis();
        if(now-lastContactReport>=30000){lastContactReport=now;tell("Nearby entity contact: "+targetName+" at "+coordinates(MiningRouteAccess.dimension(ctx),target.blockPosition())+"; no clear sight line");}
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
                .filter(info->!info.getProfile().id().equals(ctx.player().getUUID())&&!baritone.getNetwork().isTeammate(info.getProfile().id())).toList();
        UUID selected;String name;
        boolean random=requested==null||requested.equalsIgnoreCase("random");
        if(kill&&random)throw new IllegalStateException("Name one player explicitly: kill <player>");
        if(restoring!=null){selected=restoring.target();name=restoring.name();}
        else if(checkOnly) {
            selected=identity(requested);
            var known=memory().places(selected,MiningRouteAccess.dimension(ctx),System.currentTimeMillis(),retention());
            Sighting shared=baritone.getNetwork().hint(selected,MiningRouteAccess.dimension(ctx));
            if(known.isEmpty()&&shared==null)throw new IllegalStateException("No saved places or recent peer marks for that player in this dimension");
            name=known.isEmpty()?shared.playerName():known.get(0).name();
        } else {
            PlayerInfo profile;
            if(random) {
                List<PlayerInfo> nearby=online.stream().filter(info->loaded(info.getProfile().id())!=null).toList();
                List<PlayerInfo> pool=nearby.isEmpty()?online:nearby;
                if(pool.isEmpty())throw new IllegalStateException("No other players are online");
                profile=pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            } else profile=online.stream().filter(info->info.getProfile().name().equalsIgnoreCase(requested)).findFirst().orElse(null);
            if(profile==null){
                if(!requested.matches("[A-Za-z0-9_]{1,16}"))throw new IllegalStateException("Use a valid player name");
                selected=memory().resolve(requested,System.currentTimeMillis(),retention());if(selected==null)selected=baritone.getNetwork().resolve(requested);name=requested;
            }else{selected=profile.getProfile().id();name=profile.getProfile().name();}
        }
        if(baritone.getNetwork().isTeammate(selected))throw new IllegalStateException("Connected bots are teammates and cannot be stalked or hunted");
        if(ctx.player().getUUID().equals(selected))throw new IllegalStateException("The bot cannot target itself");
        if(!kill&&Baritone.settings().stalkMinimalEvidence.value)for(EquipmentSlot slot:EquipmentSlot.values())
            if(ctx.player().getItemBySlot(slot).getEnchantments().keySet().stream().anyMatch(holder->holder.is(Enchantments.FROST_WALKER)))
                throw new IllegalStateException("Remove Frost Walker equipment for minimal-evidence tracking");
        if(kill) {
            if(style==CombatStyle.ANCHOR&&!BlastPolicy.anchorDimension(MiningRouteAccess.dimension(ctx)))
                throw new IllegalStateException("Use crystal, melee, or auto here; explosive anchor combat is supported in the Overworld and End");
            if(!Baritone.settings().killAllowTrustedTargets.value&&baritone.getBotControl().isTrusted(selected))
                throw new IllegalStateException("Trusted controllers are protected from kill; change killAllowTrustedTargets locally to opt in");
        }
        UUID controller=restoring==null?baritone.getJobManager().requestOwner():restoring.owner();
        if(restoring==null){
            baritone.getPathingBehavior().cancelEverything();
            if(!checkOnly&&Baritone.settings().botPersistentTracking.value)baritone.getAutonomy().arm(new Order(name,selected,kill,style.name().toLowerCase(Locale.ROOT),controller,System.currentTimeMillis()));
        }
        if(selected==null){
            String message="Waiting for "+name+" to join; no identity or global coordinates are available";
            baritone.getAutonomy().defer(message,2000);logDirect("Order saved: "+message);return;
        }
        owner=controller;targetId=selected;targetName=name;hunt=kill;combatStyle=style;meleeFallback=style==CombatStyle.AUTO&&!explosives.supplies(CombatStyle.AUTO);checkingMemoryOnly=checkOnly;observedWorld=ctx.world();
        taskStarted=searchStarted=System.currentTimeMillis();searchAnchor=ctx.playerFeet().immutable();
        rememberedPlaces=memory().places(targetId,MiningRouteAccess.dimension(ctx),taskStarted,retention());
        tell((hunt?"Hunting ":"Stalking ")+targetName+"; observing from about "+distance()+" blocks");
        if(loaded(targetId)==null)tell("Checking saved sightings/bases, then an expanding survey; target coordinates are not globally available");
    }

    private Player loaded(UUID id) {
        return baritone.getAwareness().player(id);
    }
    private void rememberPlayers() {
        if(ctx.world()!=observedWorld) { seen.clear();observedWorld=ctx.world(); }
        long now=System.currentTimeMillis();seen.entrySet().removeIf(entry->now-entry.getValue().time()>120000);
        for(Player player:ctx.world().players())if(player!=ctx.player()&&player.isAlive()&&!player.isSpectator()&&ctx.player().hasLineOfSight(player))
            seen.put(player.getUUID(),new Seen(player.blockPosition().immutable(),now));
    }
    private double facing(Player target,Vec3 point) {
        Vec3 look=target.getLookAngle(),delta=point.subtract(target.getEyePosition());
        return StealthPolicy.gazeDot(look.x,look.y,look.z,delta.x,delta.y,delta.z);
    }
    private boolean beingWatched(Player target) {
        return StealthPolicy.looking(facing(target,ctx.playerHead()),target.hasLineOfSight(ctx.player()),Baritone.settings().stalkViewConeDegrees.value);
    }
    private int weapon() { return weapon(false); }
    /** An axe disables a raised shield, so it is preferred while the target blocks. */
    private int weapon(boolean preferAxe) {
        return MeleeCombat.weapon(ctx,preferAxe);
    }
    private boolean alone(Player target) {
        return ctx.world().players().stream().noneMatch(other->other!=ctx.player()&&!other.getUUID().equals(target.getUUID())&&!other.isSpectator()&&other.isAlive()
                &&other.distanceToSqr(target)<144&&!baritone.getBotControl().isTrusted(other.getUUID())&&!baritone.getNetwork().isTeammate(other.getUUID()));
    }
    private boolean equipmentReady(CombatStyle style) {
        return style==CombatStyle.MELEE?weapon()>=0:explosives.inProgress()||explosives.carries(style)||explosives.supplies(style)||(style==CombatStyle.AUTO&&weapon()>=0);
    }
    private boolean canStart(CombatStyle style) {
        return StealthPolicy.mayStartCombat(ctx.player().getHealth(),ctx.player().getFoodData().getFoodLevel(),
                Baritone.settings().killMinimumHealth.value,equipmentReady(style));
    }
    /** Restore needed supplies displaced by flight; one normal inventory swap at a time. */
    public boolean prepareSupplies(CombatStyle style){
        if(!Baritone.settings().allowInventory.value||ctx.player()==null||!ctx.player().isAlive()||ctx.minecraft().gameMode==null||baritone.getConflictProcess().isActive()||baritone.getSculkProcess().isActive()||baritone.getElytraProcess().isActive()||ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty())return false;
        long now=System.currentTimeMillis();if(now<nextSupplyMove)return true;
        List<java.util.function.Predicate<ItemStack>> needs=new ArrayList<>();
        needs.add(stack->(stack.is(ItemTags.SWORDS)||stack.is(ItemTags.AXES))&&(!stack.isDamageableItem()||stack.getMaxDamage()-stack.getDamageValue()>Math.max(1,Baritone.settings().botToolReserve.value)));
        if(style==CombatStyle.AUTO||style==CombatStyle.CRYSTAL){needs.add(stack->stack.is(Items.END_CRYSTAL));needs.add(stack->stack.is(Items.OBSIDIAN));}
        if(style==CombatStyle.AUTO||style==CombatStyle.ANCHOR){needs.add(stack->stack.is(Items.RESPAWN_ANCHOR));needs.add(stack->stack.is(Items.GLOWSTONE));}
        for(var need:needs){
            boolean ready=false;for(int i=0;i<9;i++)if(need.test(ctx.player().getInventory().getItem(i))){ready=true;break;}if(ready)continue;
            int source=-1;for(int i=9;i<36;i++)if(need.test(ctx.player().getInventory().getItem(i))){source=i;break;}if(source<0)continue;
            int destination=-1;for(int i=1;i<8;i++)if(ctx.player().getInventory().getItem(i).isEmpty()){destination=i;break;}
            if(destination<0)for(int i=7;i>=1;i--){ItemStack stack=ctx.player().getInventory().getItem(i);if(needs.stream().noneMatch(test->test.test(stack))){destination=i;break;}}
            if(destination<0)return false;
            ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,source,destination,ContainerInput.SWAP,ctx.player());nextSupplyMove=now+500;return true;
        }
        return false;
    }
    private boolean totemReady() { return ctx.player().getOffhandItem().is(Items.TOTEM_OF_UNDYING)||ctx.player().getMainHandItem().is(Items.TOTEM_OF_UNDYING); }
    private int opponentsNear(Player target) {
        int count=0;
        for(Player other:ctx.world().players())
            if(other!=ctx.player()&&other.isAlive()&&!other.isSpectator()&&!baritone.getNetwork().isTeammate(other.getUUID())&&(other.getUUID().equals(targetId)||!baritone.getBotControl().isTrusted(other.getUUID()))
                    &&(other.distanceToSqr(ctx.player())<=256||other.distanceToSqr(target)<=144))count++;
        return count;
    }
    private String retreatReason(Player target) {
        if(StealthPolicy.lowHealth(ctx.player().getHealth(),ctx.player().getAbsorptionAmount(),Baritone.settings().killRetreatHealth.value,totemReady()))
            return "Health is low; retreating";
        if(StealthPolicy.outnumbered(opponentsNear(target),Baritone.settings().killRetreatPlayers.value))return "Several players are near the fight; retreating";
        if(weapon()<0&&(combatStyle==CombatStyle.MELEE||!explosives.inProgress()&&!explosives.carries(combatStyle)))
            return "No usable weapon or explosive supplies remain; withdrawing";
        return null;
    }
    public boolean handlesCombatAgainst(Entity attacker) {
        Player target=isActive()&&hunt&&ctx.world()!=null?loaded(targetId):null;
        if(target==null||baritone.getSculkProcess().isActive()||retreatReason(target)!=null)return false;
        boolean fromTarget=target.getUUID().equals(attacker.getUUID());
        if(!committedCombat()) {
            // The target striking first starts the fight; only a retreat that is already under way keeps fleeing.
            if(!fromTarget||phase==Phase.SCATTER&&endAfterScatter)return false;
            releaseUse();restoreWeapon();drinkStarted=watching=false;
            phase=Phase.APPROACH;approachStarted=System.currentTimeMillis();reachedRear=true;failedPaths=0;
            tell(targetName+" attacked first; fighting back");return true;
        }
        // Hits from the target, its own crystals, and mobs do not end an engaged fight; another player's attack still does.
        return fromTarget||explosives.expectedCrystal(attacker)||!(attacker instanceof Player);
    }
    /** Only damage the bot caused counts as progress: its own hits and crystals, or a sourceless blast from its own anchor. */
    public void onEntityDamaged(int entityId,DamageSource source) {
        Player target=committedCombat()&&ctx.world()!=null?loaded(targetId):null;
        if(target==null||target.getId()!=entityId)return;
        Entity cause=source.getEntity();
        if(cause==ctx.player()||cause==null&&source.is(DamageTypeTags.IS_EXPLOSION)&&combatStyle!=CombatStyle.MELEE&&!meleeFallback)progress(System.currentTimeMillis());
    }
    private void progress(long now) { damageConfirmed=true;lastProgress=now;failedPaths=0; }

    @Override public PathingCommand onTick(boolean failed,boolean safe) {
        watching=false;watchedPlayer=null;
        try {
            if(ctx.player()==null||ctx.world()==null||!ctx.player().isAlive()||ctx.world()!=observedWorld||ctx.minecraft().getConnection()==null)
                return stop("Bot or world changed");
            if(baritone.getNetwork().isTeammate(targetId)){baritone.getAutonomy().clear();return stop("Target is a connected teammate");}
            if(hunt&&!committedCombat()&&safe&&prepareSupplies(combatStyle))return pause();
            if(phase==Phase.SEARCH&&!targetMissing&&waitingToRetry(safe))return pause();
            if(phase!=Phase.DRINK)releaseUse();
            if(ctx.minecraft().getConnection().getPlayerInfo(targetId)==null&&!checkingMemoryOnly)return offlineWait(safe);
            if(offlineWaiting){offlineWaiting=false;phase=Phase.SEARCH;searchGoal=null;taskStarted=searchStarted=System.currentTimeMillis();retryAt=0;targetMissing=true;tell(targetName+" is online again; resuming search");}
            if(hunt&&!committedCombat()&&System.currentTimeMillis()-taskStarted>StealthPolicy.bounded(Baritone.settings().killTimeoutMS.value,60000L,3600000L))return stop("Search pass timed out; retrying the saved order");
            if(hunt&&phase==Phase.ATTACK) {
                Player entity=ctx.world().players().stream().filter(player->player.getUUID().equals(targetId)).findFirst().orElse(null);
                if(entity!=null&&(!entity.isAlive()||entity.getHealth()<=0)){baritone.getAutonomy().complete();return scatter(entity,"Target death observed; withdrawing",true);}
            }
            Player target=loaded(targetId);
            if(phase==Phase.SCATTER)return scatterTick(target,failed,safe);
            if(phase==Phase.DRINK)return drink(target,safe);
            if(hunt&&target!=null&&committedCombat()){
                if(!baritone.getNetwork().team().ready())return watch(target,safe);
                int spacing=baritone.getNetwork().team().supportDistance(targetId);if(spacing>0)return support(target,safe,spacing);
            }
            if(target==null) {
                if(phase==Phase.ATTACK){explosives.abandon();restoreWeapon();tell("Target left client view; searching again without assuming a kill");}
                targetMissing=true;
                if(!hunt&&watchStation.hold(false,true,Baritone.settings().stalkLostSightTicks.value))return watch(null,safe);
                watchStation.reset();
                phase=Phase.SEARCH;quietTicks=0;ambushAt=0;return search(failed,safe);
            }
            if(hunt&&(target.isCreative()||target.isSpectator()))return stop("Target is not currently a survival-mode opponent");
            if(hunt&&!Baritone.settings().killAllowTrustedTargets.value&&baritone.getBotControl().isTrusted(targetId))
                {baritone.getAutonomy().clear();return stop("Target became a trusted controller");}
            contact(target);
            if(hunt){
                String retreat=retreatReason(target);if(retreat!=null)return scatter(target,retreat,true);
                if(baritone.getFlight().pursue(this,target))return pause();
            }
            searchAnchor=target.blockPosition().immutable();searchStarted=System.currentTimeMillis();searchGoal=null;
            legTicks=siteWait=searchStep=0;checkedLastSeen=false;memoryStep=0;
            boolean viewed=beingWatched(target);
            gazeTicks=StealthPolicy.gazeTicks(gazeTicks,viewed,Baritone.settings().stalkGazeTicks.value);
            if(!committedCombat()&&gazeTicks>=StealthPolicy.bounded(Baritone.settings().stalkGazeTicks.value,10,200))
                return scatter(target,"Player watched the bot too long; scattering",false);
            if(phase==Phase.ATTACK)return attack(target,failed,safe);
            if(phase==Phase.APPROACH)return approach(target,failed,safe);
            if(targetMissing) { retryAt=0;retryCount=0;targetMissing=false; }
            phase=Phase.WATCH;return observe(target,failed,safe,viewed);
        } catch(RuntimeException error) {
            if(hunt)return stop("Tracking error: "+error.getMessage());
            watchStation.reset();vantage=null;searchGoal=null;targetMissing=false;
            return retry(Phase.SEARCH,"Tracking interrupted: "+error.getMessage(),safe);
        }
    }

    private PathingCommand observe(Player target,boolean failed,boolean safe,boolean viewed) {
        boolean sight=ctx.player().hasLineOfSight(target);
        quietTicks=sight&&!viewed?quietTicks+1:0;
        if(hunt) { PathingCommand ambush=ambush(target,sight,safe);if(ambush!=null)return ambush; }
        if(baritone.getNetwork().team().yieldWatch(ctx.playerFeet(),targetId)){watchStation.reset();failedVantages.add(ctx.playerFeet().immutable());}
        // Once settled, small movements, head turns, and brief occlusions do not start another path.
        if(watchStation.hold(sight,watchRange(target,true),Baritone.settings().stalkLostSightTicks.value)) {
            retryCount=0;retryAt=0;return watch(target,safe);
        }
        if(waitingToRetry(safe))return pause();
        if(failed) {
            if(vantage!=null)failedVantages.add(vantage);
            vantage=null;
            return retry(Phase.WATCH,"Viewing route blocked; trying another concealed spot",safe);
        }
        int tolerance=StealthPolicy.bounded(Baritone.settings().stalkPositionTolerance.value,4,32);
        if(vantageAnchor!=null&&vantageAnchor.distSqr(target.blockPosition())>tolerance*tolerance) {
            // A moving target need not invalidate a destination that still offers the same useful view.
            if(vantage==null||!WatchPolicy.inRange(Math.hypot(vantage.getX()+0.5-target.getX(),vantage.getZ()+0.5-target.getZ()),
                    vantage.getY()-target.getY(),distance(),tolerance,Baritone.settings().stalkVerticalRange.value,true)
                    ||!clearRay(new Vec3(vantage.getX()+0.5,vantage.getY()+1.27,vantage.getZ()+0.5),target.getEyePosition()))vantage=null;
            failedVantages.clear();vantageAnchor=target.blockPosition().immutable();
        }
        // Keep the chosen destination while travelling. Re-scoring every second caused visible zigzags.
        if(vantage!=null) {
            if(new GoalNear(vantage,0).isInGoal(ctx.playerFeet())) {
                if(sight&&watchRange(target,true)) {
                    watchStation.settle();retryCount=0;vantage=null;return watch(target,safe);
                }
                failedVantages.add(vantage);vantage=null;
                return retry(Phase.WATCH,"Viewing spot lost its sight line; waiting before repositioning",safe);
            }
            return path(new GoalNear(vantage,0));
        }
        if(recycleVantages) { failedVantages.clear();recycleVantages=false; }
        vantage=findVantage(target);vantageAnchor=target.blockPosition().immutable();
        if(vantage!=null) {
            if(new GoalNear(vantage,0).isInGoal(ctx.playerFeet())) {
                watchStation.settle();retryCount=0;vantage=null;return watch(target,safe);
            }
            return path(new GoalNear(vantage,0));
        }
        recycleVantages=true;
        return retry(Phase.WATCH,"No concealed sight line is reachable yet; staying put and retrying",safe);
    }

    private PathingCommand support(Player target,boolean safe,int spacing){
        lastProgress=approachStarted=System.currentTimeMillis();
        if(safe){baritone.getInputOverrideHandler().clearAllKeys();ctx.playerController().resetBlockRemoving();}
        double range=ctx.player().distanceToSqr(target);
        if(range<spacing*spacing)return path(new GoalRetreat(List.of(new GoalRetreat.Threat(target.blockPosition(),spacing+2))));
        if(range>(spacing+10)*(spacing+10))return path(new GoalNear(target.blockPosition(),spacing+3));
        return watch(target,safe);
    }
    private boolean watchRange(Player target,boolean settled) {
        return WatchPolicy.inRange(Math.hypot(ctx.player().getX()-target.getX(),ctx.player().getZ()-target.getZ()),ctx.player().getY()-target.getY(),
                distance(),Baritone.settings().stalkPositionTolerance.value,Baritone.settings().stalkVerticalRange.value,settled);
    }
    private PathingCommand watch(Player target,boolean safe) {
        watching=safe;watchedPlayer=target;
        if(safe) { baritone.getInputOverrideHandler().clearAllKeys();ctx.playerController().resetBlockRemoving(); }
        return pause();
    }

    /** A hunt starts from wherever it already sees the target instead of first settling into the viewing band. */
    private PathingCommand ambush(Player target,boolean sight,boolean safe) {
        if(!baritone.getNetwork().team().ready())return null;
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
        watchStation.reset();retryAt=0;
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
        BlockPos current=ctx.playerFeet().immutable();
        if(!failedVantages.contains(current)&&watchRange(target,false)&&openPoint(current)&&ctx.player().hasLineOfSight(target)
                &&clearRay(new Vec3(current.getX()+0.5,current.getY()+1.27,current.getZ()+0.5),target.getEyePosition())) {
            best=current;bestScore=vantageScore(target,current);
        }
        int vertical=StealthPolicy.bounded(Baritone.settings().stalkVerticalRange.value,2,8);
        int slack=Math.max(4,StealthPolicy.bounded(Baritone.settings().stalkPositionTolerance.value,4,32)/2);
        for(int radius:new int[]{distance(),distance()-slack,distance()+slack})for(int step=0;step<24;step++) {
            double angle=initial+step*Math.PI/12;
            int x=(int)Math.floor(target.getX()+Math.cos(angle)*radius),z=(int)Math.floor(target.getZ()+Math.sin(angle)*radius);
            for(int y=center.getY()-vertical;y<=center.getY()+vertical;y++) {
                BlockPos pos=new BlockPos(x,y,z);
                if(!WatchPolicy.inRange(Math.hypot(x+0.5-target.getX(),z+0.5-target.getZ()),y-target.getY(),distance(),
                        Baritone.settings().stalkPositionTolerance.value,Baritone.settings().stalkVerticalRange.value,false)
                        ||failedVantages.contains(pos)||!ctx.world().hasChunkAt(pos)||!openPoint(pos)
                        ||!MovementHelper.canWalkThrough(context,x,y,z)||!MovementHelper.canWalkThrough(context,x,y+1,z)
                        ||!MovementHelper.canWalkOn(context,x,y-1,z)||!context.get(pos).getFluidState().isEmpty()||!context.get(pos.above()).getFluidState().isEmpty())continue;
                Vec3 eye=new Vec3(x+0.5,y+1.27,z+0.5);
                if(pos.equals(ctx.playerFeet())&&!ctx.player().hasLineOfSight(target))continue;
                if(!clearRay(eye,target.getEyePosition()))continue;
                double score=vantageScore(target,pos);
                if(score<bestScore) { bestScore=score;best=pos; }
            }
        }return best;
    }
    private boolean clearRay(Vec3 from,Vec3 to) {
        return ctx.world().clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,ctx.player())).getType()==HitResult.Type.MISS;
    }
    private double vantageScore(Player target,BlockPos point) {
        Vec3 eye=new Vec3(point.getX()+0.5,point.getY()+1.27,point.getZ()+0.5);
        double range=Math.hypot(eye.x-target.getX(),eye.z-target.getZ());
        return WatchPolicy.vantageScore(ctx.playerFeet().distSqr(point),facing(target,eye),cover(target,point),point.getY()-target.getY(),range-distance())+baritone.getNetwork().team().watchPenalty(point,targetId);
    }
    /** Leave the eye ray open while hiding part of the body and breaking its silhouette with nearby terrain. */
    private double cover(Player target,BlockPos point) {
        Vec3 center=new Vec3(point.getX()+0.5,point.getY(),point.getZ()+0.5),observer=target.getEyePosition();
        Vec3 away=center.subtract(observer);double length=Math.hypot(away.x,away.z);
        if(length<0.01)return 0;
        double sideX=-away.z/length*0.22,sideZ=away.x/length*0.22;
        int obscured=0,adjacent=0;
        for(double height:new double[]{0.4,0.9})for(int side:new int[]{-1,1})
            if(!clearRay(observer,center.add(side*sideX,height,side*sideZ)))obscured++;
        for(BlockPos neighbor:List.of(point.north(),point.south(),point.east(),point.west())) {
            if(!ctx.world().hasChunkAt(neighbor))continue;
            if(!ctx.world().getBlockState(neighbor).getCollisionShape(ctx.world(),neighbor).isEmpty()
                    ||!ctx.world().getBlockState(neighbor.above()).getCollisionShape(ctx.world(),neighbor.above()).isEmpty())adjacent++;
        }
        Vec3 eye=center.add(0,1.27,0);
        boolean backdrop=!clearRay(eye,eye.add(away.x/length*3,0,away.z/length*3));
        return obscured*0.15+Math.min(2,adjacent)*0.125+(backdrop?0.15:0);
    }

    private PathingCommand scatter(Player target,String reason,boolean finish) {
        if(target!=null)scatterAnchor=target.blockPosition().immutable();
        else if(scatterAnchor==null)scatterAnchor=searchAnchor;
        String remaining=explosives.remaining();if(remaining!=null)tell(remaining);
        releaseUse();restoreWeapon();explosives.reset();phase=Phase.SCATTER;watching=false;gazeTicks=quietTicks=scatterTicks=scatterQuiet=failedPaths=0;
        watchStation.reset();retryAt=0;retryCount=0;
        scatterWaypoint=null;scatterReplan=0;scatterDistance=Math.min(128,Math.max(64,distance()+24));
        scatterAngle=Math.atan2(ctx.player().getZ()-scatterAnchor.getZ(),ctx.player().getX()-scatterAnchor.getX())+(ThreadLocalRandom.current().nextBoolean()?1:-1)*Math.PI/2;
        vantage=null;failedVantages.clear();endAfterScatter=finish;scatterReason=reason;ambushAt=0;
        baritone.getInputOverrideHandler().clearAllKeys();baritone.getPathingBehavior().softCancelIfSafe();tell(reason);return pause();
    }
    private PathingCommand scatterTick(Player target,boolean failed,boolean safe) {
        if(target!=null)scatterAnchor=target.blockPosition().immutable();
        if(scatterAnchor==null)scatterAnchor=ctx.playerFeet().immutable();
        if(hunt&&Math.hypot(ctx.player().getX()-scatterAnchor.getX(),ctx.player().getZ()-scatterAnchor.getZ())<Math.max(256,Baritone.settings().botFlightEscapeDistance.value/2)
                &&baritone.getFlight().escape(this,List.of(scatterAnchor))&&!baritone.getElytraProcess().getPath().isEmpty())return pause();
        if(waitingToRetry(safe))return pause();
        if((failed&&++failedPaths>=3)||++scatterTicks>1200) {
            if(hunt)return stop("No safe terrain-preserving scatter route");
            failedPaths=scatterTicks=scatterQuiet=scatterReplan=0;scatterWaypoint=null;scatterAngle+=Math.PI/4;
            return retry(Phase.SCATTER,"Scatter route blocked; waiting and trying another flank",safe);
        }
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
            phase=Phase.SEARCH;searchStarted=System.currentTimeMillis();failedPaths=0;
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
        int hearing=StealthPolicy.bounded(Baritone.settings().stalkSneakHearingRange.value,4,48);
        // Crouch-walking cannot gain on a target that is walking away; close the gap openly instead.
        if(!reachedRear&&Math.hypot(target.getX()-target.xo,target.getZ()-target.zo)>0.08&&target.distanceToSqr(ctx.player())<=hearing*hearing)reachedRear=true;
        double attackRange=combatStyle==CombatStyle.MELEE||meleeFallback?8:25;
        if(target.distanceToSqr(ctx.player())<=attackRange&&ctx.player().hasLineOfSight(target)&&safe) {
            phase=Phase.ATTACK;attackStarted=lastProgress=System.currentTimeMillis();attackCount=failedPaths=explosiveRecheck=0;explosiveBackoff=5;damageConfirmed=false;lastStrike=0;
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
        if(safe&&MeleeCombat.prepare(ctx,target.isBlocking()))return pause();
        float health=target.getHealth()+target.getAbsorptionAmount();
        if(health<lastTargetHealth&&now-lastStrike<1500)progress(now); // health drops right after the bot's own hit or blast
        lastTargetHealth=health;
        if(failed) {
            explosives.routeFailed();
            if(++failedPaths>=4)return scatter(target,"Cannot reach the target; returning to stalking",false);
        }
        if(!damageConfirmed&&(attackCount>=4||explosives.confirmedCycles()>=2)&&now-attackStarted>6000)
            return scatter(target,"No confirmed damage; PvP may be disabled or blocked",true);
        if(now-lastProgress>StealthPolicy.bounded(Baritone.settings().killAttackTimeoutMS.value,5000L,600000L))
            return scatter(target,"No hit landed for a while; returning to stalking",false);
        if(combatStyle!=CombatStyle.MELEE&&safe&&explosives.stock(combatStyle))return pause();
        if(combatStyle!=CombatStyle.MELEE&&meleeFallback&&weapon()<0&&explosives.supplies(combatStyle))meleeFallback=false;
        if(combatStyle!=CombatStyle.MELEE&&meleeFallback&&++explosiveRecheck>=explosiveBackoff) {
            explosiveRecheck=0;
            if(explosives.available(target,combatStyle)) { meleeFallback=false;tell("A blast position opened up; switching back to explosives"); }
            else {
                var move=explosives.relocate(target,combatStyle);
                if(move!=null) { meleeFallback=false;return path(move.goal()); }
            }
        }
        if(combatStyle!=CombatStyle.MELEE&&!meleeFallback) {
            if(!explosives.inProgress()&&!explosives.supplies(combatStyle)&&weapon()>=0)fallBackToMelee("Out of explosive supplies");
        }
        if(combatStyle!=CombatStyle.MELEE&&!meleeFallback) {
            if(!safe||ctx.minecraft().gameMode==null)return pause();
            baritone.getInputOverrideHandler().clearAllKeys();ctx.playerController().resetBlockRemoving();
            if(previousSlot<0)previousSlot=ctx.player().getInventory().getSelectedSlot();
            int cycles=explosives.confirmedCycles();
            var step=explosives.tick(target,combatStyle);
            if(explosives.confirmedCycles()!=cycles)lastStrike=now;
            String warning=explosives.takeWarning();if(warning!=null&&!warning.equals(lastWarning)) { lastWarning=warning;tell(warning); }
            if(step.problem()==null&&!step.fallback())return step.goal()==null?pause():path(step.goal());
            String reason=step.problem()!=null?step.problem():"No eligible explosive position/supply remains";
            var move=explosives.relocate(target,combatStyle);
            if(move!=null)return path(move.goal());
            if(weapon()>=0)fallBackToMelee(reason);
            else if(explosives.supplies(combatStyle)) { // keep pressing without a melee weapon
                if(!reason.equals(lastWarning)) { lastWarning=reason;tell(reason+"; retrying explosives (no melee weapon)"); }
                explosives.reset();return path(new GoalNear(target.blockPosition(),4));
            }
            else return scatter(target,reason+"; no melee weapon to continue",true);
        }
        if(target.distanceToSqr(ctx.player())>8||!ctx.player().hasLineOfSight(target))return path(new GoalNear(target.blockPosition(),1));
        if(!safe||ctx.minecraft().gameMode==null)return pause();
        boolean blocking=target.isBlocking();
        int selected=weapon(blocking);if(selected<0)return scatter(target,"Weapon unavailable",true);
        baritone.getInputOverrideHandler().clearAllKeys();ctx.playerController().resetBlockRemoving();
        if(previousSlot<0)previousSlot=ctx.player().getInventory().getSelectedSlot();
        ctx.player().getInventory().setSelectedSlot(selected);ctx.playerController().syncHeldItem();
        if(MeleeCombat.aim(ctx,baritone,target)&&ctx.player().getAttackStrengthScale(0)>=0.95f) {
            if(!baritone.getNetwork().team().clearStrike(target))return path(new GoalNear(target.blockPosition().offset((int)Math.round(Math.cos(baritone.getNetwork().team().heading(Kind.HUNT,targetId))*3),0,(int)Math.round(Math.sin(baritone.getNetwork().team().heading(Kind.HUNT,targetId))*3)),1));
            ctx.minecraft().gameMode.attack(ctx.player(),target);ctx.player().swing(InteractionHand.MAIN_HAND,SwingAnimation.DEFAULT,false);lastStrike=now;
            if(!blocking)attackCount++; // a raised shield proves PvP is on, so blocked swings do not count toward the no-damage check
        }return pause();
    }
    private void fallBackToMelee(String reason) {
        meleeFallback=true;explosives.abandon();explosiveRecheck=0;explosiveBackoff=Math.min(80,explosiveBackoff*2);
        tell(reason+"; fighting in melee until a blast position opens");
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
        phase=Phase.SEARCH;searchStarted=System.currentTimeMillis();failedPaths=0;ambushAt=0;return pause();
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
        if(totemDelay>0)totemDelay--;
        if(!Baritone.settings().killUseTotems.value||ctx.player().getOffhandItem().is(Items.TOTEM_OF_UNDYING)||totemDelay>0)return;
        if(ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty())return;
        for(int slot=0;slot<36;slot++)if(ctx.player().getInventory().getItem(slot).is(Items.TOTEM_OF_UNDYING)) {
            ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,slot<9?slot+36:slot,40,ContainerInput.SWAP,ctx.player());
            totemDelay=4;return;
        }
    }
    private boolean noticeable() {
        boolean invisible=ctx.player().hasEffect(MobEffects.INVISIBILITY),hidden=visuallyHidden();
        for(Player other:ctx.world().players()) {
            if(other==ctx.player()||!other.isAlive()||other.isSpectator()||baritone.getNetwork().isTeammate(other.getUUID()))continue;
            if(StealthPolicy.mayBeNoticed(Math.sqrt(other.distanceToSqr(ctx.player())),facing(other,ctx.playerHead()),invisible,hidden,Baritone.settings().stalkSneakHearingRange.value,
                    Baritone.settings().stalkSneakViewAngle.value,()->other.hasLineOfSight(ctx.player())))return true;
        }return false;
    }
    /** Crouching stops a player at ledges, so crouch is released near the edge of a planned drop and while falling. */
    private boolean steppingDown() {
        PathExecutor current=baritone.getPathingBehavior().getCurrent();
        if(current==null||!baritone.getPathingBehavior().isPathing())return false;
        List<IMovement> movements=current.getPath().movements();int index=current.getPosition();
        if(index<0||index>=movements.size())return false;
        BetterBlockPos src=movements.get(index).getSrc(),dest=movements.get(index).getDest();
        if(dest.y>=src.y)return false;
        if(dest.x==src.x&&dest.z==src.z)return true; // straight down a ladder or vine, where crouching holds the bot in place
        if(ctx.player().getY()<src.y-0.5)return !ctx.player().onGround(); // slabs, paths and soul sand leave the feet below src

        return (ctx.player().getX()-(src.x+0.5))*(dest.x-src.x)+(ctx.player().getZ()-(src.z+0.5))*(dest.z-src.z)>=0.25;
    }

    private PathingCommand offlineWait(boolean safe){
        if(!offlineWaiting){
            offlineWaiting=true;phase=Phase.SEARCH;explosives.abandon();releaseUse();restoreWeapon();watchStation.reset();searchGoal=null;vantage=null;retryAt=0;
            var logout=baritone.getNetwork().logout(targetId);
            BlockPos point=logout!=null?MiningRouteAccess.position(logout.point()):seen.containsKey(targetId)?seen.get(targetId).position():rememberedPlaces.isEmpty()?null:MiningRouteAccess.position(rememberedPlaces.get(0).point());
            if(point!=null)searchGoal=new GoalStandoff(point,distance());
            tell(targetName+" is offline; order remains active"+(point==null?"; waiting for sightings":"; watching the last known area"));
        }
        taskStarted=searchStarted=System.currentTimeMillis();
        if(searchGoal!=null&&!searchGoal.isInGoal(ctx.playerFeet()))return path(searchGoal);
        if(safe)baritone.getInputOverrideHandler().clearAllKeys();return pause();
    }
    private PathingCommand search(boolean failed,boolean safe) {
        long now=System.currentTimeMillis();Seen local=lastLocal();
        Sighting shared=baritone.getNetwork().hint(targetId,MiningRouteAccess.dimension(ctx));
        if(SharedSightings.shouldRedirect(shared,peerSearch,local==null?0:local.time(),peerSearchAt,now)) {
            peerSearch=shared;peerSearchAt=now;checkedLastSeen=true;searchAnchor=MiningRouteAccess.position(shared.point());
            freshSurveyAnchor=true;
            searchGoal=new GoalStandoff(searchAnchor,distance());searchStarted=now;legTicks=siteWait=0;retryAt=0;
            tell(shared.reporterName()+" saw "+targetName+" at "+coordinates(shared.point().dimension(),searchAnchor)
                    +" "+Math.max(0,(now-shared.observedAt())/1000)+"s ago; checking that report");
            return path(searchGoal);
        }
        if(waitingToRetry(safe))return pause();
        long timeout=StealthPolicy.bounded(Baritone.settings().stalkSearchTimeoutMS.value,10000L,600000L);
        if(System.currentTimeMillis()-searchStarted>timeout)return restartSearch("Search pass finished; keeping the target and trying again",safe,false);
        if(searchGoal!=null) {
            if(failed||++legTicks>600) {
                searchGoal=null;legTicks=siteWait=0;
                return retry(Phase.SEARCH,"Search route blocked; trying the next saved place or search leg",safe);
            }
            else if(searchGoal.isInGoal(ctx.playerFeet())) {
                if(++siteWait<200) { if(safe)baritone.getInputOverrideHandler().clearAllKeys();return pause(); }
                searchGoal=null;legTicks=siteWait=0;
            }
        }
        if(searchGoal==null) {
            Seen last=lastLocal();
            if(!checkingMemoryOnly&&!checkedLastSeen&&last!=null) { checkedLastSeen=true;searchAnchor=last.position();freshSurveyAnchor=true;searchGoal=new GoalStandoff(last.position(),distance()); }
            else if(memoryStep<rememberedPlaces.size()) {
                var place=rememberedPlaces.get(memoryStep++);searchGoal=new GoalStandoff(MiningRouteAccess.position(place.point()),distance());
                searchAnchor=MiningRouteAccess.position(place.point());freshSurveyAnchor=true;
                tell("Checking "+place.label()+" for "+targetName+" at "+coordinates(place.point().dimension(),MiningRouteAccess.position(place.point())));
            } else {
                if(checkingMemoryOnly)return restartSearch("Saved places checked; waiting before checking them again",safe,true);
                boolean aerial=Baritone.settings().botAerialSearch.value&&baritone.getFlight().capable();
                BlockPos point=chooseSurvey(aerial);
                if(point==null)return retry(Phase.SEARCH,"Survey route is blocked by the world border; trying later",safe);
                if(aerial&&baritone.getFlight().survey(this,point))return pause();
            }
        }return path(searchGoal);
    }
    private BlockPos chooseSurvey(boolean aerial){
        if(survey==null||aerial!=surveyAir){
            surveyAir=aerial;BlockPos origin=aerial&&searchAnchor!=null?searchAnchor:ctx.playerFeet();
            survey=new SurveyRoute(MiningRouteAccess.root(ctx),"search-"+targetId+(aerial?"-air":"-ground"),MiningRouteAccess.endpoint(ctx,origin),
                    aerial?Baritone.settings().botFlightSurveySpacing.value:Math.max(64,SocialPolicy.searchRadius(Baritone.settings().stalkSearchRadius.value)/4),
                    baritone.getNetwork().team().heading(Kind.SEARCH,targetId));
        }
        if(freshSurveyAnchor&&searchAnchor!=null){
            if(Math.hypot(survey.origin().x()-searchAnchor.getX(),survey.origin().z()-searchAnchor.getZ())>256)survey.reanchor(MiningRouteAccess.endpoint(ctx,searchAnchor));
            freshSurveyAnchor=false;
        }
        BlockPos point=null;
        for(int attempt=0;attempt<64;attempt++){
            BlockPos candidate=MiningRouteAccess.position(survey.next());
            if(!ctx.world().getWorldBorder().isWithinBounds(candidate)||candidate.distSqr(ctx.playerFeet())<48*48)continue;
            point=candidate;if(!baritone.getNetwork().mapped(candidate))break;
        }
        survey.save();if(point==null)return null;
        searchGoal=new GoalXZ(point.getX(),point.getZ());searchStarted=taskStarted=System.currentTimeMillis();legTicks=siteWait=0;return point;
    }
    @Override public BlockPos nextSurveyWaypoint(){
        if(!isActive()||phase!=Phase.SEARCH||offlineWaiting||checkingMemoryOnly||!Baritone.settings().botAerialSearch.value||!baritone.getFlight().capable())return null;
        return chooseSurvey(true);
    }
    private PathingCommand restartSearch(String reason,boolean safe,boolean recycle) {
        searchGoal=null;legTicks=siteWait=0;
        if(recycle) { searchStep=memoryStep=0;checkedLastSeen=false;peerSearch=null;peerSearchAt=0; }
        searchStarted=System.currentTimeMillis();
        rememberedPlaces=memory().places(targetId,MiningRouteAccess.dimension(ctx),searchStarted,retention());
        return retry(Phase.SEARCH,reason,safe);
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
    private PathingCommand path(Goal goal) {
        if(hunt&&phase!=Phase.SCATTER&&ctx.world()!=null){
            Player target=loaded(targetId);
            if(target!=null){
                if(baritone.getFlight().pursue(this,target))return pause();
                double dx=ctx.player().getX()-target.getX(),dz=ctx.player().getZ()-target.getZ(),length=Math.max(1,Math.hypot(dx,dz));
                BlockPos approach=target.blockPosition().offset((int)(dx/length*24),0,(int)(dz/length*24));
                if(baritone.getFlight().travel(this,approach))return pause();
            }
        }
        if(phase==Phase.SEARCH&&loaded(targetId)==null){
            BlockPos point=goal instanceof GoalXZ g?new BlockPos(g.getX(),ctx.playerFeet().getY(),g.getZ()):goal instanceof GoalStandoff g?g.target():null;
            if(point!=null&&goal instanceof GoalStandoff){double dx=ctx.player().getX()-point.getX(),dz=ctx.player().getZ()-point.getZ(),length=Math.max(1,Math.hypot(dx,dz));point=point.offset((int)(dx/length*96),0,(int)(dz/length*96));}
            if(point!=null&&baritone.getFlight().travel(this,point))return pause();
        }
        return new PathingCommand(goal,PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }
    private PathingCommand pause() { return new PathingCommand(null,PathingCommandType.REQUEST_PAUSE); }
    private PathingCommand retry(Phase next,String reason,boolean safe) {
        phase=next;watching=false;watchedPlayer=null;retryReason=reason;
        retryCount=Math.min(100,retryCount+1);
        long now=System.currentTimeMillis();retryAt=now+WatchPolicy.retryDelay(retryCount,Baritone.settings().stalkRetryDelayMS.value);
        if(safe) { baritone.getInputOverrideHandler().clearAllKeys();ctx.playerController().resetBlockRemoving(); }
        if(lastRetryReport==0||now-lastRetryReport>=60000) { tell(reason+"; stalking remains active");lastRetryReport=now; }
        return pause();
    }
    private boolean waitingToRetry(boolean safe) {
        if(retryAt==0)return false;
        if(System.currentTimeMillis()>=retryAt) { retryAt=0;return false; }
        if(safe) { baritone.getInputOverrideHandler().clearAllKeys();ctx.playerController().resetBlockRemoving(); }
        return true;
    }
    private PathingCommand stop(String reason) {
        retainOrder=Baritone.settings().botPersistentTracking.value&&baritone.getAutonomy().order()!=null;
        tell((hunt?"Hunt":"Stalk")+(retainOrder?" waiting: ":" stopped: ")+reason);lastResult=reason;
        if(retainOrder)baritone.getAutonomy().defer(reason,30000);
        onLostControl();return new PathingCommand(null,PathingCommandType.CANCEL_AND_SET_GOAL);
    }
    @Override public void onTick(TickEvent event) {
        if(event.getType()==TickEvent.Type.OUT||ctx.world()==null||ctx.player()==null) { onLostControl();seen.clear();observedWorld=null;return; }
        if(observedWorld!=null&&observedWorld!=ctx.world())onLostControl();rememberPlayers();
        if(!isActive())return;
        boolean controlled=baritone.getPathingControlManager().mostRecentInControl().orElse(null)==this;
        if(hunt&&(committedCombat()||phase==Phase.SCATTER||baritone.getConflictProcess().isActive())&&!baritone.getConflictProcess().holdsUse())equipTotem();
        if(!controlled) { watching=perceptible=stalkSneak=false;quietTicks=gazeTicks=0;releaseUse();return; }
        perceptible=quietPhase()&&noticeable();
        // A crouch the current movement asked for (sneak-placing, magma) is kept; only the stalk's own crouch follows perception.
        boolean requested=!stalkSneak&&baritone.getPathingBehavior().isPathing()&&baritone.getInputOverrideHandler().isInputForcedDown(Input.SNEAK);
        boolean sneak=!steppingDown()&&(requested||quietMovement()&&(watching||Baritone.settings().stalkSneakWhileMoving.value));
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK,sneak);stalkSneak=sneak&&!requested;
        if(watching&&watchedPlayer!=null) {
            baritone.getInputOverrideHandler().clearAllKeys();baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK,sneak);
            baritone.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(),watchedPlayer.getEyePosition(),ctx.playerRotations()),true);
        }
    }
    @Override public void onBlockChange(BlockChangeEvent event) {
        explosives.onBlockChange(event);
        if(isActive()&&hunt&&meleeFallback&&ctx.player()!=null&&event.getBlocks().stream().anyMatch(change->change.first().distSqr(ctx.playerFeet())<=64))explosiveRecheck=explosiveBackoff;
    }
    @Override public void onLostControl() {
        if(isActive()&&baritone.getAutonomy()!=null&&!retainOrder&&ctx.player()!=null&&ctx.player().isAlive()&&ctx.world()!=null&&ctx.world()==observedWorld)baritone.getAutonomy().clear();
        retainOrder=false;offlineWaiting=false;survey=null;freshSurveyAnchor=false;nearbyContact=null;
        if(isActive()) { String remaining=explosives.remaining();if(remaining!=null)tell(remaining); }
        releaseUse();restoreWeapon();explosives.reset();combatStyle=CombatStyle.MELEE;meleeFallback=false;targetId=owner=null;targetName=null;hunt=checkingMemoryOnly=watching=endAfterScatter=reachedRear=checkedLastSeen=false;
        perceptible=drinkStarted=invisibilityFailed=damageConfirmed=stalkSneak=false;drinkTicks=drinkWait=totemDelay=explosiveRecheck=0;explosiveBackoff=5;lastProgress=lastStrike=0;lastWarning=null;afterDrink=Phase.SEARCH;
        phase=Phase.SEARCH;watchedPlayer=null;searchAnchor=vantageAnchor=vantage=lastReported=scatterAnchor=scatterWaypoint=null;searchGoal=null;scatterDistance=scatterReplan=0;
        watchStation.reset();retryAt=lastRetryReport=0;retryCount=0;retryReason="";recycleVantages=targetMissing=false;
        searchStep=memoryStep=legTicks=siteWait=gazeTicks=quietTicks=scatterTicks=scatterQuiet=failedPaths=attempts=attackCount=0;
        ambushAt=approachStarted=attackStarted=lastRecorded=lastReport=lastContactReport=0;rememberedPlaces=List.of();failedVantages.clear();
        peerSearch=null;peerSearchAt=0;
        baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK,false);baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT,false);
    }
    @Override public void onPlayerDeath() { retainOrder=true;onLostControl(); }
    @Override public String displayName0() {
        String waiting=retryAt>System.currentTimeMillis()?"; retry in "+Math.max(1,(retryAt-System.currentTimeMillis()+999)/1000)+"s: "+retryReason:"";
        return (hunt?"Hunt ["+(meleeFallback?"melee":combatStyle.name().toLowerCase(Locale.ROOT))+"] ":"Stalk ")+targetName+": "+(offlineWaiting?"waiting for login":phase.name().toLowerCase(Locale.ROOT))
                +(watchStation.settled()?" (holding viewing spot)":"")+(hunt&&explosives.positioning()?" (moving toward a blast position)":"")+" (view distance "+distance()+")"+waiting;
    }
}
