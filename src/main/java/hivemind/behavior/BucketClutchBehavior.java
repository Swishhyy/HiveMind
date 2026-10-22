/* This file is part of HiveMind, distributed under the LGPL-3.0 license. */
package hivemind.behavior;

import hivemind.HiveMind;
import hivemind.api.event.events.*;
import hivemind.api.event.events.type.EventState;
import hivemind.api.utils.RotationUtils;
import hivemind.utils.bot.BotInventory;
import hivemind.utils.bot.BotWork;
import hivemind.utils.social.ClutchPolicy;
import hivemind.utils.social.TraversalPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Planned and unexpected bucket clutches share one owner and retry every reachable tick. */
public final class BucketClutchBehavior extends Behavior {
    private BlockPos planned,landing,placed;
    private long planAt,lastUse=Long.MIN_VALUE;
    private int previous=-1,wetTicks,pickupTicks;
    private boolean attempted;
    private Object world;
    public BucketClutchBehavior(HiveMind bot){super(bot);}
    public void plan(BlockPos point){planned=point.immutable();planAt=ctx.player().tickCount;}
    public boolean active(){return landing!=null||placed!=null;}
    private boolean enabled(){return ctx.player()!=null&&ctx.world()!=null&&ctx.player().isAlive()&&ctx.minecraft().gameMode!=null
            &&HiveMind.settings().allowWaterBucketFall.value&&!hivemind.getAutonomy().paused()&&BotWork.active(hivemind)
            &&ctx.world().dimension()!=Level.NETHER&&ctx.minecraft().gui.screen()==null&&ctx.player().containerMenu==ctx.player().inventoryMenu
            &&ctx.player().inventoryMenu.getCarried().isEmpty()&&!ctx.player().isFallFlying()&&!ctx.player().isPassenger();}
    private boolean valid(BlockPos pos){
        if(!ctx.world().hasChunkAt(pos)||!ctx.world().hasChunkAt(pos.below()))return false;
        var support=ctx.world().getBlockState(pos.below());
        return TraversalPolicy.bucketLanding(true,ctx.world().getBlockState(pos).isAir(),support.isCollisionShapeFullBlock(ctx.world(),pos.below()),
                BuiltInRegistries.BLOCK.getKey(support.getBlock()).getPath());
    }
    private BlockPos predict(){
        var movement=ctx.player().getDeltaMovement();int ahead=3;
        for(int pass=0;pass<2;pass++){
            int x=(int)Math.floor(ctx.player().getX()+movement.x*ahead),z=(int)Math.floor(ctx.player().getZ()+movement.z*ahead);
            for(int y=ctx.playerFeet().getY();y>=Math.max(ctx.world().getMinY()+1,ctx.playerFeet().getY()-48);y--){
                BlockPos pos=new BlockPos(x,y,z);if(!ctx.world().hasChunkAt(pos))return null;
                if(!ctx.world().getBlockState(pos.below()).getCollisionShape(ctx.world(),pos.below()).isEmpty()){
                    if(!valid(pos))return null;
                    int ticks=Math.min(8,ClutchPolicy.impactTicks(Math.max(0,ctx.player().getY()-y),movement.y));
                    if(pass==0&&ticks!=ahead){ahead=ticks;break;}return pos;
                }
            }
        }
        return null;
    }
    private boolean select(net.minecraft.world.item.Item item){
        int source=BotInventory.slot(ctx,stack->stack.is(item));if(source<0)return false;
        if(previous<0)previous=ctx.player().getInventory().getSelectedSlot();
        if(ctx.player().isUsingItem())ctx.minecraft().gameMode.releaseUsingItem(ctx.player());
        if(source>=9){
            if(!HiveMind.settings().allowInventory.value)return false;
            int destination=-1;
            for(int i=0;i<9;i++)if(ctx.player().getInventory().getItem(i).isEmpty()){destination=i;break;}
            if(destination<0)destination=8;
            ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,source,destination,ContainerInput.SWAP,ctx.player());source=destination;
        }
        ctx.player().getInventory().setSelectedSlot(source);ctx.playerController().syncHeldItem();return ctx.player().getMainHandItem().is(item);
    }
    private void finish(){
        if(previous>=0&&ctx.player()!=null&&ctx.minecraft().gameMode!=null){ctx.player().getInventory().setSelectedSlot(previous);ctx.playerController().syncHeldItem();}
        previous=-1;landing=placed=planned=null;attempted=false;wetTicks=pickupTicks=0;
    }
    @Override public void onTick(TickEvent event){
        if(event.getState()!=EventState.PRE)return;
        if(event.getType()==TickEvent.Type.OUT||world!=ctx.world()){finish();planned=null;lastUse=Long.MIN_VALUE;world=ctx.world();}
        if(!enabled()){if(active())finish();return;}
        if(planned!=null&&ctx.player().tickCount-planAt>20)planned=null;
        if(placed==null&&landing==null&&!ctx.player().onGround()&&!ctx.player().isInWater()&&!ctx.player().isInLava()&&ctx.player().getDeltaMovement().y<-.2
                &&(planned!=null||HiveMind.settings().botEmergencyBucketClutch.value&&ctx.player().fallDistance>2.5)){
            BlockPos next=planned!=null&&valid(planned)?planned:HiveMind.settings().botEmergencyBucketClutch.value?predict():null;
            if(next!=null&&ctx.player().getY()-next.getY()<ClutchPolicy.armHeight(ctx.playerController().getBlockReachDistance(),ctx.player().getDeltaMovement().y))landing=next;
        }
        if(landing!=null){
            if(attempted&&ctx.world().getFluidState(landing).isSource()&&ctx.world().getFluidState(landing).getType() instanceof net.minecraft.world.level.material.WaterFluid){
                placed=landing;landing=null;
            }else if(ctx.player().onGround()||ctx.player().isInWater()||!valid(landing)||!select(Items.WATER_BUCKET)){finish();return;}
        }
        if(placed!=null){
            wetTicks=ctx.player().isInWater()?wetTicks+1:0;
            if(++pickupTicks>60||placed.distSqr(ctx.playerFeet())>9){finish();return;}
            if(!ClutchPolicy.caught(ctx.player().onGround(),ctx.player().isInWater(),ctx.player().getDeltaMovement().y,wetTicks))return;
            if(!ctx.world().getFluidState(placed).isSource()||!select(Items.BUCKET)){finish();return;}
            hivemind.getInputOverrideHandler().clearAllKeys();
        }
        BlockPos target=placed!=null?placed:landing;
        if(target!=null){
            hivemind.getLookBehavior().updateTarget(RotationUtils.calcRotationFromVec3d(ctx.playerHead(),new Vec3(target.getX()+.5,target.getY()+.01,target.getZ()+.5),ctx.playerRotations()),true);
            tryUse();
        }
    }
    /** Runs after LookBehavior applies the requested rotation, avoiding stale crosshair state and use-item delay. */
    @Override public void onPlayerUpdate(PlayerUpdateEvent event){
        if(event.getState()==EventState.PRE)tryUse();
    }
    private void tryUse(){
        if(!active()||!enabled()||lastUse==hivemind.clientTick())return;
        boolean pickup=placed!=null;
        if(pickup&&!ClutchPolicy.caught(ctx.player().onGround(),ctx.player().isInWater(),ctx.player().getDeltaMovement().y,wetTicks))return;
        if(!ctx.player().getMainHandItem().is(pickup?Items.BUCKET:Items.WATER_BUCKET))return;
        BlockPos target=pickup?placed:landing;var eye=ctx.playerHead();
        var rotation=RotationUtils.calcRotationFromVec3d(eye,new Vec3(target.getX()+.5,target.getY()+.01,target.getZ()+.5),ctx.playerRotations());
        var hit=ctx.world().clip(new ClipContext(eye,eye.add(RotationUtils.calcLookDirectionFromRotation(rotation).scale(ctx.playerController().getBlockReachDistance())),
                ClipContext.Block.OUTLINE,pickup?ClipContext.Fluid.SOURCE_ONLY:ClipContext.Fluid.NONE,ctx.player()));
        if(hit.getType()!=HitResult.Type.BLOCK||!(pickup?hit.getBlockPos().equals(target):hit.getBlockPos().equals(target.below())&&hit.getDirection()==Direction.UP))return;
        lastUse=hivemind.clientTick();attempted|=!pickup;
        // useItem carries the player's rotation. Supply the actual bucket ray for this call,
        // including before travel crosses the remaining placement window this tick.
        float yaw=ctx.player().getYRot(),pitch=ctx.player().getXRot();
        try{ctx.player().setYRot(rotation.getYaw());ctx.player().setXRot(rotation.getPitch());ctx.playerController().processRightClick(ctx.player(),ctx.world(),InteractionHand.MAIN_HAND);}
        finally{ctx.player().setYRot(yaw);ctx.player().setXRot(pitch);}
        if(pickup&&ctx.player().getMainHandItem().is(Items.WATER_BUCKET))finish();
    }
}
