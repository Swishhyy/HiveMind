/* HiveMind, LGPL-3.0. */
package hivemind.controller;
import hivemind.utils.network.ControlPackets.State;
import java.util.*;
/** The controller owns reconnect timing and combat-log decisions; the client executes them. */
public final class SessionDecisions {
    private final Map<UUID,Long> next=new HashMap<>();
    public String decide(State state,long now){
        if(now<next.getOrDefault(state.reporter(),0L)||state.session().isEmpty())return null;
        Map<String,String> s=state.session(),settings=state.settings();boolean online=Boolean.parseBoolean(s.getOrDefault("connected","false"));
        if(online){
            if(Boolean.parseBoolean(settings.getOrDefault("botCombatLog","false"))&&state.health()>0&&state.health()<=number(settings,"botCombatLogHealth",8)
                &&number(s,"hostiles",0)>0&&now-number(s,"damageAt",0)<10000){
                long wait=Math.max(5000,Math.min(86400000,number(settings,"botCombatLogWaitMS",60000)));
                next.put(state.reporter(),now+10000);return "session logout "+Math.max(5,wait/1000);
            }
        }else{
            long hold=number(s,"holdUntil",0);
            if(!s.getOrDefault("server","").isBlank()&&hold!=Long.MAX_VALUE&&now>=hold&&now>=number(s,"nextAttempt",0)
                &&(Boolean.parseBoolean(settings.getOrDefault("botAutoReconnect","true"))||hold>0)){
                next.put(state.reporter(),now+Math.max(10000,Math.min(300000,number(settings,"botReconnectDelayMS",10000))));return "session reconnect";
            }
        }
        return null;
    }
    private static long number(Map<String,String> values,String key,long fallback){try{return Long.parseLong(values.getOrDefault(key,""));}catch(NumberFormatException e){return fallback;}}
}
