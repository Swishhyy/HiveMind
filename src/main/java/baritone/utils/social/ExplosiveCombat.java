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

package baritone.utils.social;

import baritone.Baritone;
import baritone.api.event.events.BlockChangeEvent;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.mining.ConstructionPolicy;
import baritone.utils.mining.MiningRouteAccess;
import baritone.utils.mining.MiningWaypoints;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Explicit explosive attacks bypass no movement protections; only this armed combat state uses block items. */
public final class ExplosiveCombat {
    public record Step(Goal goal,boolean fallback,String problem) {}
    private enum Stage { SELECT,SUPPORT,WAIT_SUPPORT,COVER,PREPARE,WAIT_COVER,WAIT_BASE,CRYSTAL,WAIT_CRYSTAL,WAIT_BREAK,WAIT_ANCHOR,CHARGE,WAIT_CHARGE,DETONATE,WAIT_DETONATE }
    private record Plan(BlockPos pos,CombatStyle style,boolean newBase,List<BlockPos> supports,BlockPos cover,double score) {}
    private final Baritone bot;
    private final IPlayerContext ctx;
    private Stage stage=Stage.SELECT;
    private Plan plan;
    private int ticks,id=-1,idleTicks,confirmedCycles,stockDelay;
    private final CombatCadence cadence=new CombatCadence();
    private long evaluatedTick=Long.MIN_VALUE;
    private BlockPos reusableCrystalBase;
    private int supportIndex,selectDelay;
    private BlockPos lurePoint,firingPoint;
    private long lureUntil,nextLureCheck,firingUntil;
    private final Map<BlockPos,Long> failedFootings=new HashMap<>();
    private boolean blockConfirmed;
    private String lastProblem="No suitable blast position",warning,rejection;
    private final Set<CombatStyle> rejected=EnumSet.noneOf(CombatStyle.class);
    private final Map<Integer,double[]> chainDamage=new HashMap<>();
    private final Set<BlockPos> reusableAnchors=new HashSet<>();
    private long chainTick=-1;
    private double lowestSelfDamage;
    /** Interactive blocks would open/toggle on an unsneaked right-click instead of taking the placed block. */
    private static final Set<String> INTERACTIVE=Set.of("lever","repeater","comparator","crafting_table","crafter","anvil","chipped_anvil","damaged_anvil",
            "grindstone","loom","stonecutter","smithing_table","cartography_table","fletching_table","note_block","composter","cake","flower_pot",
            "respawn_anchor","redstone_wire","dragon_egg","cauldron","water_cauldron","lava_cauldron","powder_snow_cauldron","beehive","bee_nest");

    public ExplosiveCombat(Baritone bot) { this.bot=bot;ctx=bot.getPlayerContext(); }
    public BlockPos teamPoint(){return plan==null?null:BlockPos.containing(blastPoint(plan));}
    public int confirmedCycles() { return confirmedCycles; }
    public boolean inProgress() { return plan!=null&&stage!=Stage.SELECT; }
    public String takeWarning() { String message=warning;warning=null;return message; }
    public String remaining() {
        if(plan==null||ctx.world()==null)return warning;
        boolean blocks=ctx.world().getBlockState(plan.pos()).is(Blocks.RESPAWN_ANCHOR)||plan.newBase()&&ctx.world().getBlockState(plan.pos()).is(Blocks.OBSIDIAN)
                ||plan.supports().stream().anyMatch(pos->ctx.world().getBlockState(pos).is(Blocks.OBSIDIAN));
        boolean cover=plan.cover()!=null&&ctx.world().getBlockState(plan.cover()).is(Blocks.OBSIDIAN);
        boolean crystal=crystals().stream().anyMatch(entity->entity.position().distanceToSqr(blastPoint(plan))<2);
        return blocks||cover||crystal?"Combat blocks/explosive still visible near "+plan.pos().getX()+" "+plan.pos().getY()+" "+plan.pos().getZ()+"; no cleanup was attempted":null;
    }
    /** Whether a crystal/anchor plan is eligible right now; lets a melee fallback switch back to explosives. */
    public boolean available(Player target,CombatStyle requested) { rejected.clear();return !inProgress()&&choose(target,requested)!=null; }
    /** Drops the current plan but remembers which styles failed, so a melee fallback does not immediately retry them. */
    public void abandon() { stage=Stage.SELECT;plan=null;ticks=idleTicks=supportIndex=selectDelay=0;id=-1;blockConfirmed=false;firingPoint=lurePoint=null;firingUntil=lureUntil=0; }
    public boolean expectedCrystal(Entity entity) { return stage==Stage.WAIT_BREAK&&entity instanceof EndCrystal&&entity.getId()==id; }
    public void reset() { abandon();cadence.reset();evaluatedTick=Long.MIN_VALUE;reusableCrystalBase=null;confirmedCycles=stockDelay=0;warning=null;rejected.clear();reusableAnchors.clear();failedFootings.clear();nextLureCheck=0; }
    public void onBlockChange(BlockChangeEvent event) {
        if(plan!=null) {
            BlockPos expected=stage==Stage.WAIT_COVER?plan.cover():stage==Stage.WAIT_SUPPORT&&supportIndex<plan.supports().size()?plan.supports().get(supportIndex):plan.pos();
            for(var change:event.getBlocks())if(change.first().equals(expected))blockConfirmed=true;
        }
    }
    public void routeFailed() {
        long until=System.currentTimeMillis()+30000;
        if(lurePoint!=null)failedFootings.put(lurePoint,until);
        if(firingPoint!=null)failedFootings.put(firingPoint,until);
        lurePoint=firingPoint=null;lureUntil=nextLureCheck=firingUntil=0;
    }
    public boolean supplies(CombatStyle requested) {
        return switch(requested) {
            case MELEE -> false;
            case CRYSTAL -> slot(Items.END_CRYSTAL)>=0;
            case ANCHOR -> anchorAvailable();
            case AUTO -> slot(Items.END_CRYSTAL)>=0||anchorAvailable();
        };
    }
    /** Like supplies(), but also counts the main inventory, from which stock() can refill the hotbar. */
    public boolean carries(CombatStyle requested) {
        return switch(requested) {
            case MELEE -> false;
            case CRYSTAL -> carried(Items.END_CRYSTAL)>=0;
            case ANCHOR -> BlastPolicy.anchorDimension(MiningRouteAccess.dimension(ctx))&&(carried(Items.RESPAWN_ANCHOR)>=0&&carried(Items.GLOWSTONE)>=0||reusableAnchor(carried(Items.GLOWSTONE)>=0));
            case AUTO -> carries(CombatStyle.CRYSTAL)||carries(CombatStyle.ANCHOR);
        };
    }
    /** Moves missing supplies for the style from the main inventory onto free or non-combat hotbar slots; true while still moving them. */
    public boolean stock(CombatStyle requested) {
        if(ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty())return false;
        List<Item> wanted=switch(requested) {
            case MELEE -> List.of();
            case CRYSTAL -> List.of(Items.END_CRYSTAL,Items.OBSIDIAN);
            case ANCHOR -> List.of(Items.RESPAWN_ANCHOR,Items.GLOWSTONE,Items.OBSIDIAN);
            case AUTO -> List.of(Items.END_CRYSTAL,Items.OBSIDIAN,Items.RESPAWN_ANCHOR,Items.GLOWSTONE);
        };
        for(Item item:wanted) {
            int source=carried(item);
            if(slot(item)>=0||source<9)continue;
            int hotbar=freeHotbarSlot();if(hotbar<0)return false;
            if(stockDelay>0) { stockDelay--;return true; }
            ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,source,hotbar,ContainerInput.SWAP,ctx.player());
            stockDelay=3;return true;
        }
        return false;
    }
    private int carried(Item item) {
        for(int i=0;i<36;i++)if(ctx.player().getInventory().getItem(i).is(item))return i;return -1;
    }
    private int freeHotbarSlot() {
        int fallback=-1;
        for(int i=0;i<9;i++) {
            ItemStack stack=ctx.player().getInventory().getItem(i);
            if(stack.isEmpty())return i;
            if(fallback<0&&i!=ctx.player().getInventory().getSelectedSlot()&&!combatItem(stack))fallback=i;
        }return fallback;
    }
    private static boolean combatItem(ItemStack stack) {
        return stack.is(ItemTags.SWORDS)||stack.is(ItemTags.AXES)||stack.is(Items.END_CRYSTAL)||stack.is(Items.OBSIDIAN)||stack.is(Items.RESPAWN_ANCHOR)
                ||stack.is(Items.GLOWSTONE)||stack.is(Items.TOTEM_OF_UNDYING)||stack.is(Items.POTION)||stack.is(Items.ENDER_PEARL)||stack.has(DataComponents.FOOD);
    }
    private boolean anchorAvailable() {
        return BlastPolicy.anchorDimension(MiningRouteAccess.dimension(ctx))&&(slot(Items.RESPAWN_ANCHOR)>=0&&slot(Items.GLOWSTONE)>=0||reusableAnchor(slot(Items.GLOWSTONE)>=0))
                &&!ctx.player().getOffhandItem().is(Items.GLOWSTONE)&&triggerSlot()>=0;
    }
    private boolean reusableAnchor(boolean glowstone) {
        reusableAnchors.removeIf(pos->!ctx.world().hasChunkAt(pos)||!ctx.world().getBlockState(pos).is(Blocks.RESPAWN_ANCHOR));
        return reusableAnchors.stream().anyMatch(pos->pos.distSqr(ctx.playerFeet())<=64&&anchorExplodesAt(pos)
                &&(glowstone||ctx.world().getBlockState(pos).getValue(RespawnAnchorBlock.CHARGE)>0));
    }
    private boolean anchorExplodesAt(BlockPos pos) {
        return BlastPolicy.anchorDimension(MiningRouteAccess.dimension(ctx))
                &&!Boolean.TRUE.equals(ctx.world().environmentAttributes().getValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS,pos));
    }
    private int slot(Item item) {
        for(int i=0;i<9;i++)if(ctx.player().getInventory().getItem(i).is(item))return i;return -1;
    }
    private int triggerSlot() {
        for(int i=0;i<9;i++)if(!ctx.player().getInventory().getItem(i).is(Items.GLOWSTONE))return i;return -1;
    }
    public Step tick(Player target,CombatStyle requested) {
        long tick=ctx.player().tickCount;
        if(!cadence.ready(tick))return waitStep();
        if(evaluatedTick!=tick){ticks++;evaluatedTick=tick;}
        // Drain transitions that only inspect already-confirmed state. At most one use/attack
        // is sent per client tick; missing server confirmations still stop the pipeline.
        for(int transitions=0;transitions<8;transitions++){
            Stage previous=stage;Step result=advance(target,requested);
            if(cadence.acted(tick)||stage==previous||result.goal()!=null||result.fallback()||result.problem()!=null)return result;
        }
        return waitStep();
    }
    public void reactiveTick(Player target,CombatStyle requested){
        // Complete aiming in the same tick, but leave route selection and timeout accounting to the process.
        if(stage!=Stage.SELECT&&stage!=Stage.WAIT_BREAK&&stage!=Stage.WAIT_DETONATE)tick(target,requested);
    }
    private Step advance(Player target,CombatStyle requested) {
        if(ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty())
            return problem("Close inventory menus before explosive combat",requested);
        // Building is evaluated with its planned supports/cover. Only an actual detonation
        // uses the current confirmed geometry; a small movement first tries another firing point.
        if(plan!=null&&(stage==Stage.DETONATE||stage==Stage.WAIT_CRYSTAL&&crystals().stream().anyMatch(entity->entity.position().distanceToSqr(blastPoint(plan))<2))
                &&!worthwhile(target,blastPoint(plan),plan.style()==CombatStyle.CRYSTAL?6:5,plan.style()==CombatStyle.ANCHOR?plan.pos():null)) {
            String reason=rejection;Step move=saferFiringPoint(target);
            if(move!=null) { ticks=0;return move; }
            return problem("Blast conditions changed: "+reason,requested);
        }
        if(ticks>80&&stage!=Stage.SELECT)return problem("Server did not confirm the explosive action",requested);
        switch(stage) {
            case SELECT -> {
                if(target.distanceToSqr(ctx.player())>256)return new Step(blastRange(target.blockPosition()),false,null);
                if(Baritone.settings().botAutoCrystal.value&&(requested==CombatStyle.AUTO||requested==CombatStyle.CRYSTAL)){
                    EndCrystal existing=crystals().stream().filter(crystal->nearest(crystal.getBoundingBox(),ctx.playerHead()).distanceToSqr(ctx.playerHead())<=9
                            &&clearRay(ctx.playerHead(),nearest(crystal.getBoundingBox(),ctx.playerHead()),null)&&worthwhile(target,crystal.position(),6,null)).findFirst().orElse(null);
                    if(existing!=null){plan=new Plan(existing.blockPosition().below(),CombatStyle.CRYSTAL,false,List.of(),null,0);change(Stage.WAIT_CRYSTAL);return waitStep();}
                }
                if(selectDelay-->0) { Step move=relocate(target,requested);return move==null?new Step(blastRange(target.blockPosition()),false,null):move; }
                plan=reuseCrystalBase(target,requested);if(plan==null)plan=choose(target,requested);
                if(plan==null) {
                    selectDelay=2;
                    Step move=relocate(target,requested);if(move!=null)return move;
                    if(++idleTicks>=6)return problem(lastProblem,requested);
                    return new Step(blastRange(target.blockPosition()),false,null);
                }
                idleTicks=supportIndex=0;lurePoint=firingPoint=null;lureUntil=firingUntil=0;rejection="";
                change(plan.supports().isEmpty()?Stage.COVER:Stage.SUPPORT);
                return waitStep();
            }
            case SUPPORT -> {
                BlockPos support=plan.supports().get(supportIndex);
                if(ctx.world().getBlockState(support).is(Blocks.OBSIDIAN)) {
                    supportIndex++;change(supportIndex<plan.supports().size()?Stage.SUPPORT:Stage.COVER);return waitStep();
                }
                BlockHitResult hit=placementHit(support);
                if(hit==null) { if(placementJump(support))return waitStep();return problem("Support placement face changed",requested); }
                if(use(slot(Items.OBSIDIAN),hit)) { change(Stage.WAIT_SUPPORT);blockConfirmed=false; }
                return waitStep();
            }
            case WAIT_SUPPORT -> {
                if(blockConfirmed&&ctx.world().getBlockState(plan.supports().get(supportIndex)).is(Blocks.OBSIDIAN)) {
                    supportIndex++;change(supportIndex<plan.supports().size()?Stage.SUPPORT:Stage.COVER);
                }
                return waitStep();
            }
            case COVER -> {
                if(plan.cover()!=null) {
                    if(ctx.world().getBlockState(plan.cover()).is(Blocks.OBSIDIAN)) { change(Stage.PREPARE);return waitStep(); }
                    BlockHitResult hit=placementHit(plan.cover());
                    if(hit==null)return problem("No reachable cover placement face",requested);
                    if(use(slot(Items.OBSIDIAN),hit)){change(Stage.WAIT_COVER);blockConfirmed=false;}
                } else change(Stage.PREPARE);
                return waitStep();
            }
            case WAIT_COVER -> {
                if(blockConfirmed&&ctx.world().getBlockState(plan.cover()).is(Blocks.OBSIDIAN))change(Stage.PREPARE);
                return waitStep();
            }
            case PREPARE -> {
                if(plan.style()==CombatStyle.CRYSTAL) {
                    if(plan.newBase()) {
                        BlockHitResult hit=placementHit(plan.pos());
                        if(hit==null) { if(placementJump(plan.pos()))return waitStep();return problem("No reachable obsidian placement face",requested); }
                        if(use(slot(Items.OBSIDIAN),hit)){change(Stage.WAIT_BASE);blockConfirmed=false;}
                    } else change(Stage.CRYSTAL);
                } else {
                    if(ctx.world().getBlockState(plan.pos()).is(Blocks.RESPAWN_ANCHOR)) {
                        reusableAnchors.add(plan.pos());change(Stage.CHARGE);return waitStep();
                    }
                    BlockHitResult hit=placementHit(plan.pos());
                    if(hit==null) { if(placementJump(plan.pos()))return waitStep();return problem("No reachable anchor placement face",requested); }
                    if(use(slot(Items.RESPAWN_ANCHOR),hit)){change(Stage.WAIT_ANCHOR);blockConfirmed=false;}
                }
                return waitStep();
            }
            case WAIT_BASE -> {
                if(blockConfirmed&&ctx.world().getBlockState(plan.pos()).is(Blocks.OBSIDIAN))change(Stage.CRYSTAL);
                return waitStep();
            }
            case CRYSTAL -> {
                if(!base(plan.pos())||!crystalSpace(plan.pos()))return problem("Crystal base or entity clearance changed",requested);
                BlockHitResult hit=faceHit(plan.pos(),Direction.UP);
                if(hit==null) { if(placementJump(plan.pos().above()))return waitStep();return problem("Crystal base moved out of reach",requested); }
                if(use(slot(Items.END_CRYSTAL),hit))change(Stage.WAIT_CRYSTAL);
                return waitStep();
            }
            case WAIT_CRYSTAL -> {
                EndCrystal crystal=crystals().stream().filter(entity->entity.position().distanceToSqr(blastPoint(plan))<2).findFirst().orElse(null);
                if(crystal==null)return waitStep();
                Vec3 aim=nearest(crystal.getBoundingBox(),ctx.playerHead());
                if(aim.distanceToSqr(ctx.playerHead())>9||!clearRay(ctx.playerHead(),aim,null)) {
                    Step move=saferFiringPoint(target);
                    if(move==null)return problem("No normal-reach crystal hit with acceptable blast exposure",requested);
                    ticks=0;return move;
                }
                if(aim(aim)&&bot.getCombat().crystal(crystal)&&cadence.claim(ctx.player().tickCount,Baritone.settings().killExplosiveDelayTicks.value)) {
                    id=crystal.getId();change(Stage.WAIT_BREAK);
                }
                return waitStep();
            }
            case WAIT_BREAK -> {
                boolean present=crystals().stream().anyMatch(entity->entity.getId()==id);
                if(!present) { reusableCrystalBase=plan.pos();confirmedCycles++;change(Stage.SELECT);plan=null;firingPoint=null;firingUntil=0; }
                return waitStep();
            }
            case WAIT_ANCHOR -> {
                if(blockConfirmed&&ctx.world().getBlockState(plan.pos()).is(Blocks.RESPAWN_ANCHOR)) { reusableAnchors.add(plan.pos());change(Stage.CHARGE); }
                return waitStep();
            }
            case CHARGE -> {
                var state=ctx.world().getBlockState(plan.pos());
                if(!anchorExplodesAt(plan.pos())||!state.is(Blocks.RESPAWN_ANCHOR))return problem("Anchor is absent or is a respawn point in this dimension",requested);
                if(state.getValue(RespawnAnchorBlock.CHARGE)>0) { change(Stage.DETONATE);return waitStep(); }
                BlockHitResult hit=bestFace(plan.pos());
                if(hit==null) {
                    rejection="";Step move=saferFiringPoint(target);if(move!=null) { ticks=0;return move; }
                    return problem("No reachable firing position for the existing anchor",requested);
                }
                if(use(slot(Items.GLOWSTONE),hit)){change(Stage.WAIT_CHARGE);blockConfirmed=false;}
                return waitStep();
            }
            case WAIT_CHARGE -> {
                var state=ctx.world().getBlockState(plan.pos());
                if(blockConfirmed&&state.is(Blocks.RESPAWN_ANCHOR)&&state.getValue(RespawnAnchorBlock.CHARGE)>0)change(Stage.DETONATE);
                return waitStep();
            }
            case DETONATE -> {
                var state=ctx.world().getBlockState(plan.pos());
                if(!anchorExplodesAt(plan.pos())||!state.is(Blocks.RESPAWN_ANCHOR)||state.getValue(RespawnAnchorBlock.CHARGE)<1)
                    return problem("Anchor cannot detonate here",requested);
                if(ctx.player().getOffhandItem().is(Items.GLOWSTONE))return problem("Move glowstone out of the offhand before anchor combat",requested);
                BlockHitResult hit=bestFace(plan.pos());
                if(hit==null) {
                    rejection="";Step move=saferFiringPoint(target);if(move!=null) { ticks=0;return move; }
                    return problem("No reachable firing position for the charged anchor",requested);
                }
                if(use(triggerSlot(),hit)){change(Stage.WAIT_DETONATE);blockConfirmed=false;}
                return waitStep();
            }
            case WAIT_DETONATE -> {
                if(blockConfirmed&&!ctx.world().getBlockState(plan.pos()).is(Blocks.RESPAWN_ANCHOR)) { confirmedCycles++;change(Stage.SELECT);plan=null;firingPoint=null;firingUntil=0; }
                return waitStep();
            }
        }
        return waitStep();
    }

    private Plan choose(Player target,CombatStyle requested) {
        List<CombatStyle> styles=requested==CombatStyle.AUTO?List.of(CombatStyle.CRYSTAL,CombatStyle.ANCHOR):List.of(requested);
        Plan best=null;Map<String,Integer> reasons=new LinkedHashMap<>();lowestSelfDamage=Double.POSITIVE_INFINITY;
        for(CombatStyle style:styles) {
            if(rejected.contains(style))continue;
            if(!supplies(style)) { reasons.merge("missing hotbar supplies or invalid anchor dimension",1,Integer::sum);continue; }
            BlockPos center=target.blockPosition();
            for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)for(int dy=-3;dy<=2;dy++) {
                BlockPos pos=center.offset(dx,dy,dz);
                if(!ctx.world().hasChunkAt(pos)||pos.distSqr(ctx.playerFeet())>36)continue;
                boolean existing=style==CombatStyle.CRYSTAL?base(pos):ctx.world().getBlockState(pos).is(Blocks.RESPAWN_ANCHOR);
                if(!existing&&(!replaceable(pos)||!emptyBody(pos)))continue;
                if(style==CombatStyle.CRYSTAL&&!crystalSpace(pos))continue;
                if(style==CombatStyle.CRYSTAL&&!CombatTerrainPolicy.crystalTopReachable(pos.getY(),ctx.playerHead().y))continue;
                if(style==CombatStyle.ANCHOR&&!anchorExplodesAt(pos))continue;
                if(style==CombatStyle.ANCHOR&&(!existing&&slot(Items.RESPAWN_ANCHOR)<0
                        ||slot(Items.GLOWSTONE)<0&&(!existing||ctx.world().getBlockState(pos).getValue(RespawnAnchorBlock.CHARGE)==0)))continue;
                if(style==CombatStyle.CRYSTAL&&!existing&&slot(Items.OBSIDIAN)<0)continue;
                List<BlockPos> support=existing?List.of():supports(pos,style,ctx.player().position());
                if(support==null||(existing&&(style==CombatStyle.CRYSTAL
                        ?faceHit(pos,Direction.UP)==null&&faceHitFrom(pos,Direction.UP,ctx.playerHead().add(0,1.2,0))==null:bestFace(pos)==null))) {
                    reasons.merge("no reachable block face or short support chain",1,Integer::sum);continue;
                }
                double reach=Math.min(4.5,ctx.playerController().getBlockReachDistance());
                if(nearest(new AABB(pos),ctx.playerHead()).distanceToSqr(ctx.playerHead())>reach*reach)continue;
                int needed=support.size()+(style==CombatStyle.CRYSTAL&&!existing?1:0);
                if(needed>obsidianCount()) { reasons.merge("more obsidian is needed for supports and the base",1,Integer::sum);continue; }
                double power=style==CombatStyle.CRYSTAL?6:5;
                Vec3 blast=style==CombatStyle.CRYSTAL?new Vec3(pos.getX()+0.5,pos.getY()+1,pos.getZ()+0.5):Vec3.atCenterOf(pos);
                List<BlockPos> forecast=new ArrayList<>(support);
                if(style==CombatStyle.CRYSTAL&&!existing)forecast.add(pos);
                BlockPos removed=style==CombatStyle.ANCHOR?pos:null,shield=null;
                if(!worthwhileAt(target,ctx.player().position(),target.position(),blast,power,removed,forecast)) {
                    String reason=rejection;
                    if(obsidianCount()>needed&&Baritone.settings().killUseBlastCover.value)shield=findCover(target,blast,power,removed,forecast,pos);
                    if(shield==null) { reasons.merge(reason.startsWith(SELF_DAMAGE)?SELF_DAMAGE:reason,1,Integer::sum);continue; }
                    forecast.add(shield);
                }
                double score=damageAt(target,target.position(),blast,power,removed,forecast)
                        -damageAt(ctx.player(),ctx.player().position(),blast,power,removed,forecast)*1.1-needed*0.5-(shield==null?0:0.5)+(existing?1.5:0);
                if(best==null||score>best.score())best=new Plan(pos.immutable(),style,!existing,List.copyOf(support),shield,score);
            }
        }
        String top=reasons.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("no open placement beside the target at a reachable height");
        if(top.equals(SELF_DAMAGE)&&Double.isFinite(lowestSelfDamage))
            top=String.format(Locale.ROOT,"estimated self damage at least %.1f exceeds killMaximumSelfDamage %d",lowestSelfDamage,Baritone.settings().killMaximumSelfDamage.value);
        lastProblem="No usable blast spot: "+top;
        return best;
    }

    private Plan reuseCrystalBase(Player target,CombatStyle requested){
        if(reusableCrystalBase==null||requested==CombatStyle.ANCHOR||requested==CombatStyle.MELEE||rejected.contains(CombatStyle.CRYSTAL)
                ||slot(Items.END_CRYSTAL)<0||!ctx.world().hasChunkAt(reusableCrystalBase)||reusableCrystalBase.distSqr(ctx.playerFeet())>36
                ||!base(reusableCrystalBase)||!crystalSpace(reusableCrystalBase)||faceHit(reusableCrystalBase,Direction.UP)==null)return null;
        Vec3 blast=new Vec3(reusableCrystalBase.getX()+.5,reusableCrystalBase.getY()+1,reusableCrystalBase.getZ()+.5);
        return worthwhile(target,blast,6,null)?new Plan(reusableCrystalBase,CombatStyle.CRYSTAL,false,List.of(),null,0):null;
    }
    /** Holds a 3-4.5 block band around the target: close enough to place, far enough that the blast is survivable. */
    private static Goal blastRange(BlockPos center) {
        return new Goal() {
            private double outside(int x,int y,int z) {
                double d=Math.hypot(x-center.getX(),z-center.getZ());return Math.max(0,3-d)+Math.max(0,d-4.5)+Math.max(0,Math.abs(y-center.getY())-2);
            }
            @Override public boolean isInGoal(int x,int y,int z) { return outside(x,y,z)==0; }
            @Override public double heuristic(int x,int y,int z) { return outside(x,y,z)*Baritone.settings().costHeuristic.value; }
            @Override public String toString() { return "Blast range of "+center.toShortString(); }
        };
    }
    private BlockPos findCover(Player target,Vec3 blast,double power,BlockPos removedBlock,List<BlockPos> forecast,BlockPos base) {
        BlockPos feet=ctx.playerFeet();
        for(Direction direction:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)) {
            BlockPos cover=feet.relative(direction);
            if(cover.equals(base)||forecast.contains(cover)||cover.equals(base.above())||cover.equals(base.above(2))||placementHit(cover)==null)continue;
            List<BlockPos> blocks=new ArrayList<>(forecast);blocks.add(cover);
            if(worthwhileAt(target,ctx.player().position(),target.position(),blast,power,removedBlock,blocks))return cover;
        }return null;
    }
    private Vec3 blastPoint(Plan plan) { return plan.style()==CombatStyle.CRYSTAL?new Vec3(plan.pos().getX()+0.5,plan.pos().getY()+1,plan.pos().getZ()+0.5):Vec3.atCenterOf(plan.pos()); }
    private boolean worthwhile(Player target,Vec3 blast,double power,BlockPos removedBlock) { return worthwhile(target,blast,power,removedBlock,null); }
    private boolean worthwhile(Player target,Vec3 blast,double power,BlockPos removedBlock,BlockPos addedCover) {
        return worthwhileAt(target,ctx.player().position(),target.position(),blast,power,removedBlock,addedCover==null?List.of():List.of(addedCover));
    }
    private boolean worthwhileAt(Player target,Vec3 selfFeet,Vec3 targetFeet,Vec3 blast,double power,BlockPos removedBlock,List<BlockPos> addedBlocks) {
        if(bot.getNetwork().isTeammate(target.getUUID())||!bot.getNetwork().team().ready()||bot.getNetwork().team().blastSupport(target.getUUID())){rejection="waiting for the team's explosive attacker";return false;}
        boolean bystanders=ctx.world().players().stream().anyMatch(player->player!=ctx.player()&&!player.getUUID().equals(target.getUUID())
                &&player.isAlive()&&!player.isSpectator()&&player.position().distanceToSqr(blast)<power*power*4);
        if(bystanders) { rejection="another player is within blast range";return false; }
        boolean tnt=ctx.entitiesStream().anyMatch(entity-> {
            String type=BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
            return (type.equals("tnt")||type.equals("tnt_minecart"))&&entity.position().distanceToSqr(blast)<power*power*4;
        });
        if(tnt) { rejection="TNT is within blast range";return false; }
        boolean protectedArea=MiningWaypoints.protection(ctx).stream().anyMatch(area->
                Math.abs(blast.x-area.x())<=area.radius()+power*2&&Math.abs(blast.y-area.y())<=area.radius()+power*2
                        &&Math.abs(blast.z-area.z())<=area.radius()+power*2);
        if(protectedArea||bot.getSculkProcess().snapshot().contains((int)Math.floor(blast.x),(int)Math.floor(blast.y),(int)Math.floor(blast.z))) {
            rejection="a protected area or sculk is within blast range";return false;
        }
        // Other crystals in range detonate with this blast; their damage is added rather than refusing every contested spot.
        double chainSelf=0,chainTarget=0;
        if(ctx.world().getGameTime()!=chainTick) { chainDamage.clear();chainTick=ctx.world().getGameTime(); }
        for(EndCrystal crystal:crystals()) {
            if(crystal.position().distanceToSqr(blast)>=power*power*4||removedBlock==null&&crystal.position().distanceToSqr(blast)<0.25)continue;
            boolean actual=addedBlocks.isEmpty()&&selfFeet.equals(ctx.player().position())&&targetFeet.equals(target.position());
            double[] chained=actual?chainDamage.computeIfAbsent(crystal.getId(),key->new double[]{damage(ctx.player(),crystal.position(),6,null,null),damage(target,crystal.position(),6,null,null)}):
                    new double[]{damageAt(ctx.player(),selfFeet,crystal.position(),6,null,addedBlocks),damageAt(target,targetFeet,crystal.position(),6,null,addedBlocks)};
            chainSelf+=chained[0];chainTarget+=chained[1];
        }
        double self=damageAt(ctx.player(),selfFeet,blast,power,removedBlock,addedBlocks)+chainSelf;
        double opponent=damageAt(target,targetFeet,blast,power,removedBlock,addedBlocks)+chainTarget;
        int maximumSelf=Baritone.settings().killMaximumSelfDamage.value,minimumTarget=Baritone.settings().killMinimumBlastDamage.value,reserve=Baritone.settings().killBlastHealthReserve.value;
        float targetHealth=target.getHealth()+target.getAbsorptionAmount();
        if(BlastPolicy.mayBlast(self,opponent,ctx.player().getHealth(),ctx.player().getAbsorptionAmount(),targetHealth,maximumSelf,minimumTarget,reserve,false,false)) { rejection="";return true; }
        if(self>StealthPolicy.bounded(maximumSelf,1,20))lowestSelfDamage=Math.min(lowestSelfDamage,self);
        rejection=self>StealthPolicy.bounded(maximumSelf,1,20)?String.format(Locale.ROOT,"self damage %.1f exceeds limit %d (target %.1f)",self,maximumSelf,opponent)
                :ctx.player().getHealth()+Math.max(0,ctx.player().getAbsorptionAmount())-self-BlastPolicy.damageMargin(self)<StealthPolicy.bounded(reserve,2,16)?"the blast would leave the bot under killBlastHealthReserve"
                :opponent<StealthPolicy.bounded(minimumTarget,1,20)&&opponent<targetHealth?"too little damage reaches the target (cover or armor)"
                :"the target would take much less damage than the bot";
        return false;
    }
    private static final String SELF_DAMAGE="self damage";
    private double damage(Player entity,Vec3 blast,double power,BlockPos removedBlock) { return damage(entity,blast,power,removedBlock,null); }
    private double damage(Player entity,Vec3 blast,double power,BlockPos removedBlock,BlockPos addedCover) {
        return damageAt(entity,entity.position(),blast,power,removedBlock,addedCover==null?List.of():List.of(addedCover));
    }
    private double damageAt(Player entity,Vec3 feet,Vec3 blast,double power,BlockPos removedBlock,List<BlockPos> addedBlocks) {
        AABB box=entity.getBoundingBox().move(feet.subtract(entity.position()));
        double exposure=removedBlock==null&&addedBlocks.isEmpty()&&feet.equals(entity.position())?ServerExplosion.getSeenPercent(blast,entity):modifiedExposure(blast,box,removedBlock,addedBlocks);
        double raw=BlastPolicy.difficultyScaled(BlastPolicy.rawDamage(power,feet.distanceTo(blast),exposure),ctx.world().getDifficulty().getId());
        int protection=0;
        for(EquipmentSlot slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET)) {
            var enchantments=entity.getItemBySlot(slot).getEnchantments();
            for(var enchantment:enchantments.keySet()) {
                if(enchantment.is(Enchantments.PROTECTION))protection+=enchantments.getLevel(enchantment);
                if(enchantment.is(Enchantments.BLAST_PROTECTION))protection+=enchantments.getLevel(enchantment)*2;
            }
        }
        int resistance=entity.getActiveEffects().stream()
                .filter(effect->BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).getPath().equals("resistance"))
                .mapToInt(effect->effect.getAmplifier()).max().orElse(-1);
        return BlastPolicy.afterResistance(BlastPolicy.afterArmor(raw,entity.getArmorValue(),entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS),protection),resistance);
    }
    private double modifiedExposure(Vec3 blast,AABB box,BlockPos removed,List<BlockPos> addedBlocks) {
        double sx=1/((box.maxX-box.minX)*2+1),sy=1/((box.maxY-box.minY)*2+1),sz=1/((box.maxZ-box.minZ)*2+1);
        double bx=(1-Math.floor(1/sx)*sx)/2,bz=(1-Math.floor(1/sz)*sz)/2;int visible=0,total=0;
        for(double x=0;x<=1;x+=sx)for(double y=0;y<=1;y+=sy)for(double z=0;z<=1;z+=sz) {
            Vec3 point=new Vec3(box.minX+(box.maxX-box.minX)*x+bx,box.minY+(box.maxY-box.minY)*y,box.minZ+(box.maxZ-box.minZ)*z+bz);
            boolean covered=addedBlocks.stream().anyMatch(added->BlastPolicy.blockedByCube(added.getX(),added.getY(),added.getZ(),point.x,point.y,point.z,blast.x,blast.y,blast.z));
            if(!covered&&clearRay(point,blast,removed))visible++;total++;
        }return total==0?0:visible/(double)total;
    }
    private boolean clearRay(Vec3 from,Vec3 to,BlockPos removed) {
        var result=ctx.world().clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,ctx.player()));
        return result.getType()==HitResult.Type.MISS||removed!=null&&result.getBlockPos().equals(removed);
    }
    private boolean base(BlockPos pos) { var state=ctx.world().getBlockState(pos);return state.is(Blocks.OBSIDIAN)||state.is(Blocks.BEDROCK); }
    private boolean emptyBody(BlockPos pos) {
        AABB box=new AABB(pos.getX(),pos.getY(),pos.getZ(),pos.getX()+1,pos.getY()+1,pos.getZ()+1);
        return ctx.entitiesStream().noneMatch(entity->entity.isAlive()&&entity.getBoundingBox().intersects(box));
    }
    private boolean crystalSpace(BlockPos base) {
        if(!ctx.world().getBlockState(base.above()).isAir()||!ctx.world().getBlockState(base.above(2)).isAir())return false;
        AABB box=new AABB(base.getX(),base.getY()+1,base.getZ(),base.getX()+1,base.getY()+3,base.getZ()+1);
        return ctx.entitiesStream().noneMatch(entity->entity.isAlive()&&entity.getBoundingBox().intersects(box));
    }
    private List<EndCrystal> crystals() {
        return ctx.entitiesStream().filter(entity->entity instanceof EndCrystal&&entity.isAlive()).map(entity->(EndCrystal)entity).toList();
    }
    private boolean replaceable(BlockPos pos) {
        if(!ctx.world().hasChunkAt(pos))return false;
        var state=ctx.world().getBlockState(pos);
        return state.canBeReplaced()&&state.getFluidState().isEmpty()&&ctx.world().getBlockEntity(pos)==null;
    }
    private BlockHitResult placementHit(BlockPos space) {
        return replaceable(space)&&emptyBody(space)?placementFaceFrom(space,ctx.playerHead()):null;
    }
    private boolean placementJump(BlockPos space) {
        if(ticks>20||space.getY()+0.5<=ctx.playerHead().y||space.getY()-ctx.playerFeet().getY()>3
                ||Math.hypot(space.getX()+0.5-ctx.player().getX(),space.getZ()+0.5-ctx.player().getZ())>3
                ||!MovementHelper.canWalkThrough(ctx,ctx.playerFeet().above(2)))return false;
        if(ctx.player().onGround())bot.getInputOverrideHandler().setInputForceState(baritone.api.utils.input.Input.JUMP,true);
        return true;
    }
    private BlockHitResult placementFaceFrom(BlockPos space,Vec3 eye) {
        for(Direction direction:Direction.values()) {
            BlockPos neighbor=space.relative(direction);var state=ctx.world().getBlockState(neighbor);
            if(state.isAir()||!state.getFluidState().isEmpty()||ctx.world().getBlockEntity(neighbor)!=null||interactive(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()))continue;
            BlockHitResult hit=faceHitFrom(neighbor,direction.getOpposite(),eye);if(hit!=null)return hit;
        }return null;
    }
    private List<BlockPos> supports(BlockPos destination,CombatStyle style,Vec3 feet) {
        int maximum=slot(Items.OBSIDIAN)<0?0:StealthPolicy.bounded(Baritone.settings().killMaxSupportBlocks.value,0,2);
        Vec3 eye=feet.add(0,1.62,0);AABB botBox=ctx.player().getBoundingBox().move(feet.subtract(ctx.player().position()));
        return CombatTerrainPolicy.supports(destination,maximum,
                pos->List.of(pos.below(),pos.north(),pos.east(),pos.south(),pos.west(),pos.above()),
                pos->replaceable(pos)&&!(style==CombatStyle.CRYSTAL&&(pos.equals(destination.above())||pos.equals(destination.above(2))))
                        &&!botBox.intersects(new AABB(pos))&&emptyBodyExceptBot(pos)
                        &&pos.distSqr(BlockPos.containing(feet))<=36&&!bot.getSculkProcess().snapshot().contains(pos.getX(),pos.getY(),pos.getZ()),
                pos->replaceable(pos)&&placementFaceFrom(pos,eye)!=null);
    }
    private boolean emptyBodyExceptBot(BlockPos pos) {
        AABB box=new AABB(pos);
        return ctx.entitiesStream().noneMatch(entity->entity!=ctx.player()&&entity.isAlive()&&entity.getBoundingBox().intersects(box));
    }
    private int obsidianCount() {
        int count=0;for(int i=0;i<9;i++)if(ctx.player().getInventory().getItem(i).is(Items.OBSIDIAN))count+=ctx.player().getInventory().getItem(i).getCount();return count;
    }
    private static boolean interactive(String path) {
        return INTERACTIVE.contains(path)||ConstructionPolicy.isContainer(path)||path.endsWith("_door")||path.endsWith("_trapdoor")||path.endsWith("_fence_gate")
                ||path.endsWith("_button")||path.endsWith("_bed")||path.endsWith("_candle")||path.endsWith("candle_cake")||path.startsWith("potted_");
    }
    private BlockHitResult faceHit(BlockPos pos,Direction face) { return faceHitFrom(pos,face,ctx.playerHead()); }
    private BlockHitResult faceHitFrom(BlockPos pos,Direction face,Vec3 eye) {
        double reach=Math.min(4.5,ctx.playerController().getBlockReachDistance());
        // Slabs, stairs, walls and ledges have real collision faces away from a full cube's center.
        for(AABB box:ctx.world().getBlockState(pos).getCollisionShape(ctx.world(),pos).toAabbs()) {
            for(double[] sample:new double[][]{{0.5,0.5},{0.25,0.25},{0.75,0.25},{0.25,0.75},{0.75,0.75}}) {
                double x=box.minX+(box.maxX-box.minX)*sample[0],y=box.minY+(box.maxY-box.minY)*sample[1],z=box.minZ+(box.maxZ-box.minZ)*sample[1];
                switch(face) {
                    case DOWN -> y=box.minY+0.001;
                    case UP -> y=box.maxY-0.001;
                    case WEST -> x=box.minX+0.001;
                    case EAST -> x=box.maxX-0.001;
                    case NORTH -> { z=box.minZ+0.001;y=box.minY+(box.maxY-box.minY)*sample[1]; }
                    case SOUTH -> { z=box.maxZ-0.001;y=box.minY+(box.maxY-box.minY)*sample[1]; }
                }
                if(face==Direction.WEST||face==Direction.EAST)z=box.minZ+(box.maxZ-box.minZ)*sample[0];
                Vec3 point=new Vec3(pos.getX()+x,pos.getY()+y,pos.getZ()+z);
                if(eye.distanceToSqr(point)>reach*reach)continue;
                var ray=ctx.world().clip(new ClipContext(eye,point,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,ctx.player()));
                if(ray.getType()==HitResult.Type.BLOCK&&ray.getBlockPos().equals(pos)&&ray.getDirection()==face)
                    return new BlockHitResult(ray.getLocation(),face,pos,false);
            }
        }return null;
    }
    private BlockHitResult bestFace(BlockPos pos) { return bestFaceFrom(pos,ctx.playerHead()); }
    private BlockHitResult bestFaceFrom(BlockPos pos,Vec3 eye) {
        for(Direction face:Direction.values()) { var hit=faceHitFrom(pos,face,eye);if(hit!=null)return hit; }return null;
    }
    private static Vec3 nearest(AABB box,Vec3 eye) {
        return new Vec3(Math.max(box.minX+0.001,Math.min(box.maxX-0.001,eye.x)),Math.max(box.minY+0.001,Math.min(box.maxY-0.001,eye.y)),Math.max(box.minZ+0.001,Math.min(box.maxZ-0.001,eye.z)));
    }
    public boolean positioning() { return lurePoint!=null||firingPoint!=null; }
    private boolean walkable(BlockPos pos,CalculationContext context) {
        return walkable(pos,context,null);
    }
    private boolean walkable(BlockPos pos,CalculationContext context,Entity ignored) {
        return ctx.world().hasChunkAt(pos)&&MovementHelper.canWalkOn(context,pos.getX(),pos.getY()-1,pos.getZ())
                &&MovementHelper.canWalkThrough(context,pos.getX(),pos.getY(),pos.getZ())&&MovementHelper.canWalkThrough(context,pos.getX(),pos.getY()+1,pos.getZ())
                &&ctx.world().getBlockState(pos).getFluidState().isEmpty()&&ctx.world().getBlockState(pos.above()).getFluidState().isEmpty()
                &&!ctx.world().getBlockState(pos.below()).is(Blocks.MAGMA_BLOCK)
                &&!bot.getSculkProcess().snapshot().contains(pos.getX(),pos.getY(),pos.getZ())
                &&ctx.entitiesStream().noneMatch(entity->entity!=ctx.player()&&entity!=ignored&&entity.isAlive()
                        &&entity.getBoundingBox().intersects(new AABB(pos.getX()+0.2,pos.getY(),pos.getZ()+0.2,pos.getX()+0.8,pos.getY()+1.8,pos.getZ()+0.8)));
    }
    private Step saferFiringPoint(Player target) {
        if(rejection.contains("another player")||rejection.contains("TNT")||rejection.contains("protected area"))return null;
        long now=System.currentTimeMillis();
        if(firingUntil!=0&&now>=firingUntil) { routeFailed();return null; }
        if(firingPoint!=null&&!new GoalNear(firingPoint,0).isInGoal(ctx.playerFeet()))return new Step(new GoalNear(firingPoint,0),false,null);
        EndCrystal crystal=plan.style()==CombatStyle.CRYSTAL?crystals().stream().filter(entity->entity.position().distanceToSqr(blastPoint(plan))<2).findFirst().orElse(null):null;
        if(plan.style()==CombatStyle.CRYSTAL&&crystal==null)return null;
        firingPoint=firingPosition(target,crystal);
        if(firingPoint==null)return null;
        if(firingUntil==0)firingUntil=now+StealthPolicy.bounded(Baritone.settings().killLureTimeoutMS.value,2000L,15000L);
        return new Step(new GoalNear(firingPoint,0),false,null);
    }
    private BlockPos firingPosition(Player target,EndCrystal crystal) {
        if(plan==null)return null;
        CalculationContext context=new CalculationContext(bot);BlockPos best=null;double bestScore=Double.NEGATIVE_INFINITY;
        Vec3 blast=blastPoint(plan);BlockPos center=BlockPos.containing(blast);
        for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++)for(int dy=-2;dy<=2;dy++) {
            BlockPos point=center.offset(dx,dy,dz);
            if(point.equals(ctx.playerFeet())||failedFootings.getOrDefault(point,0L)>System.currentTimeMillis()||!walkable(point,context))continue;
            Vec3 feet=new Vec3(point.getX()+0.5,point.getY(),point.getZ()+0.5),eye=feet.add(0,1.62,0);
            if(crystal!=null) {
                Vec3 hit=nearest(crystal.getBoundingBox(),eye);
                if(hit.distanceToSqr(eye)>9||!clearRay(eye,hit,null))continue;
            } else if(bestFaceFrom(plan.pos(),eye)==null)continue;
            BlockPos removed=plan.style()==CombatStyle.ANCHOR?plan.pos():null;
            double power=plan.style()==CombatStyle.CRYSTAL?6:5;
            if(!worthwhileAt(target,feet,target.position(),blast,power,removed,List.of()))continue;
            double score=-damageAt(ctx.player(),feet,blast,power,removed,List.of())-ctx.playerFeet().distSqr(point)*0.15;
            if(score>bestScore) { bestScore=score;best=point; }
        }return best;
    }
    /** A bounded lure is movement toward real terrain, never an assumed movement or teleport of the opponent. */
    public Step relocate(Player target,CombatStyle requested) {
        if(!Baritone.settings().killLureOpponent.value||!supplies(requested))return null;
        long now=System.currentTimeMillis();failedFootings.entrySet().removeIf(entry->entry.getValue()<now);
        if(lurePoint!=null) {
            if(now<lureUntil&&target.distanceToSqr(ctx.player())<=324) {
                bot.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(),target.getEyePosition(),ctx.playerRotations()),true);
                return new Step(new GoalNear(lurePoint,0),false,null);
            }
            failedFootings.put(lurePoint,now+30000);lurePoint=null;lureUntil=0;nextLureCheck=now+2000;return null;
        }
        if(now<nextLureCheck)return null;
        nextLureCheck=now+2000;lurePoint=findLurePosition(target,requested);
        if(lurePoint==null)return null;
        rejected.clear();
        lureUntil=now+StealthPolicy.bounded(Baritone.settings().killLureTimeoutMS.value,2000L,15000L);
        warning="Repositioning toward a better blast spot; watching for the opponent to follow";
        return new Step(new GoalNear(lurePoint,0),false,null);
    }
    private record Footing(BlockPos pos,double score) {}
    private BlockPos findLurePosition(Player target,CombatStyle requested) {
        CalculationContext context=new CalculationContext(bot);List<Footing> candidates=new ArrayList<>();BlockPos origin=ctx.playerFeet();
        for(int dx=-6;dx<=6;dx+=2)for(int dz=-6;dz<=6;dz+=2)for(int dy=-2;dy<=2;dy++) {
            BlockPos point=origin.offset(dx,dy,dz);Vec3 feet=new Vec3(point.getX()+0.5,point.getY(),point.getZ()+0.5);
            if(!CombatTerrainPolicy.lureRange(origin.distSqr(point),feet.distanceToSqr(target.position()))||failedFootings.containsKey(point)||!walkable(point,context)
                    ||!clearRay(feet.add(0,1.62,0),target.getEyePosition(),null))continue;
            double score=origin.distSqr(point)*0.15+(point.getY()-target.getY())*3;
            candidates.add(new Footing(point,score));
        }
        candidates.sort(Comparator.comparingDouble(Footing::score));
        List<CombatStyle> styles=requested==CombatStyle.AUTO?List.of(CombatStyle.CRYSTAL,CombatStyle.ANCHOR):List.of(requested);
        BlockPos best=null;double bestScore=Double.NEGATIVE_INFINITY;
        for(Footing footing:candidates.stream().limit(12).toList()) {
            BlockPos point=footing.pos();Vec3 feet=new Vec3(point.getX()+0.5,point.getY(),point.getZ()+0.5);
            double distance=Math.hypot(target.getX()-feet.x,target.getZ()-feet.z),nx=(target.getX()-feet.x)/distance,nz=(target.getZ()-feet.z)/distance;
            for(int ahead=2;ahead<=3;ahead++)for(int dy=-1;dy<=2;dy++)for(CombatStyle style:styles) {
                if(!supplies(style))continue;
                BlockPos base=BlockPos.containing(feet.x+nx*ahead,feet.y+dy,feet.z+nz*ahead);
                boolean existing=style==CombatStyle.CRYSTAL&&base(base);
                if(style==CombatStyle.CRYSTAL&&!CombatTerrainPolicy.crystalTopReachable(base.getY(),feet.y+1.62))continue;
                if(style==CombatStyle.CRYSTAL&&(!crystalSpace(base)||!existing&&slot(Items.OBSIDIAN)<0)
                        ||style==CombatStyle.ANCHOR&&!anchorExplodesAt(base))continue;
                if(!existing&&(!replaceable(base)||!emptyBodyExceptBot(base)))continue;
                List<BlockPos> support=existing?List.of():supports(base,style,feet);if(support==null)continue;
                int needed=support.size()+(style==CombatStyle.CRYSTAL&&!existing?1:0);if(needed>obsidianCount())continue;
                List<BlockPos> forecast=new ArrayList<>(support);if(style==CombatStyle.CRYSTAL&&!existing)forecast.add(base);
                Vec3 opponent=null;
                for(int height=-1;height<=2;height++) {
                    BlockPos approach=BlockPos.containing(base.getX()+0.5+nx*1.4,feet.y+height,base.getZ()+0.5+nz*1.4);
                    if(walkable(approach,context,target)&&!forecast.contains(approach)&&!forecast.contains(approach.above())) {
                        opponent=new Vec3(approach.getX()+0.5,approach.getY(),approach.getZ()+0.5);break;
                    }
                }
                if(opponent==null)continue;
                Vec3 blast=style==CombatStyle.CRYSTAL?new Vec3(base.getX()+0.5,base.getY()+1,base.getZ()+0.5):Vec3.atCenterOf(base);
                BlockPos removed=style==CombatStyle.ANCHOR?base:null;double power=style==CombatStyle.CRYSTAL?6:5;
                if(!worthwhileAt(target,feet,opponent,blast,power,removed,forecast)) {
                    if(obsidianCount()<=needed||!Baritone.settings().killUseBlastCover.value)continue;
                    boolean covered=false;
                    for(Direction direction:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)) {
                        BlockPos shield=point.relative(direction);
                        if(shield.equals(base)||shield.equals(base.above())||shield.equals(base.above(2))||forecast.contains(shield)
                                ||!replaceable(shield)||!emptyBodyExceptBot(shield)||placementFaceFrom(shield,feet.add(0,1.62,0))==null)continue;
                        forecast.add(shield);
                        if(worthwhileAt(target,feet,opponent,blast,power,removed,forecast)) { covered=true;break; }
                        forecast.remove(forecast.size()-1);
                    }
                    if(!covered)continue;
                }
                double score=damageAt(target,opponent,blast,power,removed,forecast)-damageAt(ctx.player(),feet,blast,power,removed,forecast)*1.1-footing.score()+(existing?2:0)-needed*0.5;
                if(score>bestScore) { bestScore=score;best=point; }
            }
        }return best;
    }
    private boolean aim(Vec3 point) {
        var rotation=RotationUtils.calcRotationFromVec3d(ctx.playerHead(),point,ctx.playerRotations());bot.getLookBehavior().updateTarget(rotation,true);
        // Look randomization keeps the sent rotation within about a degree of the request; the hit position itself is supplied explicitly.
        Rotation off=ctx.playerRotations().subtract(rotation).normalize();
        return Math.abs(off.getYaw())<3&&Math.abs(off.getPitch())<3;
    }
    private boolean use(int selected,BlockHitResult hit) {
        if(selected<0||ctx.player().isShiftKeyDown()||!aim(hit.getLocation())||!cadence.claim(ctx.player().tickCount,Baritone.settings().killExplosiveDelayTicks.value))return false;
        ctx.player().getInventory().setSelectedSlot(selected);ctx.playerController().syncHeldItem();
        ctx.playerController().processRightClickBlock(ctx.player(),ctx.world(),InteractionHand.MAIN_HAND,hit);
        ctx.player().swing(InteractionHand.MAIN_HAND,SwingAnimation.DEFAULT,false);return true;
    }
    private void change(Stage next) { stage=next;ticks=0; }
    private Step waitStep() { return new Step(null,false,null); }
    private Step problem(String reason,CombatStyle requested) {
        warning=remaining();
        if(plan!=null)rejected.add(plan.style());else rejected.addAll(List.of(CombatStyle.CRYSTAL,CombatStyle.ANCHOR));
        lastProblem=reason;stage=Stage.SELECT;plan=null;ticks=idleTicks=supportIndex=0;firingPoint=null;firingUntil=0;
        if(requested==CombatStyle.AUTO) {
            boolean remaining=List.of(CombatStyle.CRYSTAL,CombatStyle.ANCHOR).stream().anyMatch(style->!rejected.contains(style)&&supplies(style));
            return new Step(null,!remaining,null);
        }
        return new Step(null,false,reason);
    }
}
