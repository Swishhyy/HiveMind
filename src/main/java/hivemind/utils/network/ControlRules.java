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
package hivemind.utils.network;

import hivemind.utils.bot.AtomicProperties;
import hivemind.utils.bot.TrustedControl;
import hivemind.utils.network.ControlPackets.*;
import hivemind.utils.network.NetworkProtocol.Identity;
import java.nio.file.Path;
import java.util.*;

/** Host-owned durable defaults and individual overrides. Commands themselves are never saved or replayed. */
public final class ControlRules {
    private final Path file;
    private Properties values;
    private long version;
    public ControlRules(Path root) {
        file=root.resolve("bot-network-controls.properties");values=AtomicProperties.read(file);
        version=Long.parseLong(values.getProperty("version","0"));
        if(version<0||version==Long.MAX_VALUE||values.size()>8192)throw new IllegalStateException("Invalid bot-network-controls.properties");
        Set<UUID> bots=new HashSet<>();boolean migrated=false;
        for(String key:values.stringPropertyNames()){
            if(key.equals("version"))continue;
            String suffix;
            if(key.startsWith("global."))suffix=key.substring(7);
            else if(key.startsWith("bot.")&&key.length()>41&&key.charAt(40)=='.'){bots.add(UUID.fromString(key.substring(4,40)));suffix=key.substring(41);}
            else throw new IllegalStateException("Invalid control rule");
            if(suffix.startsWith("setting.")&&NetworkSettings.retired(suffix.substring(8))){values.remove(key);migrated=true;continue;}
            String normalized=validate(suffix,values.getProperty(key));if(!normalized.equals(values.getProperty(key))){values.setProperty(key,normalized);migrated=true;}
        }
        if(bots.size()>64)throw new IllegalStateException("Too many individual bot policies");
        if(migrated){version=Math.max(System.currentTimeMillis(),version+1);values.setProperty("version",""+version);AtomicProperties.write(file,values);}
    }
    private static String validate(String key,String value) {
        if(key.startsWith("setting."))return NetworkSettings.spec(key.substring(8)).normalize(value);
        if(!key.startsWith("trust."))throw new IllegalArgumentException("Invalid policy entry");
        UUID.fromString(key.substring(6));
        if(!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException("Trust requires true or false");
        return value;
    }
    public long version(){return version;}
    public void change(Request request){
        String suffix=request.action()==Action.SETTING?"setting."+request.key():request.action()==Action.TRUST?"trust."+request.key():null;
        if(suffix==null)throw new IllegalArgumentException("Quick commands cannot be saved as policies");
        String value=validate(suffix,request.value());Properties next=new Properties();next.putAll(values);
        String prefix=request.target()==null?"global.":"bot."+request.target()+".";
        if(request.target()==null)next.keySet().removeIf(key->key.toString().startsWith("bot.")&&key.toString().endsWith("."+suffix));
        next.setProperty(prefix+suffix,value);
        long revision=Math.max(System.currentTimeMillis(),version+1);next.setProperty("version",""+revision);
        if(next.size()>8192)throw new IllegalArgumentException("Control policy storage limit exceeded");
        Set<String> bots=new HashSet<>();for(String key:next.stringPropertyNames())if(key.startsWith("bot."))bots.add(key.substring(4,40));
        if(bots.size()>64)throw new IllegalArgumentException("At most 64 individual bot policies can be saved");
        // Check merged bounds before committing so a connected client can always decode its policy.
        for(String bot:bots)checkSize(next,UUID.fromString(bot));
        checkSize(next,null);AtomicProperties.write(file,next);values=next;version=revision;
    }
    private void checkSize(Properties properties,UUID target){
        if(settings(properties,target).size()>128||trust(properties,target).size()>128)throw new IllegalArgumentException("A bot policy may contain at most 128 settings/trusted-player changes");
    }
    public Map<String,String> settings(UUID target){return settings(values,target);}
    private Map<String,String> settings(Properties properties,UUID target){
        Map<String,String> result=new LinkedHashMap<>();
        for(String prefix:target==null?List.of("global.setting."):List.of("global.setting.","bot."+target+".setting."))
            for(String key:properties.stringPropertyNames())if(key.startsWith(prefix))result.put(key.substring(prefix.length()),properties.getProperty(key));
        return Map.copyOf(result);
    }
    public Map<UUID,Boolean> trust(UUID target){return trust(values,target);}
    private Map<UUID,Boolean> trust(Properties properties,UUID target){
        Map<UUID,Boolean> result=new LinkedHashMap<>();
        for(String prefix:target==null?List.of("global.trust."):List.of("global.trust.","bot."+target+".trust."))
            for(String key:properties.stringPropertyNames())if(key.startsWith(prefix))result.put(UUID.fromString(key.substring(prefix.length())),Boolean.parseBoolean(properties.getProperty(key)));
        return Map.copyOf(result);
    }
    public Policy policy(Identity host,UUID id,UUID requester,UUID target,long now){
        return new Policy(host.id(),host.name(),id,requester,target,version,settings(target),trust(target),now);
    }
    public static String command(String value){
        String command=TrustedControl.command(value.startsWith("#")?value:"#"+value);
        if(command==null)throw new IllegalArgumentException("Use a supported bot command, such as stop, pause, resume, mine, deposit, stalk, kill, escort, gather, repair or job");
        return command;
    }
    public static final class Coordinator {
        private final ControlRules rules;
        private final Identity host;
        private final Map<UUID,Long> seen=new LinkedHashMap<>();
        public Coordinator(ControlRules rules,Identity host){this.rules=rules;this.host=host;}
        public List<Packet> handle(Request request,Collection<UUID> online,long now){
            if(!request.valid(now))return List.of(failure(request,"Request expired; send it again",now));
            seen.values().removeIf(time->now-time>60000);
            if(seen.containsKey(request.id()))return List.of();
            if(seen.size()>=1024)return List.of(failure(request,"Too many pending requests; try again shortly",now));
            seen.put(request.id(),now);
            try{
                if(request.action()==Action.COMMAND){
                    String command=command(request.value());
                    if(request.target()!=null&&!online.contains(request.target()))throw new IllegalArgumentException("Bot is offline; command was not run");
                    return List.of(new Execute(host.id(),host.name(),request.id(),request.reporter(),request.target(),command,now));
                }
                rules.change(request);
                List<Packet> result=new ArrayList<>();
                for(UUID bot:online)if(request.target()==null||request.target().equals(bot))result.add(rules.policy(host,request.id(),request.reporter(),bot,now));
                if(result.isEmpty())result.add(new Receipt(host.id(),host.name(),request.id(),request.reporter(),true,"Saved; waiting for the selected bot to connect",now));
                return result;
            }catch(RuntimeException error){return List.of(failure(request,error.getMessage(),now));}
        }
        private Receipt failure(Request request,String message,long now){return new Receipt(host.id(),host.name(),request.id(),request.reporter(),false,message,now);}
    }
}
