/* HiveMind, LGPL-3.0. */
package hivemind.utils.update;

import java.nio.file.Path;
public final class UpdateRuntime {
    private static volatile UpdateService service;
    private UpdateRuntime(){}
    public static synchronized void initialize(Path instance,Path artifact){if(service==null)service=new UpdateService(instance,artifact,UpdateService.installedVersion());}
    public static UpdateService service(){if(service==null)throw new IllegalStateException("HiveMind updater is not initialized by Fabric");return service;}
    public static boolean ready(){return service!=null;}
}
