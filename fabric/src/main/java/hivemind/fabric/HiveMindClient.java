/* HiveMind, LGPL-3.0. */
package hivemind.fabric;
import hivemind.utils.update.UpdateRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
public final class HiveMindClient implements ClientModInitializer {
    @Override public void onInitializeClient(){
        var loader=FabricLoader.getInstance();Path instance=loader.getGameDir().toAbsolutePath().normalize();
        Path artifact=loader.getModContainer("hivemind").orElseThrow().getOrigin().getPaths().stream()
            .map(path->path.toAbsolutePath().normalize()).filter(path->path.getFileName().toString().endsWith(".jar")&&path.getParent().equals(instance.resolve("mods"))).findFirst().orElse(null);
        UpdateRuntime.initialize(instance,artifact);
    }
}
