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

package hivemind.api.command.exception;

import hivemind.api.command.ICommand;
import hivemind.api.command.argument.ICommandArgument;

import java.util.List;

import static hivemind.api.utils.Helper.HELPER;

public class CommandNotFoundException extends CommandException {

    public final String command;

    public CommandNotFoundException(String command) {
        super(String.format("Command not found: %s", command));
        this.command = command;
    }

    @Override
    public void handle(ICommand command, List<ICommandArgument> args) {
        HELPER.logDirect(getMessage());
    }
}
