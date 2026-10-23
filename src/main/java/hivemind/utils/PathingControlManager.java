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

package hivemind.utils;

import hivemind.HiveMind;
import hivemind.utils.reliability.ActionArbiter;
import hivemind.api.event.events.TickEvent;
import hivemind.api.event.listener.AbstractGameEventListener;
import hivemind.api.pathing.calc.IPathingControlManager;
import hivemind.api.pathing.goals.Goal;
import hivemind.api.process.IHiveMindProcess;
import hivemind.api.process.PathingCommand;
import hivemind.api.process.PathingCommandType;
import hivemind.behavior.PathingBehavior;
import hivemind.pathing.path.PathExecutor;
import net.minecraft.core.BlockPos;

import java.util.*;

public class PathingControlManager implements IPathingControlManager {

    private final HiveMind hivemind;
    private final HashSet<IHiveMindProcess> processes; // unGh
    private final List<IHiveMindProcess> active;
    private IHiveMindProcess inControlLastTick;
    private IHiveMindProcess inControlThisTick;
    private PathingCommand command;

    public PathingControlManager(HiveMind hivemind) {
        this.hivemind = hivemind;
        this.processes = new HashSet<>();
        this.active = new ArrayList<>();
        hivemind.getGameEventHandler().registerEventListener(new AbstractGameEventListener() { // needs to be after all behavior ticks
            @Override
            public void onTick(TickEvent event) {
                if (event.getType() == TickEvent.Type.IN) {
                    postTick();
                }
            }
        });
    }

    @Override
    public void registerProcess(IHiveMindProcess process) {
        process.onLostControl(); // make sure it's reset
        processes.add(process);
    }

    public void cancelEverything() { // called by PathingBehavior on TickEvent Type OUT
        cancelEverything(false);
    }

    /** Preserve only mining work during a bounded, connected, expected portal transfer. User stop never uses this. */
    public void cancelEverything(boolean preservePortalWork) {
        inControlLastTick = null;
        inControlThisTick = null;
        command = null;
        active.clear();
        for (IHiveMindProcess proc : processes) {
            if (preservePortalWork && (proc == hivemind.getMineProcess() || proc == hivemind.getDepositProcess()
                    || proc == hivemind.getPortalTravelProcess() || proc == hivemind.getSurvivalProcess()
                    || proc == hivemind.getJobManager() || proc == hivemind.getCompanionProcess()
                    || proc == hivemind.getWorkstationProcess() || proc == hivemind.getRepairProcess()
                    || proc == hivemind.getScoutProcess() || proc == hivemind.getOperations())) {
                continue;
            }
            proc.onLostControl();
            if (proc.isActive() && !proc.isTemporary()) { // it's okay only for a temporary thing (like combat pause) to maintain control even if you say to cancel
                throw new IllegalStateException(proc.displayName() + " stayed active after being cancelled");
            }
        }
    }

    @Override
    public Optional<IHiveMindProcess> mostRecentInControl() {
        return Optional.ofNullable(inControlThisTick);
    }

    @Override
    public Optional<PathingCommand> mostRecentCommand() {
        return Optional.ofNullable(command);
    }

    public void preTick() {
        inControlLastTick = inControlThisTick;
        inControlThisTick = null;
        PathingBehavior p = hivemind.getPathingBehavior();
        command = executeProcesses();
        if (command == null) {
            p.cancelSegmentIfSafe();
            p.secretInternalSetGoal(null);
            return;
        }
        if (!Objects.equals(inControlThisTick, inControlLastTick) && command.commandType != PathingCommandType.REQUEST_PAUSE && inControlLastTick != null && !inControlLastTick.isTemporary()) {
            // if control has changed from a real process to another real process, and the new process wants to do something
            p.cancelSegmentIfSafe();
            // get rid of the in progress stuff from the last process
        }
        switch (command.commandType) {
            case SET_GOAL_AND_PAUSE:
                p.secretInternalSetGoalAndPath(command);
            case REQUEST_PAUSE:
                p.requestPause();
                break;
            case CANCEL_AND_SET_GOAL:
                p.secretInternalSetGoal(command.goal);
                p.cancelSegmentIfSafe();
                break;
            case FORCE_REVALIDATE_GOAL_AND_PATH:
            case REVALIDATE_GOAL_AND_PATH:
                if (!p.isPathing() && !p.getInProgress().isPresent()) {
                    p.secretInternalSetGoalAndPath(command);
                }
                break;
            case SET_GOAL_AND_PATH:
                // now this i can do
                if (command.goal != null) {
                    p.secretInternalSetGoalAndPath(command);
                }
                break;
            default:
                throw new IllegalStateException("Unexpected command type " + command.commandType);
        }
    }

    private void postTick() {
        // if we did this in pretick, it would suck
        // we use the time between ticks as calculation time
        // therefore, we only cancel and recalculate after the tick for the current path has executed
        // "it would suck" means it would actually execute a path every other tick
        if (command == null) {
            return;
        }
        PathingBehavior p = hivemind.getPathingBehavior();
        switch (command.commandType) {
            case FORCE_REVALIDATE_GOAL_AND_PATH:
                if (command.goal == null || forceRevalidate(command.goal) || revalidateGoal(command.goal)) {
                    // pwnage
                    p.softCancelIfSafe();
                }
                p.secretInternalSetGoalAndPath(command);
                break;
            case REVALIDATE_GOAL_AND_PATH:
                if (HiveMind.settings().cancelOnGoalInvalidation.value && (command.goal == null || revalidateGoal(command.goal))) {
                    p.softCancelIfSafe();
                }
                p.secretInternalSetGoalAndPath(command);
                break;
            default:
        }
    }

    public boolean forceRevalidate(Goal newGoal) {
        PathExecutor current = hivemind.getPathingBehavior().getCurrent();
        if (current != null) {
            if (newGoal.isInGoal(current.getPath().getDest())) {
                return false;
            }
            return !newGoal.equals(current.getPath().getGoal());
        }
        return false;
    }

    public boolean revalidateGoal(Goal newGoal) {
        PathExecutor current = hivemind.getPathingBehavior().getCurrent();
        if (current != null) {
            Goal intended = current.getPath().getGoal();
            BlockPos end = current.getPath().getDest();
            if (intended.isInGoal(end) && !newGoal.isInGoal(end)) {
                // this path used to end in the goal
                // but the goal has changed, so there's no reason to continue...
                return true;
            }
        }
        return false;
    }


    public PathingCommand executeProcesses() {
        for (IHiveMindProcess process : processes) {
            if (process.isActive()) {
                if (!active.contains(process)) {
                    // put a newly active process at the very front of the queue
                    active.add(0, process);
                }
            } else {
                active.remove(process);
            }
        }
        // ties are broken by which was added to the beginning of the list first
        active.sort(Comparator.comparingDouble(IHiveMindProcess::priority).reversed());

        Iterator<IHiveMindProcess> iterator = active.iterator();
        while (iterator.hasNext()) {
            IHiveMindProcess proc = iterator.next();

            PathingCommand exec;
            var owner=ActionArbiter.owner(proc.getClass().getSimpleName());
            try(var scope=hivemind.getActions().scope(owner.name(),owner.priority())){
                exec = proc.onTick(Objects.equals(proc, inControlLastTick) && hivemind.getPathingBehavior().calcFailedLastTick(), hivemind.getPathingBehavior().isSafeToCancel());
            }
            if (exec == null) {
                if (proc.isActive()) {
                    throw new IllegalStateException(proc.displayName() + " actively returned null PathingCommand");
                }
                // no need to call onLostControl; they are reporting inactive.
            } else if (exec.commandType != PathingCommandType.DEFER) {
                inControlThisTick = proc;
                if (!proc.isTemporary()) {
                    iterator.forEachRemaining(IHiveMindProcess::onLostControl);
                }
                return exec;
            }
        }
        return null;
    }
}
