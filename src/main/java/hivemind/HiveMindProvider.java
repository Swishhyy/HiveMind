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

import hivemind.api.IHiveMind;
import hivemind.api.IHiveMindProvider;
import hivemind.api.cache.IWorldScanner;
import hivemind.api.command.ICommandSystem;
import hivemind.api.schematic.ISchematicSystem;
import hivemind.cache.FasterWorldScanner;
import hivemind.command.CommandSystem;
import hivemind.command.ExampleHiveMindControl;
import hivemind.utils.schematic.SchematicSystem;
import net.minecraft.client.Minecraft;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author Brady
 * @since 9/29/2018
 */
public final class HiveMindProvider implements IHiveMindProvider {

    private final List<IHiveMind> all;
    private final List<IHiveMind> allView;

    public HiveMindProvider() {
        this.all = new CopyOnWriteArrayList<>();
        this.allView = Collections.unmodifiableList(this.all);

        // Setup chat control, just for the primary instance
        final HiveMind primary = (HiveMind) this.createHiveMind(Minecraft.getInstance());
        primary.registerBehavior(ExampleHiveMindControl::new);
    }

    @Override
    public IHiveMind getPrimaryHiveMind() {
        return this.all.get(0);
    }

    @Override
    public List<IHiveMind> getAllHiveMinds() {
        return this.allView;
    }

    @Override
    public synchronized IHiveMind createHiveMind(Minecraft minecraft) {
        IHiveMind hivemind = this.getHiveMindForMinecraft(minecraft);
        if (hivemind == null) {
            this.all.add(hivemind = new HiveMind(minecraft));
        }
        return hivemind;
    }

    @Override
    public synchronized boolean destroyHiveMind(IHiveMind hivemind) {
        return hivemind != this.getPrimaryHiveMind() && this.all.remove(hivemind);
    }

    @Override
    public IWorldScanner getWorldScanner() {
        return FasterWorldScanner.INSTANCE;
    }

    @Override
    public ICommandSystem getCommandSystem() {
        return CommandSystem.INSTANCE;
    }

    @Override
    public ISchematicSystem getSchematicSystem() {
        return SchematicSystem.INSTANCE;
    }
}
