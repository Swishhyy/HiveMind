/* This file is part of Baritone, distributed under the LGPL-3.0 license. */
package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.*;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.RotationUtils;
import baritone.utils.bot.BotWork;
import baritone.utils.social.CombatCadence;
import baritone.utils.social.MeleeCombat;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;

/** Shared attack budget, reactive melee and offhand replacement across autonomous work. */
public final class CombatBehavior extends Behavior {
    private final CombatCadence attacks=new CombatCadence();
    private Object world;
    private long lastTotemSwap=Long.MIN_VALUE;
    public CombatBehavior(Baritone bot){super(bot);}
    private boolean active(){return ctx.player()!=null&&ctx.world()!=null&&ctx.player().isAlive()&&ctx.minecraft().gameMode!=null
            &&!baritone.getAutonomy().paused()&&BotWork.active(baritone)&&ctx.minecraft().gui.screen()==null
            &&ctx.player().containerMenu==ctx.player().inventoryMenu&&ctx.player().inventoryMenu.getCarried().isEmpty();}
    public boolean ensureTotem(){
        if(!active()||!Baritone.settings().botAutoTotem.value||!Baritone.settings().allowInventory.value
                ||baritone.getStalkProcess().hunting()&&!Baritone.settings().killUseTotems.value
                ||ctx.player().getOffhandItem().is(Items.TOTEM_OF_UNDYING)||lastTotemSwap==baritone.clientTick())return false;
        if(ctx.player().isUsingItem()&&ctx.player().getUsedItemHand()==InteractionHand.OFF_HAND){
            if(!ctx.player().getOffhandItem().is(Items.SHIELD))return false;
            ctx.minecraft().gameMode.releaseUsingItem(ctx.player());
        }
        for(int slot=0;slot<36;slot++)if(ctx.player().getInventory().getItem(slot).is(Items.TOTEM_OF_UNDYING)){
            ctx.playerController().windowClick(ctx.player().inventoryMenu.containerId,slot<9?slot+36:slot,40,ContainerInput.SWAP,ctx.player());
            lastTotemSwap=baritone.clientTick();return true;
        }
        return false;
    }
    private boolean canAttack(Entity target){
        if(!active()||target==null||!target.isAlive()||target.isSpectator()||ctx.player().isFallFlying()
                ||baritone.getClutch().active()||baritone.getShulkerProcess().isActive()||!ctx.player().hasLineOfSight(target))return false;
        if(target instanceof Player player&&(player.isCreative()||baritone.getNetwork().isTeammate(player.getUUID())
                ||baritone.getBotControl().isTrusted(player.getUUID())&&!(baritone.getStalkProcess().hunting()
                &&player.getUUID().equals(baritone.getStalkProcess().target())&&Baritone.settings().killAllowTrustedTargets.value)))return false;
        var eye=ctx.playerHead();
        return target.getBoundingBox().inflate(.06).clip(eye,eye.add(RotationUtils.calcLookDirectionFromRotation(ctx.playerRotations()).scale(3))).isPresent();
    }
    public boolean melee(LivingEntity target){
        if(!canAttack(target)||ctx.player().getAttackStrengthScale(0)<.95f||MeleeCombat.weapon(ctx,target.isBlocking())<0)return false;
        if(ctx.player().isUsingItem()){
            if(!ctx.player().getOffhandItem().is(Items.SHIELD)||ctx.player().getUsedItemHand()!=InteractionHand.OFF_HAND)return false;
            ctx.minecraft().gameMode.releaseUsingItem(ctx.player());
        }
        if(!baritone.getNetwork().team().clearStrike(target)||!attacks.claim(baritone.clientTick(),1))return false;
        int slot=MeleeCombat.weapon(ctx,target.isBlocking());ctx.player().getInventory().setSelectedSlot(slot);ctx.playerController().syncHeldItem();
        strike(target);baritone.getStalkProcess().onMeleeStrike(target);return true;
    }
    public boolean crystal(Entity target){
        if(!canAttack(target)||!attacks.claim(baritone.clientTick(),1))return false;
        strike(target);return true;
    }
    private void strike(Entity target){ctx.minecraft().gameMode.attack(ctx.player(),target);ctx.player().swing(InteractionHand.MAIN_HAND,SwingAnimation.DEFAULT,false);}
    @Override public void onTick(TickEvent event){
        if(event.getState()!=EventState.PRE)return;
        if(event.getType()==TickEvent.Type.OUT||world!=ctx.world()) {attacks.reset();lastTotemSwap=Long.MIN_VALUE;world=ctx.world();}
        if(!active())return;ensureTotem();
        if(!Baritone.settings().botKillAura.value||ctx.player().isFallFlying()||baritone.getClutch().active()||baritone.getShulkerProcess().isActive())return;
        LivingEntity target=baritone.getStalkProcess().auraTarget();
        if(target==null)target=baritone.getConflictProcess().auraTarget();
        if(target!=null&&target.distanceToSqr(ctx.player())<16)MeleeCombat.aim(ctx,baritone,target);
    }
    /** The ordinary movement update has sent this tick's aim before these reactive attacks run. */
    @Override public void onPlayerUpdate(PlayerUpdateEvent event){
        if(event.getState()!=EventState.POST||!active()||baritone.getClutch().active()||baritone.getShulkerProcess().isActive())return;
        if(Baritone.settings().botAutoCrystal.value)baritone.getStalkProcess().reactiveExplosives();
        if(!Baritone.settings().botKillAura.value)return;
        LivingEntity target=baritone.getStalkProcess().auraTarget();if(target==null)target=baritone.getConflictProcess().auraTarget();
        if(target!=null)melee(target);
    }
}
