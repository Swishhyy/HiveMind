/* HiveMind, LGPL-3.0. */
package hivemind.utils.reliability;
import java.util.function.LongSupplier;
/** A time ceiling and a work quota bound background scans even when the clock is coarse. */
public final class WorkBudget {
    private final LongSupplier clock;
    private final long deadline;
    private int remaining;
    public WorkBudget(long nanos,int items){this(System::nanoTime,nanos,items);}
    public WorkBudget(LongSupplier clock,long nanos,int items){this.clock=clock;deadline=clock.getAsLong()+Math.max(1,nanos);remaining=Math.max(0,items);}
    public boolean take(){if(remaining<=0||clock.getAsLong()>=deadline)return false;remaining--;return true;}
    public int remaining(){return remaining;}
}
