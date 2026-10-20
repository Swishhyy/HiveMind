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
import baritone.api.utils.RotationUtils;
import baritone.utils.mining.ConstructionPolicy;
import baritone.utils.mining.MiningRouteAccess;
import baritone.utils.mining.MiningWaypoints;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
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
    private enum Stage { SELECT,PREPARE,WAIT_COVER,WAIT_BASE,CRYSTAL,WAIT_CRYSTAL,WAIT_BREAK,WAIT_ANCHOR,CHARGE,WAIT_CHARGE,DETONATE,WAIT_DETONATE }
    private record Plan(BlockPos pos,CombatStyle style,boolean newBase,BlockPos cover,double score) {}
    private final Baritone bot;
    private final IPlayerContext ctx;
    private Stage stage=Stage.SELECT;
    private Plan plan;
    private int ticks,delay,id=-1,idleTicks,confirmedCycles;
    private boolean blockConfirmed;
    private String lastProblem="No suitable blast position",warning;
    private final Set<CombatStyle> rejected=EnumSet.noneOf(CombatStyle.class);

    public ExplosiveCombat(Baritone bot) { this.bot=bot;ctx=bot.getPlayerContext(); }
    public int confirmedCycles() { return confirmedCycles; }
    public boolean inProgress() { return plan!=null&&stage!=Stage.SELECT; }
    public String takeWarning() { String message=warning;warning=null;return message; }
    public String remaining() {
        if(plan==null||ctx.world()==null)return warning;
        boolean blocks=ctx.world().getBlockState(plan.pos()).is(Blocks.RESPAWN_ANCHOR)||plan.newBase()&&ctx.world().getBlockState(plan.pos()).is(Blocks.OBSIDIAN);
        boolean cover=plan.cover()!=null&&ctx.world().getBlockState(plan.cover()).is(Blocks.OBSIDIAN);
        boolean crystal=crystals().stream().anyMatch(entity->entity.position().distanceToSqr(blastPoint(plan))<2);
        return blocks||cover||crystal?"Combat blocks/explosive still visible near "+plan.pos().getX()+" "+plan.pos().getY()+" "+plan.pos().getZ()+"; no cleanup was attempted":null;
    }
    /** Whether a crystal/anchor plan is eligible right now; lets a melee fallback switch back to explosives. */
    public boolean available(Player target,CombatStyle requested) { return !inProgress()&&choose(target,requested)!=null; }
    public boolean expectedCrystal(Entity entity) { return stage==Stage.WAIT_BREAK&&entity instanceof EndCrystal&&entity.getId()==id; }
    public void reset() { stage=Stage.SELECT;plan=null;ticks=delay=idleTicks=confirmedCycles=0;id=-1;blockConfirmed=false;warning=null;rejected.clear(); }
    public void onBlockChange(BlockChangeEvent event) {
        if(plan!=null)for(var change:event.getBlocks())if(change.first().equals(stage==Stage.WAIT_COVER?plan.cover():plan.pos()))blockConfirmed=true;
    }
    public boolean supplies(CombatStyle requested) {
        return switch(requested) {
            case MELEE -> false;
            case CRYSTAL -> slot(Items.END_CRYSTAL)>=0;
            case ANCHOR -> anchorAvailable();
            case AUTO -> slot(Items.END_CRYSTAL)>=0||anchorAvailable();
        };
    }
    private boolean anchorAvailable() {
        return BlastPolicy.anchorDimension(MiningRouteAccess.dimension(ctx))&&slot(Items.RESPAWN_ANCHOR)>=0&&slot(Items.GLOWSTONE)>=0
                &&!ctx.player().getOffhandItem().is(Items.GLOWSTONE)&&triggerSlot()>=0;
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
        if(delay-->0)return waitStep();ticks++;
        if(ctx.player().containerMenu!=ctx.player().inventoryMenu||!ctx.player().inventoryMenu.getCarried().isEmpty())
            return problem("Close inventory menus before explosive combat",requested);
        if(plan!=null&&stage!=Stage.SELECT&&!worthwhile(target,blastPoint(plan),plan.style()==CombatStyle.CRYSTAL?6:5,plan.style()==CombatStyle.ANCHOR?plan.pos():null,
                stage==Stage.WAIT_COVER?plan.cover():plan.style()==CombatStyle.CRYSTAL&&plan.newBase()&&(stage==Stage.PREPARE||stage==Stage.WAIT_BASE)?plan.pos():null)) {
            // A placed/charged explosive may be left in place when detonating it is no longer suitable.
            return problem("Blast geometry or bystander/health conditions changed",requested);
        }
        if(ticks>80&&stage!=Stage.SELECT)return problem("Server did not confirm the explosive action",requested);
        switch(stage) {
            case SELECT -> {
                plan=choose(target,requested);
                if(plan==null) {
                    if(++idleTicks>=30)return problem(lastProblem,requested);
                    return new Step(new GoalNear(target.blockPosition(),3),false,null);
                }
                idleTicks=0;
                if(plan.cover()!=null) {
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
                        if(hit==null)return problem("No reachable obsidian placement face",requested);
                        if(use(slot(Items.OBSIDIAN),hit)){change(Stage.WAIT_BASE);blockConfirmed=false;}
                    } else change(Stage.CRYSTAL);
                } else {
                    BlockHitResult hit=placementHit(plan.pos());
                    if(hit==null)return problem("No reachable anchor placement face",requested);
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
                if(hit==null)return problem("Crystal base moved out of reach",requested);
                if(use(slot(Items.END_CRYSTAL),hit))change(Stage.WAIT_CRYSTAL);
                return waitStep();
            }
            case WAIT_CRYSTAL -> {
                EndCrystal crystal=crystals().stream().filter(entity->entity.position().distanceToSqr(blastPoint(plan))<2).findFirst().orElse(null);
                if(crystal==null)return waitStep();
                Vec3 aim=nearest(crystal.getBoundingBox(),ctx.playerHead());
                if(aim.distanceToSqr(ctx.playerHead())>9) {
                    BlockPos firing=closerFiringPoint(crystal,plan.pos());
                    if(firing==null)return problem("No normal-reach crystal hit with acceptable blast exposure",requested);
                    return new Step(new GoalNear(firing,0),false,null);
                }
                if(!clearRay(ctx.playerHead(),aim,null))return problem("Crystal is obstructed",requested);
                if(aim(aim)) {
                    id=crystal.getId();ctx.minecraft().gameMode.attack(ctx.player(),crystal);
                    ctx.player().swing(InteractionHand.MAIN_HAND,SwingAnimation.DEFAULT,false);change(Stage.WAIT_BREAK);pace();
                }
                return waitStep();
            }
            case WAIT_BREAK -> {
                boolean present=crystals().stream().anyMatch(entity->entity.getId()==id);
                if(!present) { confirmedCycles++;change(Stage.SELECT);plan=null; }
                return waitStep();
            }
            case WAIT_ANCHOR -> {
                if(blockConfirmed&&ctx.world().getBlockState(plan.pos()).is(Blocks.RESPAWN_ANCHOR))change(Stage.CHARGE);
                return waitStep();
            }
            case CHARGE -> {
                var state=ctx.world().getBlockState(plan.pos());
                if(!anchorExplodesAt(plan.pos())||!state.is(Blocks.RESPAWN_ANCHOR))return problem("Anchor is absent or is a respawn point in this dimension",requested);
                if(state.getValue(RespawnAnchorBlock.CHARGE)>0) { change(Stage.DETONATE);return waitStep(); }
                BlockHitResult hit=bestFace(plan.pos());
                if(hit==null)return problem("Anchor moved out of reach",requested);
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
                BlockHitResult hit=bestFace(plan.pos());if(hit==null)return problem("Charged anchor moved out of reach",requested);
                if(use(triggerSlot(),hit)){change(Stage.WAIT_DETONATE);blockConfirmed=false;}
                return waitStep();
            }
            case WAIT_DETONATE -> {
                if(blockConfirmed&&!ctx.world().getBlockState(plan.pos()).is(Blocks.RESPAWN_ANCHOR)) { confirmedCycles++;change(Stage.SELECT);plan=null; }
                return waitStep();
            }
        }
        return waitStep();
    }

    private Plan choose(Player target,CombatStyle requested) {
        List<CombatStyle> styles=requested==CombatStyle.AUTO?List.of(CombatStyle.CRYSTAL,CombatStyle.ANCHOR):List.of(requested);
        Plan best=null;
        for(CombatStyle style:styles) {
            if(rejected.contains(style)||!supplies(style))continue;
            BlockPos center=target.blockPosition();
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=-1;dy<=0;dy++) {
                BlockPos pos=center.offset(dx,dy,dz);
                if(!ctx.world().hasChunkAt(pos)||pos.distSqr(ctx.playerFeet())>36)continue;
                boolean existing=style==CombatStyle.CRYSTAL&&base(pos);
                if(style==CombatStyle.CRYSTAL) {
                    if(!existing&&(!ctx.world().getBlockState(pos).isAir()||slot(Items.OBSIDIAN)<0||placementHit(pos)==null))continue;
                    if(!crystalSpace(pos)||existing&&faceHit(pos,Direction.UP)==null)continue;
                } else {
                    if(!anchorExplodesAt(pos)||!ctx.world().getBlockState(pos).isAir()||!emptyBody(pos)||placementHit(pos)==null)continue;
                }
                double power=style==CombatStyle.CRYSTAL?6:5;Vec3 blast=style==CombatStyle.CRYSTAL?new Vec3(pos.getX()+0.5,pos.getY()+1,pos.getZ()+0.5):Vec3.atCenterOf(pos);
                BlockPos addedCover=style==CombatStyle.CRYSTAL&&!existing?pos:null,shield=null;
                if(!worthwhile(target,blast,power,style==CombatStyle.ANCHOR?pos:null,addedCover)) {
                    if(addedCover!=null||slot(Items.OBSIDIAN)<0||!Baritone.settings().killUseBlastCover.value)continue;
                    shield=findCover(target,blast,power,style==CombatStyle.ANCHOR?pos:null);
                    if(shield==null)continue;addedCover=shield;
                }
                double score=damage(target,blast,power,style==CombatStyle.ANCHOR?pos:null,addedCover)
                        -damage(ctx.player(),blast,power,style==CombatStyle.ANCHOR?pos:null,addedCover)*1.3-(existing?0:1)-(shield==null?0:1);
                if(best==null||score>best.score())best=new Plan(pos.immutable(),style,!existing,shield,score);
            }
        }
        lastProblem="No reachable blast position meets health, damage, dimension, and bystander limits";
        return best;
    }
    private BlockPos findCover(Player target,Vec3 blast,double power,BlockPos removedBlock) {
        BlockPos feet=ctx.playerFeet();
        for(Direction direction:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)) {
            BlockPos cover=feet.relative(direction);
            if(cover.equals(removedBlock)||!ctx.world().getBlockState(cover).isAir()||placementHit(cover)==null)continue;
            if(worthwhile(target,blast,power,removedBlock,cover))return cover;
        }return null;
    }
    private Vec3 blastPoint(Plan plan) { return plan.style()==CombatStyle.CRYSTAL?new Vec3(plan.pos().getX()+0.5,plan.pos().getY()+1,plan.pos().getZ()+0.5):Vec3.atCenterOf(plan.pos()); }
    private boolean worthwhile(Player target,Vec3 blast,double power,BlockPos removedBlock) { return worthwhile(target,blast,power,removedBlock,null); }
    private boolean worthwhile(Player target,Vec3 blast,double power,BlockPos removedBlock,BlockPos addedCover) {
        boolean bystanders=ctx.world().players().stream().anyMatch(player->player!=ctx.player()&&!player.getUUID().equals(target.getUUID())
                &&player.isAlive()&&!player.isSpectator()&&player.position().distanceToSqr(blast)<power*power*4);
        List<EndCrystal> nearbyCrystals=crystals().stream().filter(crystal->crystal.position().distanceToSqr(blast)<power*power*4).toList();
        long centered=removedBlock==null?nearbyCrystals.stream().filter(crystal->crystal.position().distanceToSqr(blast)<0.25).count():0;
        boolean chain=nearbyCrystals.size()>(centered==1?1:0)||ctx.entitiesStream().anyMatch(entity-> {
            String type=BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
            return (type.equals("tnt")||type.equals("tnt_minecart"))&&entity.position().distanceToSqr(blast)<power*power*4;
        });
        boolean protectedArea=MiningWaypoints.protection(ctx).stream().anyMatch(area->
                Math.abs(blast.x-area.x())<=area.radius()+power*2&&Math.abs(blast.y-area.y())<=area.radius()+power*2
                        &&Math.abs(blast.z-area.z())<=area.radius()+power*2);
        if(bystanders||chain||protectedArea||bot.getSculkProcess().snapshot().contains((int)Math.floor(blast.x),(int)Math.floor(blast.y),(int)Math.floor(blast.z)))return false;
        double self=damage(ctx.player(),blast,power,removedBlock,addedCover)+2; // margin for movement and unobserved equipment changes
        double opponent=damage(target,blast,power,removedBlock,addedCover);
        return BlastPolicy.mayBlast(self,opponent,ctx.player().getHealth(),ctx.player().getAbsorptionAmount(),target.getHealth(),
                Baritone.settings().killMaximumSelfDamage.value,Baritone.settings().killMinimumBlastDamage.value,
                Baritone.settings().killBlastHealthReserve.value,false,false);
    }
    private double damage(Player entity,Vec3 blast,double power,BlockPos removedBlock) { return damage(entity,blast,power,removedBlock,null); }
    private double damage(Player entity,Vec3 blast,double power,BlockPos removedBlock,BlockPos addedCover) {
        double exposure=removedBlock==null&&addedCover==null?ServerExplosion.getSeenPercent(blast,entity):modifiedExposure(blast,entity,removedBlock,addedCover);
        double raw=BlastPolicy.rawDamage(power,entity.position().distanceTo(blast),exposure);
        if(entity==ctx.player()&&ctx.world().getDifficulty().getId()==3)raw*=1.5;
        int protection=0;
        for(EquipmentSlot slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET)) {
            var enchantments=entity.getItemBySlot(slot).getEnchantments();
            for(var enchantment:enchantments.keySet()) {
                if(enchantment.is(Enchantments.PROTECTION))protection+=enchantments.getLevel(enchantment);
                if(enchantment.is(Enchantments.BLAST_PROTECTION))protection+=enchantments.getLevel(enchantment)*2;
            }
        }
        return BlastPolicy.afterArmor(raw,entity.getArmorValue(),entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS),protection);
    }
    private double modifiedExposure(Vec3 blast,Player entity,BlockPos removed,BlockPos addedCover) {
        AABB box=entity.getBoundingBox();double sx=1/((box.maxX-box.minX)*2+1),sy=1/((box.maxY-box.minY)*2+1),sz=1/((box.maxZ-box.minZ)*2+1);
        double bx=(1-Math.floor(1/sx)*sx)/2,bz=(1-Math.floor(1/sz)*sz)/2;int visible=0,total=0;
        for(double x=0;x<=1;x+=sx)for(double y=0;y<=1;y+=sy)for(double z=0;z<=1;z+=sz) {
            Vec3 point=new Vec3(box.minX+(box.maxX-box.minX)*x+bx,box.minY+(box.maxY-box.minY)*y,box.minZ+(box.maxZ-box.minZ)*z+bz);
            boolean covered=addedCover!=null&&BlastPolicy.blockedByCube(addedCover.getX(),addedCover.getY(),addedCover.getZ(),point.x,point.y,point.z,blast.x,blast.y,blast.z);
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
    private BlockHitResult placementHit(BlockPos space) {
        if(!ctx.world().getBlockState(space).isAir()||!emptyBody(space))return null;
        for(Direction direction:Direction.values()) {
            BlockPos neighbor=space.relative(direction);var state=ctx.world().getBlockState(neighbor);
            if(state.isAir()||!state.getFluidState().isEmpty()||ctx.world().getBlockEntity(neighbor)!=null
                    ||ConstructionPolicy.isConstruction(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath())
                    ||ConstructionPolicy.isContainer(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()))continue;
            BlockHitResult hit=faceHit(neighbor,direction.getOpposite());if(hit!=null)return hit;
        }return null;
    }
    private BlockHitResult faceHit(BlockPos pos,Direction face) {
        Vec3 point=Vec3.atCenterOf(pos).add(face.getStepX()*0.499,face.getStepY()*0.499,face.getStepZ()*0.499);
        double reach=Math.min(4.5,ctx.playerController().getBlockReachDistance());
        if(ctx.playerHead().distanceToSqr(point)>reach*reach)return null;
        var ray=ctx.world().clip(new ClipContext(ctx.playerHead(),point,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,ctx.player()));
        return ray.getType()==HitResult.Type.BLOCK&&ray.getBlockPos().equals(pos)&&ray.getDirection()==face?new BlockHitResult(ray.getLocation(),face,pos,false):null;
    }
    private BlockHitResult bestFace(BlockPos pos) {
        for(Direction face:Direction.values()) { var hit=faceHit(pos,face);if(hit!=null)return hit; }return null;
    }
    private static Vec3 nearest(AABB box,Vec3 eye) {
        return new Vec3(Math.max(box.minX+0.001,Math.min(box.maxX-0.001,eye.x)),Math.max(box.minY+0.001,Math.min(box.maxY-0.001,eye.y)),Math.max(box.minZ+0.001,Math.min(box.maxZ-0.001,eye.z)));
    }
    private BlockPos closerFiringPoint(EndCrystal crystal,BlockPos base) {
        BlockPos feet=ctx.playerFeet();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            BlockPos pos=feet.offset(dx,0,dz);
            if(pos.equals(feet)||!ctx.world().getBlockState(pos).isAir()||!ctx.world().getBlockState(pos.above()).isAir()||ctx.world().getBlockState(pos.below()).isAir())continue;
            Vec3 eye=new Vec3(pos.getX()+0.5,pos.getY()+1.62,pos.getZ()+0.5);
            if(nearest(crystal.getBoundingBox(),eye).distanceToSqr(eye)>9)continue;
            // Do not guess the future cover geometry; move only when a fully exposed estimate also fits.
            double raw=BlastPolicy.rawDamage(6,new Vec3(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5).distanceTo(crystal.position()),1);
            double upper=BlastPolicy.afterArmor(raw,ctx.player().getArmorValue(),ctx.player().getAttributeValue(Attributes.ARMOR_TOUGHNESS),0)+2;
            if(upper<=Baritone.settings().killMaximumSelfDamage.value&&ctx.player().getHealth()-upper>=Baritone.settings().killBlastHealthReserve.value)return pos;
        }return null;
    }
    private boolean aim(Vec3 point) {
        var rotation=RotationUtils.calcRotationFromVec3d(ctx.playerHead(),point,ctx.playerRotations());bot.getLookBehavior().updateTarget(rotation,true);
        return ctx.playerRotations().isReallyCloseTo(rotation);
    }
    private boolean use(int selected,BlockHitResult hit) {
        if(selected<0||ctx.player().isShiftKeyDown()||!aim(hit.getLocation()))return false;
        ctx.player().getInventory().setSelectedSlot(selected);ctx.playerController().syncHeldItem();
        ctx.playerController().processRightClickBlock(ctx.player(),ctx.world(),InteractionHand.MAIN_HAND,hit);
        ctx.player().swing(InteractionHand.MAIN_HAND,SwingAnimation.DEFAULT,false);pace();return true;
    }
    private void pace() { delay=StealthPolicy.bounded(Baritone.settings().killExplosiveDelayTicks.value,0,20); }
    private void change(Stage next) { stage=next;ticks=0; }
    private Step waitStep() { return new Step(null,false,null); }
    private Step problem(String reason,CombatStyle requested) {
        warning=remaining();
        if(plan!=null)rejected.add(plan.style());else rejected.addAll(List.of(CombatStyle.CRYSTAL,CombatStyle.ANCHOR));
        lastProblem=reason;stage=Stage.SELECT;plan=null;ticks=idleTicks=0;
        if(requested==CombatStyle.AUTO) {
            boolean remaining=List.of(CombatStyle.CRYSTAL,CombatStyle.ANCHOR).stream().anyMatch(style->!rejected.contains(style)&&supplies(style));
            return new Step(null,!remaining,null);
        }
        return new Step(null,false,reason);
    }
}
