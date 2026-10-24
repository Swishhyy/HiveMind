/*
 * This file is part of HiveMind.
 *
 * HiveMind is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * HiveMind is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with HiveMind.  If not, see <https://www.gnu.org/licenses/>.
 */

package hivemind.event;

import hivemind.HiveMind;
import hivemind.api.event.events.*;
import hivemind.api.event.events.type.EventState;
import hivemind.api.event.listener.IEventBus;
import hivemind.api.event.listener.IGameEventListener;
import hivemind.api.utils.Helper;
import hivemind.api.utils.Pair;
import hivemind.cache.CachedChunk;
import hivemind.cache.WorldProvider;
import hivemind.utils.BlockStateInterface;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import hivemind.utils.reliability.ActionArbiter;

/**
 * @author Brady
 * @since 7/31/2018
 */
public final class GameEventHandler implements IEventBus, Helper {

    private final HiveMind hivemind;

    private final List<IGameEventListener> listeners = new CopyOnWriteArrayList<>();
    private long clientTick;

    public long clientTick(){return clientTick;}

    public GameEventHandler(HiveMind hivemind) {
        this.hivemind = hivemind;
    }

    @Override
    public final void onTick(TickEvent event) {
        if(event.getState()==EventState.PRE)clientTick++;
        if (event.getType() == TickEvent.Type.IN) {
            try {
                hivemind.bsi = new BlockStateInterface(hivemind.getPlayerContext(), true);
            } catch (Exception ex) {
                ex.printStackTrace();
                hivemind.bsi = null;
            }
        } else {
            hivemind.bsi = null;
        }
        if(event.getState()==EventState.PRE&&event.getType()==TickEvent.Type.IN)hivemind.planActions();else hivemind.getActions().manual(hivemind.isPlayerClient());
        enabledListeners().forEach(l -> dispatch(l, () -> l.onTick(event)));
    }

    @Override
    public void onPostTick(TickEvent event) {
        enabledListeners().forEach(l -> dispatch(l, () -> l.onPostTick(event)));
    }

    @Override
    public final void onPlayerUpdate(PlayerUpdateEvent event) {
        enabledListeners().forEach(l -> dispatch(l, () -> l.onPlayerUpdate(event)));
    }

    @Override
    public final void onSendChatMessage(ChatEvent event) {
        listeners.forEach(l -> l.onSendChatMessage(event));
    }

    @Override
    public void onPreTabComplete(TabCompleteEvent event) {
        listeners.forEach(l -> l.onPreTabComplete(event));
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        EventState state = event.getState();
        ChunkEvent.Type type = event.getType();

        Level world = hivemind.getPlayerContext().world();

        // Whenever the server sends us to another dimension, chunks are unloaded
        // technically after the new world has been loaded, so we perform a check
        // to make sure the chunk being unloaded is already loaded.
        boolean isPreUnload = state == EventState.PRE
                && type == ChunkEvent.Type.UNLOAD
                && world.getChunkSource().getChunk(event.getX(), event.getZ(), null, false) != null;

        if (event.isPostPopulate() || isPreUnload) {
            hivemind.getWorldProvider().ifWorldLoaded(worldData -> {
                LevelChunk chunk = world.getChunk(event.getX(), event.getZ());
                worldData.getCachedWorld().queueForPacking(chunk);
            });
        }


        enabledListeners().forEach(l -> l.onChunkEvent(event));
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        if (HiveMind.settings().repackOnAnyBlockChange.value) {
            final boolean keepingTrackOf = event.getBlocks().stream()
                    .map(Pair::second).map(BlockState::getBlock)
                    .anyMatch(CachedChunk.BLOCKS_TO_KEEP_TRACK_OF::contains);

            if (keepingTrackOf) {
                hivemind.getWorldProvider().ifWorldLoaded(worldData -> {
                    final Level world = hivemind.getPlayerContext().world();
                    ChunkPos pos = event.getChunkPos();
                    worldData.getCachedWorld().queueForPacking(world.getChunk(pos.x(), pos.z()));
                });
            }
        }

        enabledListeners().forEach(l -> l.onBlockChange(event));
    }

    @Override
    public final void onRenderPass(RenderEvent event) {
        if(hivemind.isPlayerClient())return;
        enabledListeners().forEach(l -> l.onRenderPass(event));
    }

    @Override
    public final void onWorldEvent(WorldEvent event) {
        WorldProvider cache = hivemind.getWorldProvider();

        if (event.getState() == EventState.PRE) {
            // Never carry a path segment or forced inputs into a different dimension.
            boolean preserve = hivemind.getPortalTravelProcess().canPreserveTransition(event);
            hivemind.getPathingBehavior().secretInternalSegmentCancel();
            hivemind.getInputOverrideHandler().clearAllKeys();
            hivemind.getPathingControlManager().cancelEverything(preserve);
        }

        if (event.getState() == EventState.POST) {
            cache.closeWorld();
            if (event.getWorld() != null) {
                cache.initWorld(event.getWorld());
            }
        }

        enabledListeners().forEach(l -> l.onWorldEvent(event));
    }

    @Override
    public final void onSendPacket(PacketEvent event) {
        enabledListeners().forEach(l -> l.onSendPacket(event));
    }

    @Override
    public final void onReceivePacket(PacketEvent event) {
        enabledListeners().forEach(l -> l.onReceivePacket(event));
    }

    @Override
    public void onPlayerRotationMove(RotationMoveEvent event) {
        enabledListeners().forEach(l -> l.onPlayerRotationMove(event));
    }

    @Override
    public void onPlayerSprintState(SprintStateEvent event) {
        enabledListeners().forEach(l -> l.onPlayerSprintState(event));
    }

    @Override
    public void onBlockInteract(BlockInteractEvent event) {
        enabledListeners().forEach(l -> l.onBlockInteract(event));
    }

    @Override
    public void onPlayerDeath() {
        enabledListeners().forEach(IGameEventListener::onPlayerDeath);
    }

    @Override
    public void onPathEvent(PathEvent event) {
        enabledListeners().forEach(l -> l.onPathEvent(event));
    }

    private java.util.stream.Stream<IGameEventListener> enabledListeners() {
        return listeners.stream().filter(l -> !hivemind.isPlayerClient()
            || l instanceof hivemind.behavior.NetworkBehavior || l instanceof hivemind.behavior.EntityAwareness
            || l instanceof hivemind.behavior.LookBehavior || l instanceof hivemind.behavior.UpdateBehavior);
    }

    private void dispatch(IGameEventListener listener,Runnable action){
        var owner=hivemind.actionOwner(listener);
        if(listener instanceof hivemind.behavior.PathingBehavior){
            var process=hivemind.getPathingControlManager().mostRecentInControl().orElse(null);
            if(process!=null)owner=hivemind.actionOwner(process);
        }
        try(var scope=hivemind.getActions().scope(owner.name(),owner.priority())){action.run();}
    }

    @Override
    public final void registerEventListener(IGameEventListener listener) {
        this.listeners.add(listener);
    }
}
