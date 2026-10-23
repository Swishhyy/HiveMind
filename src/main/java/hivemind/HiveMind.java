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

package hivemind;

import hivemind.api.HiveMindAPI;
import hivemind.api.IHiveMind;
import hivemind.api.Settings;
import hivemind.api.behavior.IBehavior;
import hivemind.api.event.listener.IEventBus;
import hivemind.api.process.IHiveMindProcess;
import hivemind.api.process.IElytraProcess;
import hivemind.api.utils.IPlayerContext;
import hivemind.behavior.*;
import hivemind.cache.WorldProvider;
import hivemind.command.manager.CommandManager;
import hivemind.event.GameEventHandler;
import hivemind.process.*;
import hivemind.selection.SelectionManager;
import hivemind.utils.BlockStateInterface;
import hivemind.utils.GuiClick;
import hivemind.utils.InputOverrideHandler;
import hivemind.utils.PathingControlManager;
import hivemind.utils.player.HiveMindPlayerContext;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * @author Brady
 * @since 7/31/2018
 */
public class HiveMind implements IHiveMind {

    private static final ThreadPoolExecutor threadPool;

    static {
        threadPool = new ThreadPoolExecutor(4, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>());
    }

    private final Minecraft mc;
    private final Path directory;

    private final GameEventHandler gameEventHandler;

    private final PathingBehavior pathingBehavior;
    private final LookBehavior lookBehavior;
    private final InventoryBehavior inventoryBehavior;
    private final InputOverrideHandler inputOverrideHandler;

    private final FollowProcess followProcess;
    private final MineProcess mineProcess;
    private final RoamProcess roamProcess;
    private final DepositProcess depositProcess;
    private final ShulkerSupplyProcess shulkerProcess;
    private final PortalTravelProcess portalTravelProcess;
    private final ConflictProcess conflictProcess;
    private final SculkProcess sculkProcess;
    private final SurvivalProcess survivalProcess;
    private final CompanionProcess companionProcess;
    private final WorkstationProcess workstationProcess;
    private final RepairProcess repairProcess;
    private final JobManager jobManager;
    private final hivemind.utils.network.ClientRole clientRole;
    private final ScoutProcess scoutProcess;
    private final OperationsProcess operations;
    private final BotControlBehavior botControl;
    private final NetworkBehavior network;
    private final AutonomyBehavior autonomy;
    private final FlightBehavior flight;
    private final EntityAwareness awareness;
    private final CombatBehavior combat;
    private final BucketClutchBehavior clutch;
    private final StalkProcess stalkProcess;
    private final GetToBlockProcess getToBlockProcess;
    private final CustomGoalProcess customGoalProcess;
    private final BuilderProcess builderProcess;
    private final ExploreProcess exploreProcess;
    private final FarmProcess farmProcess;
    private final InventoryPauserProcess inventoryPauserProcess;
    private final IElytraProcess elytraProcess;

    private final PathingControlManager pathingControlManager;
    private final hivemind.utils.reliability.ActionArbiter actions = new hivemind.utils.reliability.ActionArbiter();
    private final ReliabilityProcess reliability;
    private final SelectionManager selectionManager;
    private final CommandManager commandManager;

    private final IPlayerContext playerContext;
    private final WorldProvider worldProvider;

    public BlockStateInterface bsi;

    HiveMind(Minecraft mc) {
        this.mc = mc;
        this.gameEventHandler = new GameEventHandler(this);

        this.directory = mc.gameDirectory.toPath().resolve("hivemind");
        if (!Files.exists(this.directory)) {
            try {
                Files.createDirectories(this.directory);
            } catch (IOException ignored) {}
        }

        this.clientRole = new hivemind.utils.network.ClientRole(this.directory);

        // Define this before behaviors try and get it, or else it will be null and the builds will fail!
        this.playerContext = new HiveMindPlayerContext(this, mc);

        {
            this.lookBehavior         = this.registerBehavior(LookBehavior::new);
            this.pathingBehavior      = this.registerBehavior(PathingBehavior::new);
            this.inventoryBehavior    = this.registerBehavior(InventoryBehavior::new);
            this.inputOverrideHandler = this.registerBehavior(InputOverrideHandler::new);
            this.registerBehavior(WaypointBehavior::new);
        }

        this.pathingControlManager = new PathingControlManager(this);
        {
            this.reliability             = this.registerProcess(ReliabilityProcess::new);
            this.followProcess           = this.registerProcess(FollowProcess::new);
            this.mineProcess             = this.registerProcess(MineProcess::new);
            this.operations              = this.registerProcess(OperationsProcess::new);
            this.scoutProcess            = this.registerProcess(ScoutProcess::new);
            this.roamProcess             = this.registerProcess(RoamProcess::new);
            this.depositProcess          = this.registerProcess(DepositProcess::new);
            this.shulkerProcess          = this.registerProcess(ShulkerSupplyProcess::new);
            this.portalTravelProcess     = this.registerProcess(PortalTravelProcess::new);
            this.conflictProcess         = this.registerProcess(ConflictProcess::new);
            this.stalkProcess            = this.registerProcess(StalkProcess::new);
            this.sculkProcess            = this.registerProcess(SculkProcess::new);
            this.survivalProcess         = this.registerProcess(SurvivalProcess::new);
            this.companionProcess        = this.registerProcess(CompanionProcess::new);
            this.workstationProcess      = this.registerProcess(WorkstationProcess::new);
            this.repairProcess           = this.registerProcess(RepairProcess::new);
            this.jobManager              = this.registerProcess(JobManager::new);
            this.customGoalProcess       = this.registerProcess(CustomGoalProcess::new); // very high iq
            this.getToBlockProcess       = this.registerProcess(GetToBlockProcess::new);
            this.builderProcess          = this.registerProcess(BuilderProcess::new);
            this.exploreProcess          = this.registerProcess(ExploreProcess::new);
            this.farmProcess             = this.registerProcess(FarmProcess::new);
            this.inventoryPauserProcess  = this.registerProcess(InventoryPauserProcess::new);
            this.elytraProcess           = this.registerProcess(ElytraProcess::create);
            this.registerProcess(BackfillProcess::new);
        }

        this.worldProvider = new WorldProvider(this);
        this.selectionManager = new SelectionManager(this);
        this.botControl = new BotControlBehavior(this);
        this.network = new NetworkBehavior(this);
        this.awareness = new EntityAwareness(this);
        this.combat = new CombatBehavior(this);
        this.clutch = new BucketClutchBehavior(this);
        this.flight = new FlightBehavior(this);
        this.autonomy = new AutonomyBehavior(this);
        this.commandManager = new CommandManager(this);
        this.registerBehavior(this.reliability);
        this.registerBehavior(this.awareness);
        this.registerBehavior(this.roamProcess);
        this.registerBehavior(this.portalTravelProcess);
        this.registerBehavior(this.conflictProcess);
        this.registerBehavior(this.stalkProcess);
        this.registerBehavior(this.sculkProcess);
        this.registerBehavior(this.survivalProcess);
        this.registerBehavior(this.shulkerProcess);
        this.registerBehavior(this.companionProcess);
        this.registerBehavior(this.jobManager);
        this.registerBehavior(this.botControl);
        this.registerBehavior(this.network);
        this.registerBehavior(this.flight);
        this.registerBehavior(this.autonomy);
        this.registerBehavior(this.operations);
        this.registerBehavior(this.combat);
        this.registerBehavior(this.clutch);
        this.registerBehavior(new UpdateBehavior(this));
        this.registerBehavior(new SessionBehavior(this));
    }

    public void registerBehavior(IBehavior behavior) {
        this.gameEventHandler.registerEventListener(behavior);
    }

    public hivemind.utils.reliability.ActionArbiter getActions(){return actions;}
    public ReliabilityProcess getReliability(){return reliability;}

    public <T extends IBehavior> T registerBehavior(Function<HiveMind, T> constructor) {
        final T behavior = constructor.apply(this);
        this.registerBehavior(behavior);
        return behavior;
    }

    public <T extends IHiveMindProcess> T registerProcess(Function<HiveMind, T> constructor) {
        final T behavior = constructor.apply(this);
        this.pathingControlManager.registerProcess(behavior);
        return behavior;
    }

    @Override
    public PathingControlManager getPathingControlManager() {
        return this.pathingControlManager;
    }

    @Override
    public InputOverrideHandler getInputOverrideHandler() {
        return this.inputOverrideHandler;
    }

    @Override
    public CustomGoalProcess getCustomGoalProcess() {
        return this.customGoalProcess;
    }

    @Override
    public GetToBlockProcess getGetToBlockProcess() {
        return this.getToBlockProcess;
    }

    @Override
    public IPlayerContext getPlayerContext() {
        return this.playerContext;
    }

    @Override
    public FollowProcess getFollowProcess() {
        return this.followProcess;
    }

    @Override
    public BuilderProcess getBuilderProcess() {
        return this.builderProcess;
    }

    public InventoryBehavior getInventoryBehavior() {
        return this.inventoryBehavior;
    }

    @Override
    public LookBehavior getLookBehavior() {
        return this.lookBehavior;
    }

    @Override
    public ExploreProcess getExploreProcess() {
        return this.exploreProcess;
    }

    @Override
    public MineProcess getMineProcess() {
        return this.mineProcess;
    }

    public DepositProcess getDepositProcess() {
        return this.depositProcess;
    }

    public PortalTravelProcess getPortalTravelProcess() {
        return this.portalTravelProcess;
    }

    public ConflictProcess getConflictProcess() { return this.conflictProcess; }

    public SculkProcess getSculkProcess() { return this.sculkProcess; }
    public SurvivalProcess getSurvivalProcess() { return this.survivalProcess; }
    public CompanionProcess getCompanionProcess() { return this.companionProcess; }
    public WorkstationProcess getWorkstationProcess() { return this.workstationProcess; }
    public RepairProcess getRepairProcess() { return this.repairProcess; }
    public hivemind.utils.network.ClientRole getClientRole() { return clientRole; }
    public boolean isPlayerClient() { return clientRole != null && clientRole.player(); }
    public OperationsProcess getOperations(){return operations;}
    public ScoutProcess getScoutProcess() { return scoutProcess; }
    public JobManager getJobManager() { return this.jobManager; }
    public BotControlBehavior getBotControl() { return this.botControl; }
    public RoamProcess getRoamProcess(){return roamProcess;}
    public NetworkBehavior getNetwork() { return this.network; }
    public AutonomyBehavior getAutonomy(){return autonomy;}
    public FlightBehavior getFlight(){return flight;}
    public EntityAwareness getAwareness(){return awareness;}
    public CombatBehavior getCombat(){return combat;}
    public long clientTick(){return gameEventHandler.clientTick();}
    public BucketClutchBehavior getClutch(){return clutch;}
    public ShulkerSupplyProcess getShulkerProcess(){return shulkerProcess;}

    public StalkProcess getStalkProcess() { return this.stalkProcess; }

    @Override
    public FarmProcess getFarmProcess() {
        return this.farmProcess;
    }

    public InventoryPauserProcess getInventoryPauserProcess() {
        return this.inventoryPauserProcess;
    }

    @Override
    public PathingBehavior getPathingBehavior() {
        return this.pathingBehavior;
    }

    @Override
    public SelectionManager getSelectionManager() {
        return selectionManager;
    }

    @Override
    public WorldProvider getWorldProvider() {
        return this.worldProvider;
    }

    @Override
    public IEventBus getGameEventHandler() {
        return this.gameEventHandler;
    }

    @Override
    public CommandManager getCommandManager() {
        return this.commandManager;
    }

    @Override
    public IElytraProcess getElytraProcess() {
        return this.elytraProcess;
    }

    @Override
    public void openClick() {
        new Thread(() -> {
            try {
                Thread.sleep(100);
                mc.execute(() -> mc.gui.setScreen(new GuiClick()));
            } catch (Exception ignored) {}
        }).start();
    }

    public Path getDirectory() {
        return this.directory;
    }

    public static Settings settings() {
        return HiveMindAPI.getSettings();
    }

    public static Executor getExecutor() {
        return threadPool;
    }
}
