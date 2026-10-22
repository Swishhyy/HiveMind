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

package hivemind.command.defaults;

import hivemind.HiveMind;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.exception.CommandException;
import hivemind.api.command.exception.CommandInvalidStateException;
import hivemind.api.command.helpers.TabCompleteHelper;
import java.util.*;
import java.util.stream.Stream;

public final class JobCommand extends Command {
    public JobCommand(IHiveMind bot) { super(bot, "job", "status"); }
    @Override public void execute(String label, IArgConsumer args) throws CommandException {
        var jobs = ((HiveMind) hivemind).getJobManager();
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status";
        try {
            switch (action) {
                case "mine" -> {
                    args.requireMin(2); int quantity = args.getAs(Integer.class); List<String> blocks = new ArrayList<>();
                    while (args.hasAny()) blocks.add(args.getString());
                    logDirect("Queued mining job #" + jobs.submit(quantity, blocks));
                }
                case "status" -> { args.requireMax(0); logDirect(jobs.status()); }
                case "list" -> {
                    args.requireMax(0); logDirect(jobs.status());
                    for (var job : jobs.jobs()) logDirect("#" + job.id() + " mine " + job.quantity() + " " + String.join(" ", job.blocks()) + " (confirmed deposits " + job.deposited() + ")");
                }
                case "pause" -> { args.requireMax(0); jobs.pauseJobs(); logDirect("Jobs paused"); }
                case "resume" -> { args.requireMax(0); jobs.resumeJobs(); logDirect("Jobs resumed"); }
                case "cancel", "clear" -> { args.requireMax(0); jobs.cancelJobs(); logDirect("Job queue cleared"); }
                case "remove" -> { args.requireMin(1); long id = args.getAs(Long.class); args.requireMax(0); jobs.remove(id); logDirect("Removed job #" + id); }
                default -> throw new IllegalArgumentException("Use job mine <quantity> <blocks...>, status, list, pause, resume, cancel, or remove <id>");
            }
        } catch (IllegalArgumentException | IllegalStateException e) { throw new CommandInvalidStateException(e.getMessage()); }
    }
    @Override public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return args.hasAtMostOne() ? new TabCompleteHelper().append("mine", "status", "list", "pause", "resume", "cancel", "remove").filterPrefix(args.getString()).stream() : Stream.empty();
    }
    @Override public String getShortDesc() { return "Queue mining jobs and inspect progress or interruptions"; }
    @Override public List<String> getLongDesc() { return List.of("job mine 32 netherite - Queue a finite mining job; repeat to add more jobs.",
            "job status/list/pause/resume/cancel/remove <id> - Manage this server/save's persistent queue.",
            "Saved jobs reload paused. Quantity includes carried target items and confirmed deposits.",
            "Storage, survival, conflict, and portal interruptions preserve the active mining request."); }
}
