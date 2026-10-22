/* This file is part of Baritone, distributed under the LGPL-3.0 license. */
package baritone.utils.social;

/** Count client ticks between actual actions, rather than adding a delay at every state transition. */
public final class CombatCadence {
    private long last=Long.MIN_VALUE,next=Long.MIN_VALUE;
    public boolean ready(long tick){return tick>=next&&tick!=last;}
    public boolean acted(long tick){return last==tick;}
    public boolean claim(long tick,int configuredDelay){
        if(!ready(tick))return false;
        last=tick;next=tick+Math.max(1,Math.min(20,configuredDelay));return true;
    }
    public void reset(){last=next=Long.MIN_VALUE;}
}
