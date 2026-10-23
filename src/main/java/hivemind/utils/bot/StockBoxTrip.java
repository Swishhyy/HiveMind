/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;
import java.util.*;
/** Bounded, confirmed chest withdrawals; unpacked contents are never credited as loose reserves prematurely. */
public final class StockBoxTrip {
    public enum Result { PROGRESS, NO_PROGRESS, RECOVERY_REQUIRED }
    private int borrowed;
    private Map<String,Integer> before=Map.of();
    private boolean awaiting;
    public boolean canBorrow(int emptySlots){return !awaiting&&borrowed<8&&emptySlots>=2;}
    public void confirmedWithdrawal(int emptySlotsBefore){
        if(!canBorrow(emptySlotsBefore))throw new IllegalStateException("No room or box budget for another storage shulker");borrowed++;
    }
    public void beginUnpack(Map<String,Integer> counts){if(awaiting)throw new IllegalStateException("Already unpacking storage supplies");before=Map.copyOf(counts);awaiting=true;}
    public Result finishUnpack(Map<String,Integer> counts,boolean recoveryPending){
        if(!awaiting)throw new IllegalStateException("Storage unpacking was not started");awaiting=false;
        if(recoveryPending)return Result.RECOVERY_REQUIRED;
        return before.entrySet().stream().anyMatch(e->counts.getOrDefault(e.getKey(),0)>e.getValue())?Result.PROGRESS:Result.NO_PROGRESS;
    }
    public int borrowed(){return borrowed;}
    public void reset(){borrowed=0;before=Map.of();awaiting=false;}
}
