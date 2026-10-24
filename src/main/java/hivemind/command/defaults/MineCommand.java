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

import hivemind.api.HiveMindAPI;
import hivemind.HiveMind;
import hivemind.api.IHiveMind;
import hivemind.api.command.Command;
import hivemind.api.command.argument.IArgConsumer;
import hivemind.api.command.datatypes.ForBlockOptionalMeta;
import hivemind.api.command.exception.CommandException;
import hivemind.api.utils.BlockOptionalMeta;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class MineCommand extends Command {

    public MineCommand(IHiveMind hivemind) {
        super(hivemind, "mine");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        int quantity = args.getAsOrDefault(Integer.class, 0);
        args.requireMin(1);
        List<BlockOptionalMeta> boms = new ArrayList<>();
        while (args.hasAny()) {
            boms.add(consumeTarget(args));
        }
        if(quantity>0){var bot=(HiveMind)hivemind;if(!bot.getJobManager().runningJobs())hivemind.getPathingBehavior().cancelEverything();long id=bot.getJobManager().submit(quantity,boms.stream().map(b->net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b.getBlock()).toString()).toList());logDirect("Queued mining job #"+id);return;}
        hivemind.getPathingBehavior().cancelEverything();
        HiveMindAPI.getProvider().getWorldScanner().repack(ctx);
        logDirect(String.format("Mining %s", boms.toString()));
        hivemind.getMineProcess().mine(quantity, boms.toArray(new BlockOptionalMeta[0]));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        args.getAsOrDefault(Integer.class, 0);
        while (args.has(2)) {
            consumeTarget(args);
        }
        String prefix = args.peekString().toLowerCase(java.util.Locale.ROOT);
        return Stream.concat(args.tabCompleteDatatype(ForBlockOptionalMeta.INSTANCE),
                "netherite".startsWith(prefix) ? Stream.of("netherite") : Stream.empty());
    }

    private BlockOptionalMeta consumeTarget(IArgConsumer args) throws CommandException {
        if (args.peekString().equalsIgnoreCase("netherite") || args.peekString().equalsIgnoreCase("minecraft:netherite")) {
            args.getString();
            return new BlockOptionalMeta(Blocks.ANCIENT_DEBRIS);
        }
        return args.getDatatypeFor(ForBlockOptionalMeta.INSTANCE);
    }

    @Override
    public String getShortDesc() {
        return "Mine some blocks";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The mine command allows you to tell HiveMind to search for and mine individual blocks.",
                "",
                "The specified blocks can be ores, or any other block.",
                "",
                "Also see the legitMine settings (see #set l legitMine).",
                "",
                "Usage:",
                "> mine diamond_ore - Mines all diamonds it can find.",
                "> mine 32 netherite - Travels to the Nether and mines 32 ancient debris.",
                "Save an active Nether portal with portal set; a saved deposit chest can be in the Overworld."
        );
    }
}
