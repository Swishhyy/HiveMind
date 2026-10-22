/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;
import java.nio.file.Path;
public final class SessionRuntime {
    private static SessionState state;
    private SessionRuntime(){}
    public static synchronized SessionState get(Path instance){if(state==null)state=new SessionState(instance);return state;}
}
