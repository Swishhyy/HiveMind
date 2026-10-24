/* This file is part of HiveMind, distributed under the LGPL-3.0 license. */
package hivemind.behavior;

import hivemind.HiveMind;
import hivemind.api.event.events.*;
import hivemind.api.event.events.type.EventState;
import hivemind.api.utils.RotationUtils;
import hivemind.utils.bot.BotWork;
import hivemind.utils.social.CombatCadence;
import hivemind.utils.social.CrystalTargeting;
import hivemind.utils.social.MeleeCombat;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

/** Shared attack budget, reactive melee and offhand replacement across autonomous work. */
public final class CombatBehavior extends Behavior {
    private final CombatCadence attacks=new CombatCadence();
    private Object world;
    private long lastTotemSwap=Long.MIN_VALUE;
    public CombatBehavior(HiveMind bot){super(bot);}
    private boolean active(){return ctx.player()!=null&&ctx.world()!=null&&ctx.player().isAlive()&&ctx.minecraft().gameMode!=null
            &&!hivemind.getAutonomy().paused()&&BotWork.active(hivemind)&&ctx.minecraft().gui.screen()==null
            &&ctx.player().containerMenu==ctx.player().inventoryMenu&&ctx.player().inventoryMenu.getCarried().isEmpty();}
    public boolean ensureTotem(){
        if(!active()||!HiveMind.settings().botAutoTotem.value
                ||hivemind.getStalkProcess().hunting()&&!HiveMind.settings().killUseTotems.value
                ||ctx.player().getOffhandItem().is(Items.TOTEM_OF_UNDYING)||lastTotemSwap==hivemind.clientTick())return false;
        if(ctx.player().isUsingItem()&&ctx.player().getUsedItemHand()==InteractionHand.OFF_HAND){
            if(!ctx.player().getOffhandItem().is(Items.SHIELD))return false;
            ctx.minecraft().gameMode.releaseUsingItem(ctx.player());
        }
        for(int slot=0;slot<36;slot++)if(ctx.player().getInventory().getItem(slot).is(Items.TOTEM_OF_UNDYING)){
            ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,slot<9?slot+36:slot,40,ContainerInput.SWAP,ctx.player());
            lastTotemSwap=hivemind.clientTick();return true;
        }
        return false;
    }
    private boolean canAttack(Entity target){
        if(!active()||target==null||!target.isAlive()||target.isSpectator()||ctx.player().isFallFlying()
                ||hivemind.getClutch().active()||hivemind.getShulkerProcess().isActive())return false;
        if(target instanceof Player player&&(player.isCreative()||hivemind.getNetwork().isTeammate(player.getUUID())
                ||hivemind.getBotControl().isTrusted(player.getUUID())&&!(hivemind.getStalkProcess().hunting()
                &&player.getUUID().equals(hivemind.getStalkProcess().target())&&HiveMind.settings().killAllowTrustedTargets.value)))return false;
        var eye=ctx.playerHead();
        if(target instanceof EndCrystal)return CrystalTargeting.facing(target.getBoundingBox(),eye,
                RotationUtils.calcLookDirectionFromRotation(ctx.playerRotations()),3,
                point->ctx.world().clip(new ClipContext(eye,point,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,ctx.player())).getType()==HitResult.Type.MISS);
        if(!ctx.player().hasLineOfSight(target))return false;
        return target.getBoundingBox().inflate(.06).clip(eye,eye.add(RotationUtils.calcLookDirectionFromRotation(ctx.playerRotations()).scale(3))).isPresent();
    }
    public boolean melee(LivingEntity target){
        if(!canAttack(target)||ctx.player().getAttackStrengthScale(0)<.95f||MeleeCombat.weapon(ctx,target.isBlocking())<0)return false;
        if(ctx.player().isUsingItem()){
            if(!ctx.player().getOffhandItem().is(Items.SHIELD)||ctx.player().getUsedItemHand()!=InteractionHand.OFF_HAND)return false;
            ctx.minecraft().gameMode.releaseUsingItem(ctx.player());
        }
        if(!hivemind.getNetwork().team().clearStrike(target)||!attacks.claim(hivemind.clientTick(),1))return false;
        int slot=MeleeCombat.weapon(ctx,target.isBlocking());hivemind.getReliability().select(slot);ctx.playerController().syncHeldItem();
        strike(target);hivemind.getStalkProcess().onMeleeStrike(target);return true;
    }
    public boolean crystal(Entity target){
        if(!canAttack(target)||!attacks.claim(hivemind.clientTick(),1))return false;
        strike(target);return true;
    }
    private void strike(Entity target){ctx.minecraft().gameMode.attack(ctx.player(),target);ctx.player().swing(InteractionHand.MAIN_HAND,SwingAnimation.DEFAULT,false);}
    @Override public void onTick(TickEvent event){
        if(event.getState()!=EventState.PRE)return;
        if(event.getType()==TickEvent.Type.OUT||world!=ctx.world()) {attacks.reset();lastTotemSwap=Long.MIN_VALUE;world=ctx.world();}
        if(!active())return;ensureTotem();
        if(!HiveMind.settings().botKillAura.value||ctx.player().isFallFlying()||hivemind.getClutch().active()||hivemind.getShulkerProcess().isActive())return;
        LivingEntity target=hivemind.getStalkProcess().auraTarget();
        if(target==null)target=hivemind.getConflictProcess().auraTarget();
        if(target!=null&&target.distanceToSqr(ctx.player())<16)MeleeCombat.aim(ctx,hivemind,target);
    }
    /** The ordinary movement update has sent this tick's aim before these reactive attacks run. */
    @Override public void onPlayerUpdate(PlayerUpdateEvent event){
        if(event.getState()!=EventState.POST||!active()||hivemind.getClutch().active()||hivemind.getShulkerProcess().isActive())return;
        if(HiveMind.settings().botAutoCrystal.value)hivemind.getStalkProcess().reactiveExplosives();
        if(!HiveMind.settings().botKillAura.value)return;
        LivingEntity target=hivemind.getStalkProcess().auraTarget();if(target==null)target=hivemind.getConflictProcess().auraTarget();
        if(target!=null)melee(target);
    }
}
