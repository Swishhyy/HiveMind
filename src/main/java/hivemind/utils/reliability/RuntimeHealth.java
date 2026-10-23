/* HiveMind, LGPL-3.0. */
package hivemind.utils.reliability;

import java.util.*;

/** Tick and server confirmation observations have different clocks; a slow server is not a frozen client. */
public final class RuntimeHealth {
    private long lastTick,lastServer,lastProgress,windowStart;
    private double tickMillis=50,confirmMillis=250;
    private int corrections;
    private long ticks,recoveries,confirmedTransfers;
    public void tick(long now){if(lastTick>0){long delta=now-lastTick;if(delta>=0&&delta<10000)tickMillis=tickMillis*.95+delta*.05;}lastTick=now;ticks++;if(now-windowStart>30000){windowStart=now;corrections=0;}}
    public void server(long now){lastServer=now;}
    public void confirmation(long started,long now){server(now);if(started>0&&now>=started)confirmMillis=.8*confirmMillis+.2*Math.min(10000,now-started);confirmedTransfers++;}
    public void correction(long now){server(now);corrections++;}
    public void progress(long now){lastProgress=now;}
    public void recovery(){recoveries++;}
    public int interactionTicks(){return (int)Math.max(5,Math.min(40,Math.ceil(confirmMillis/50)+3));}
    public long timeout(long normal){return Math.min(normal*4,Math.max(normal,(long)(normal*Math.max(tickMillis/50,confirmMillis/500))));}
    public boolean poorFlightConditions(){return corrections>=4||tickMillis>150;}
    public Map<String,String> snapshot(long now){Map<String,String> m=new LinkedHashMap<>();m.put("tickMs",""+Math.round(tickMillis));m.put("confirmMs",""+Math.round(confirmMillis));m.put("corrections",""+corrections);m.put("lastProgress",lastProgress==0?"unknown":""+Math.max(0,now-lastProgress));m.put("serverAge",lastServer==0?"unknown":""+Math.max(0,now-lastServer));m.put("ticks",""+ticks);m.put("recoveries",""+recoveries);m.put("transfers",""+confirmedTransfers);return Map.copyOf(m);}
}
