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

package baritone.pathing.movement;

import baritone.Baritone;
import baritone.utils.bot.SculkPolicy;
import baritone.utils.social.StealthPolicy;
import baritone.utils.bot.MiningZone;
import baritone.utils.mining.MiningRouteAccess;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.cache.WorldData;
import baritone.pathing.precompute.PrecomputedData;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.mining.ConstructionPolicy;
import baritone.utils.mining.MiningWaypoints;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

import static baritone.api.pathing.movement.ActionCosts.COST_INF;

/**
 * @author Brady
 * @since 8/7/2018
 */
public class CalculationContext {

    private static final ItemStack STACK_BUCKET_WATER = new ItemStack(Items.WATER_BUCKET);

    public final boolean safeForThreadedUse;
    public final IBaritone baritone;
    public final Level world;
    public final WorldData worldData;
    public final BlockStateInterface bsi;
    public final ToolSet toolSet;
    public final boolean hasWaterBucket;
    public final boolean hasThrowaway;
    public final boolean canSprint;
    protected final double placeBlockCost; // protected because you should call the function instead
    public final boolean stealth;
    public final boolean allowBreak;
    public final List<Block> allowBreakAnyway;
    public final boolean allowParkour;
    public final boolean allowParkourPlace;
    public final boolean allowJumpAtBuildLimit;
    public final boolean allowParkourAscend;
    public final boolean assumeWalkOnWater;
    public boolean allowFallIntoLava;
    public final int frostWalker;
    public final boolean allowDiagonalDescend;
    public final boolean allowDiagonalAscend;
    public final boolean allowDownward;
    public int minFallHeight;
    public int maxFallHeightNoWater;
    public final int maxFallHeightBucket;
    public final double waterWalkSpeed;
    public final double breakBlockAdditionalCost;
    public double backtrackCostFavoringCoefficient;
    public double jumpPenalty;
    public final double walkOnWaterOnePenalty;
    public final boolean allowWalkOnMagmaBlocks;
    public final BetterWorldBorder worldBorder;

    public final PrecomputedData precomputedData;
    private final List<ConstructionPolicy.Area> protectedAreas;
    private final boolean avoidSculk;
    private final BlockPos stalkTarget;
    private final int stalkClearance;
    private final boolean miningBoundary;
    private final MiningZone miningZone;
    private final String miningWorld;
    private final SculkPolicy.Snapshot sculk;
    private final double sculkPenalty;
    private final boolean preferAvoidConstruction;
    private final boolean protectContainers;
    private final double constructionBreakPenalty;
    private final List<Block> additionalConstructionBlocks;

    public CalculationContext(IBaritone baritone) {
        this(baritone, false);
    }

    public CalculationContext(IBaritone baritone, boolean forUseOnAnotherThread) {
        this.precomputedData = new PrecomputedData();
        this.safeForThreadedUse = forUseOnAnotherThread;
        this.baritone = baritone;
        LocalPlayer player = baritone.getPlayerContext().player();
        this.world = baritone.getPlayerContext().world();
        this.worldData = (WorldData) baritone.getPlayerContext().worldData();
        this.miningWorld = MiningRouteAccess.dimension(baritone.getPlayerContext());
        var locations = MiningRouteAccess.locations(baritone.getPlayerContext());
        this.miningBoundary = baritone.getMineProcess().isActive() && locations.zonesEnabled();
        this.miningZone = miningBoundary ? locations.zone(miningWorld) : null;
        this.avoidSculk = Baritone.settings().mineAvoidSculk.value;
        this.sculk = ((Baritone) baritone).getSculkProcess().snapshot();
        double sculkCost = Baritone.settings().sculkPathPenalty.value;
        this.sculkPenalty = Double.isFinite(sculkCost) ? Math.max(0, sculkCost) : 5000;
        this.protectedAreas = MiningWaypoints.protection(baritone.getPlayerContext());
        this.preferAvoidConstruction = Baritone.settings().preferAvoidConstruction.value;
        this.protectContainers = Baritone.settings().protectContainers.value;
        double penalty = Baritone.settings().constructionBreakPenalty.value;
        this.constructionBreakPenalty = Double.isFinite(penalty) ? Math.max(0, penalty) : 1000D;
        this.additionalConstructionBlocks = new ArrayList<>(Baritone.settings().additionalConstructionBlocks.value);
        this.bsi = new BlockStateInterface(baritone.getPlayerContext(), forUseOnAnotherThread);
        this.toolSet = new ToolSet(player);
        this.stealth = ((Baritone) baritone).getStalkProcess().terrainLocked();
        this.stalkTarget = ((Baritone) baritone).getStalkProcess().avoidanceCenter();
        this.stalkClearance = Math.min(24,Math.max(8,Baritone.settings().stalkDistance.value-8));
        this.hasThrowaway = !stealth && Baritone.settings().allowPlace.value && ((Baritone) baritone).getInventoryBehavior().hasGenericThrowaway();
        this.hasWaterBucket = !stealth && Baritone.settings().allowWaterBucketFall.value && Inventory.isHotbarSlot(player.getInventory().findSlotMatchingItem(STACK_BUCKET_WATER)) && world.dimension() != Level.NETHER;
        this.canSprint = !((Baritone) baritone).getStalkProcess().quietMovement() && !((Baritone) baritone).getSculkProcess().quiet() && Baritone.settings().allowSprint.value && player.getFoodData().getFoodLevel() > 6;
        this.placeBlockCost = Baritone.settings().blockPlacementPenalty.value;
        this.allowBreak = !stealth && Baritone.settings().allowBreak.value;
        this.allowBreakAnyway = stealth ? List.of() : new ArrayList<>(Baritone.settings().allowBreakAnyway.value);
        this.allowParkour = !stealth && Baritone.settings().allowParkour.value;
        this.allowParkourPlace = !stealth && Baritone.settings().allowParkourPlace.value;
        this.allowJumpAtBuildLimit = Baritone.settings().allowJumpAtBuildLimit.value;
        this.allowParkourAscend = !stealth && Baritone.settings().allowParkourAscend.value;
        this.assumeWalkOnWater = Baritone.settings().assumeWalkOnWater.value;
        this.allowFallIntoLava = false; // Super secret internal setting for ElytraBehavior
        // todo: technically there can now be datapack enchants that replace blocks with any other at any range
        int frostWalkerLevel = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = baritone.getPlayerContext()
                .player()
                .getItemBySlot(slot)
                .getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                if (enchant.is(Enchantments.FROST_WALKER)) {
                    frostWalkerLevel = itemEnchantments.getLevel(enchant);
                }
            }
        }
        this.frostWalker = stealth ? 0 : frostWalkerLevel;
        this.allowDiagonalDescend = Baritone.settings().allowDiagonalDescend.value;
        this.allowDiagonalAscend = Baritone.settings().allowDiagonalAscend.value;
        this.allowDownward = Baritone.settings().allowDownward.value;
        this.minFallHeight = 3; // Minimum fall height used by MovementFall
        this.maxFallHeightNoWater = stealth ? Math.min(1, Baritone.settings().maxFallHeightNoWater.value) : Baritone.settings().maxFallHeightNoWater.value;
        this.maxFallHeightBucket = Baritone.settings().maxFallHeightBucket.value;
        float waterSpeedMultiplier = 1.0f;
        OUTER: for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = baritone.getPlayerContext()
                .player()
                .getItemBySlot(slot)
                .getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                List<EnchantmentAttributeEffect> effects = enchant.value()
                    .getEffects(EnchantmentEffectComponents.ATTRIBUTES);
                for (EnchantmentAttributeEffect effect : effects) {
                    if (effect.attribute().is(Attributes.WATER_MOVEMENT_EFFICIENCY.unwrapKey().get())) {
                        waterSpeedMultiplier = effect.amount().calculate(itemEnchantments.getLevel(enchant));
                        break OUTER;
                    }
                }
            }
        }
        this.waterWalkSpeed = ActionCosts.WALK_ONE_IN_WATER_COST * (1 - waterSpeedMultiplier) + ActionCosts.WALK_ONE_BLOCK_COST * waterSpeedMultiplier;
        this.breakBlockAdditionalCost = Baritone.settings().blockBreakAdditionalPenalty.value;
        this.backtrackCostFavoringCoefficient = Baritone.settings().backtrackCostFavoringCoefficient.value;
        this.jumpPenalty = Baritone.settings().jumpPenalty.value;
        this.walkOnWaterOnePenalty = Baritone.settings().walkOnWaterOnePenalty.value;
        this.allowWalkOnMagmaBlocks = Baritone.settings().allowWalkOnMagmaBlocks.value;
        // why cache these things here, why not let the movements just get directly from settings?
        // because if some movements are calculated one way and others are calculated another way,
        // then you get a wildly inconsistent path that isn't optimal for either scenario.
        this.worldBorder = new BetterWorldBorder(world.getWorldBorder());
    }

    public final IBaritone getBaritone() {
        return baritone;
    }

    public BlockState get(int x, int y, int z) {
        return bsi.get0(x, y, z); // laughs maniacally
    }

    public boolean isLoaded(int x, int z) {
        return bsi.isLoaded(x, z);
    }

    public BlockState get(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    public Block getBlock(int x, int y, int z) {
        return get(x, y, z).getBlock();
    }

    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        if (sculkProtected(x, y, z, current)) return COST_INF;
        if (!hasThrowaway) { // only true if allowPlace is true, see constructor
            return COST_INF;
        }
        if (isPossiblyProtected(x, y, z)) {
            return COST_INF;
        }
        if (!worldBorder.canPlaceAt(x, z)) {
            return COST_INF;
        }
        if (!Baritone.settings().allowPlaceInFluidsSource.value && current.getFluidState().isSource()) {
            return COST_INF;
        }
        if (!Baritone.settings().allowPlaceInFluidsFlow.value && !current.getFluidState().isEmpty() && !current.getFluidState().isSource()) {
            return COST_INF;
        }
        return placeBlockCost;
    }

    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        if (stealth || !insideMiningBoundary(x,y,z)) return COST_INF;
        if (sculkProtected(x, y, z, current)) return COST_INF;
        if (!allowBreak && !allowBreakAnyway.contains(current.getBlock())) {
            return COST_INF;
        }
        if (isPossiblyProtected(x, y, z)) {
            return COST_INF;
        }
        if (protectContainers && ConstructionPolicy.isContainer(BuiltInRegistries.BLOCK.getKey(current.getBlock()).getPath())) {
            return COST_INF;
        }
        return 1;
    }

    public boolean insideMiningBoundary(int x,int y,int z) { return !miningBoundary || (miningZone != null && miningZone.contains(miningWorld,x,y,z)); }

    public double sculkPathPenalty(int x, int y, int z) {
        // Both are immutable per-calculation movement hazards; ordinary melee/ambush approach omits player clearance.
        double penalty = avoidSculk ? sculk.penalty(x, y, z) * sculkPenalty : 0;
        if (stalkTarget != null) penalty += StealthPolicy.clearancePenalty(Math.hypot((double)x-stalkTarget.getX(),(double)z-stalkTarget.getZ()),stalkClearance);
        return penalty;
    }
    private boolean sculkProtected(int x, int y, int z, BlockState state) {
        return avoidSculk && (sculk.contains(x, y, z) || SculkPolicy.sculk(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()));
    }

    public double constructionPenalty(BlockState state) {
        return preferAvoidConstruction && (additionalConstructionBlocks.contains(state.getBlock())
                || ConstructionPolicy.isConstruction(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()))
                ? constructionBreakPenalty : 0;
    }

    public double placeBucketCost() {
        return placeBlockCost; // shrug
    }

    public boolean isPossiblyProtected(int x, int y, int z) {
        for (ConstructionPolicy.Area area : protectedAreas) {
            if (area.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }
}
