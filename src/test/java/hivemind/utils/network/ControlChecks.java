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

import hivemind.utils.mining.WorldRoutes.Endpoint;
import hivemind.utils.network.NetworkProtocol.*;
import hivemind.utils.network.ControlPackets.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/** Cross-client control routing, durable global/individual policies, and actual authenticated HTTP submissions. */
public final class ControlChecks {
    private static final String KEY="control-test-key-with-enough-entropy",SCOPE=NetworkProtocol.scope("control-test-server");
    private static final Identity HOST=who("Host"),A=who("ScoutA"),B=who("ScoutB");
    private static Identity who(String name){return new Identity(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)),name,SCOPE);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void await(BooleanSupplier condition,String message)throws Exception{
        long deadline=System.nanoTime()+10_000_000_000L;
        while(!condition.getAsBoolean()){if(System.nanoTime()>deadline)throw new AssertionError(message);Thread.sleep(10);}
    }
    private static Request request(UUID target,Action action,String key,String value,long now){return new Request(A.id(),A.name(),UUID.randomUUID(),target,action,key,value,now);}
    private static State state(Identity bot,long now){
        return new State(bot.id(),bot.name(),"Idle",20,20,new Endpoint("minecraft:overworld",0,64,0),Map.of(),Map.of("stalkDistance","40"),Map.of(A.id(),A.name()),0,now);
    }
    public static void main(String[] args)throws Exception{runAll();System.out.println("Control checks passed: automatic keys, persisted defaults/overrides, trust, command deduplication, host-only dispatch, receipt delivery, HTTP auth/mutations, no command history replay");}
    public static void runAll()throws Exception{retiredCapabilityPolicies();keysAndPolicies();packetBounds();liveRouting();httpActions();}
    private static void retiredCapabilityPolicies()throws Exception{
        Path root=Files.createTempDirectory("hivemind-capability-migration-");
        try{
            List<String> retired=List.of("allowBreak","allowBreakAnyway","allowPlace","allowInventory","botUseShulkers");
            Properties saved=new Properties();saved.setProperty("version","123");
            for(String name:retired){saved.setProperty("global.setting."+name,"false");saved.setProperty("bot."+A.id()+".setting."+name,"false");}
            saved.setProperty("global.setting.stalkDistance","48");saved.setProperty("bot."+A.id()+".setting.stalkDistance","64");
            saved.setProperty("global.trust."+B.id(),"true");
            Path file=root.resolve("bot-network-controls.properties");hivemind.utils.bot.AtomicProperties.write(file,saved);
            ControlRules rules=new ControlRules(root);
            check(rules.version()>123,"Capability migration must advance policy revision");
            check(rules.settings(A.id()).equals(Map.of("stalkDistance","64"))&&rules.settings(B.id()).equals(Map.of("stalkDistance","48")),"Old false switches must not affect valid global or individual settings");
            check(rules.trust(A.id()).get(B.id()),"Migration must preserve trust");
            Properties disk=hivemind.utils.bot.AtomicProperties.read(file);
            for(String name:retired){
                check(!disk.containsKey("global.setting."+name)&&!disk.containsKey("bot."+A.id()+".setting."+name),"Retired switch persisted after migration: "+name);
                check(NetworkSettings.all().stream().noneMatch(spec->spec.name().equals(name)),"Retired switch still appears in the dashboard");
                try{rules.change(request(null,Action.SETTING,name,"false",System.currentTimeMillis()));throw new AssertionError("Retired switch could be recreated: "+name);}catch(IllegalArgumentException expected){}
            }
            check(new ControlRules(root).version()==rules.version(),"Migration should persist once and survive a restart");
            Map<String,String> legacy=new HashMap<>();retired.forEach(name->legacy.put(name,"false"));legacy.put("stalkDistance","56");
            check(NetworkSettings.normalize(legacy).equals(Map.of("stalkDistance","56")),"Older client policies must ignore retired false switches while applying valid settings");
            try{NetworkSettings.normalize(Map.of("unknownTypo","false"));throw new AssertionError("Unrelated invalid settings were silently ignored");}catch(IllegalArgumentException expected){}
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void keysAndPolicies()throws Exception{
        Path root=Files.createTempDirectory("hivemind-controls-");
        try{
            String generated=new ClientNetworkKey(root.resolve("client")).key();
            check(generated.equals(new ClientNetworkKey(root.resolve("client")).key()),"Client key survives restart without a command");
            check(!generated.equals(new ClientNetworkKey(root.resolve("other-client")).key()),"Each client receives its own random key");
            NetworkConfig config=new NetworkConfig(root.resolve("world"),generated);
            check(config.options().key().equals(generated),"A new server config automatically uses the client key");
            config.save(config.options().mode(NetworkConfig.Mode.HOST,"127.0.0.1",28766));
            check(new NetworkConfig(root.resolve("world"),NetworkProtocol.newKey()).options().key().equals(generated),"Existing configured keys are never replaced on launch");
            ControlRules rules=new ControlRules(root);long now=System.currentTimeMillis();
            rules.change(request(null,Action.SETTING,"stalkDistance","48",now));
            rules.change(request(A.id(),Action.SETTING,"stalkDistance","64",now));
            check(rules.settings(A.id()).get("stalkDistance").equals("64")&&rules.settings(B.id()).get("stalkDistance").equals("48"),"Individual choices override group defaults");
            rules.change(request(null,Action.SETTING,"stalkDistance","40",now));
            check(rules.settings(A.id()).get("stalkDistance").equals("40"),"Apply-to-all deliberately replaces individual overrides for the same setting");
            UUID owner=who("Owner").id();
            rules.change(request(null,Action.TRUST,owner.toString(),"true",now));
            rules.change(request(A.id(),Action.TRUST,owner.toString(),"false",now));
            check(!rules.trust(A.id()).get(owner)&&rules.trust(B.id()).get(owner),"Trust can differ for individual bots");
            rules.change(request(null,Action.TRUST,owner.toString(),"false",now));
            check(!rules.trust(A.id()).get(owner)&&!rules.trust(B.id()).get(owner),"Global revocation clears trust overrides");
            rules.change(request(B.id(),Action.SETTING,"killCombatStyle","ANCHOR",now));
            ControlRules loaded=new ControlRules(root);
            check(loaded.settings(B.id()).get("killCombatStyle").equals("anchor")&&loaded.settings(who("FutureBot").id()).get("stalkDistance").equals("40"),"Defaults and individual settings survive restart and cover future bots");
            long version=rules.version();String saved=Files.readString(root.resolve("bot-network-controls.properties"));
            for(Request bad:List.of(request(null,Action.SETTING,"logger","anything",now),request(null,Action.SETTING,"stalkDistance","999999",now),request(null,Action.SETTING,"botSurvival","maybe",now),request(null,Action.TRUST,owner.toString(),"perhaps",now))){
                try{rules.change(bad);throw new AssertionError("Invalid policy accepted");}catch(IllegalArgumentException expected){}
            }
            check(rules.version()==version&&Files.readString(root.resolve("bot-network-controls.properties")).equals(saved),"Rejected values never partially change persistent rules");
            var coordinator=new ControlRules.Coordinator(rules,HOST);
            Request command=request(A.id(),Action.COMMAND,"","#mine 64 netherite",now);
            List<Packet> dispatch=coordinator.handle(command,List.of(HOST.id(),A.id(),B.id()),now);
            check(dispatch.size()==1&&dispatch.get(0) instanceof Execute execute&&execute.target().equals(A.id())&&execute.command().equals("mine 64 netherite"),"A quick command keeps its selected target and normal command syntax");
            String storageCommand=PlayerBotOrders.chest("add",new hivemind.utils.mining.WorldRoutes.Endpoint("minecraft:the_nether",-30,65,70),"supplies");
            Request configure=request(B.id(),Action.COMMAND,"",storageCommand,now);var configured=coordinator.handle(configure,List.of(A.id(),B.id()),now);
            check(configured.size()==1&&configured.get(0) instanceof Execute edit&&edit.target().equals(B.id())&&edit.requester().equals(A.id())&&edit.command().equals(storageCommand),"Remote storage edits must preserve the bot target, pinned location and player receipt recipient");
            check(coordinator.handle(configure,List.of(B.id()),now).isEmpty(),"Storage setup requests must be deduplicated like other commands");
            check(coordinator.handle(command,List.of(A.id()),now).isEmpty(),"A repeated request ID cannot run a command twice");
            check(coordinator.handle(request(who("Offline").id(),Action.COMMAND,"","stop",now),List.of(A.id()),now).get(0) instanceof Receipt receipt&&receipt.success()&&receipt.message().contains("Saved"),"Offline stop saves a hold without claiming execution");
            check(new ControlRules(root).settings(who("Offline").id()).get("botFleetHold").equals("true"),"An offline bot remains held when it reconnects");
            check(coordinator.handle(request(who("Offline").id(),Action.COMMAND,"","mine 64 diamond_ore",now),List.of(A.id()),now).get(0) instanceof Receipt receipt&&!receipt.success(),"Offline work is rejected rather than replayed later");
            check(coordinator.handle(request(null,Action.COMMAND,"","network key new",now),List.of(A.id()),now).get(0) instanceof Receipt receipt&&!receipt.success(),"The quick-command transport cannot edit keys or execute arbitrary commands");
            Request future=request(who("FutureBot").id(),Action.SETTING,"stalkDistance","56",now);
            check(coordinator.handle(future,List.of(A.id()),now).get(0) instanceof Receipt receipt&&receipt.message().contains("waiting"),"Offline settings are saved without claiming they were applied");
            check(new ControlRules(root).settings(who("FutureBot").id()).get("stalkDistance").equals("56"),"An offline bot receives its saved individual choice later");
            Request global=request(null,Action.SETTING,"botSurvival","false",now);
            List<Packet> policies=coordinator.handle(global,List.of(HOST.id(),A.id(),B.id()),now);
            check(policies.size()==3&&policies.stream().allMatch(p->p instanceof Policy policy&&policy.settings().get("botSurvival").equals("false")),"Global changes create a policy for every online bot");
            check(!Files.readString(root.resolve("bot-network-controls.properties")).contains("mine 64 netherite"),"One-shot commands never enter durable policies");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void packetBounds()throws Exception{
        long now=System.currentTimeMillis();UUID id=UUID.randomUUID();
        List<Packet> packets=List.of(state(A,now),request(null,Action.COMMAND,"","stop",now),
                new Execute(HOST.id(),HOST.name(),id,A.id(),B.id(),"pause",now),
                new Policy(HOST.id(),HOST.name(),id,A.id(),B.id(),1,Map.of("stalkDistance","48"),Map.of(A.id(),true),now),
                new Receipt(B.id(),B.name(),id,A.id(),true,"Applied",now),
                new Defaults(HOST.id(),HOST.name(),1,Map.of("botSurvival","true"),Map.of(A.id(),false),now));
        for(Packet packet:packets){
            check(NetworkProtocol.decode(NetworkProtocol.encode(packet)).equals(packet),"Control packets round-trip with their original scope and provenance");
            check(!new NetworkAtlas().accept(packet,now),"Live control packets never enter the exploration/history cache");
        }
        check(!new Execute(HOST.id(),HOST.name(),id,A.id(),null,"stop",now-120000).valid(now),"Delayed command dispatch expires");
        try{WebControlAction.parse("action=command&target=all&value=stop&value=kill".getBytes(StandardCharsets.UTF_8),now);throw new AssertionError("Duplicate form values accepted");}catch(IllegalArgumentException expected){}
        try{NetworkProtocol.decode(new byte[]{99});throw new AssertionError("Unknown control packet accepted");}catch(IOException expected){}
    }
    private static void liveRouting()throws Exception{
        try(BotNetwork hub=BotNetwork.host(HOST,KEY,"127.0.0.1",0)){
            await(()->hub.boundPort()>0,"Control relay did not start");
            try(BotNetwork one=BotNetwork.join(A,KEY,"127.0.0.1",hub.boundPort());BotNetwork two=BotNetwork.join(B,KEY,"127.0.0.1",hub.boundPort())){
                await(()->hub.peers().size()==2,"Control peers did not connect");
                long now=System.currentTimeMillis();one.publish(state(A,now));two.publish(state(B,now));
                await(()->hub.states().size()==2,"Live per-bot state did not reach the host");hub.drain(128);one.drain(128);two.drain(128);
                Request request=request(A.id(),Action.COMMAND,"","pause",now);one.publish(request);List<Message> inbox=new ArrayList<>();
                await(()->{inbox.addAll(hub.drain(128));return inbox.contains(request);},"Client request did not reach host validation");
                check(two.drain(128).stream().noneMatch(message->message instanceof Request),"Unvalidated requests are never forwarded to other bots");
                Execute execute=new Execute(HOST.id(),HOST.name(),request.id(),A.id(),A.id(),"pause",now);hub.publish(execute);
                List<Message> received=new ArrayList<>();await(()->{received.addAll(one.drain(128));return received.contains(execute);},"Host dispatch did not reach the selected client");
                Receipt receipt=new Receipt(A.id(),A.name(),request.id(),A.id(),true,"Paused",now);one.publish(receipt);
                await(()->{inbox.addAll(hub.drain(128));return inbox.contains(receipt);},"Bot acknowledgement did not return to the host");
                check(!one.publish(new Execute(A.id(),A.name(),UUID.randomUUID(),A.id(),null,"stop",now)),"A client cannot impersonate host dispatch through the publisher");
                Defaults defaults=new Defaults(HOST.id(),HOST.name(),1,Map.of("stalkDistance","48"),Map.of(),now);hub.publish(defaults);
                try(BotNetwork late=BotNetwork.join(who("LateBot"),KEY,"127.0.0.1",hub.boundPort())){
                    List<Message> replay=new ArrayList<>();await(()->{replay.addAll(late.drain(128));return replay.contains(defaults);},"Late peer did not receive shared default metadata");
                    check(replay.stream().noneMatch(message->message instanceof Execute||message instanceof Request||message instanceof Policy),"Late join never replays old commands or old policy applications");
                }
                try(Socket socket=new Socket("127.0.0.1",hub.boundPort())){
                    socket.setSoTimeout(3000);Identity forger=who("Forger");
                    Channel channel=NetworkProtocol.connect(socket.getInputStream(),socket.getOutputStream(),forger,KEY);
                    channel.write(new Execute(forger.id(),forger.name(),UUID.randomUUID(),forger.id(),null,"stop",now));
                    try{while(true)channel.read();}catch(SocketTimeoutException error){throw new AssertionError("Client dispatch was not rejected",error);}catch(IOException expected){}
                }
            }
        }
    }
    private record Response(int status,String raw){}
    private static Response http(LocalNetworkViewer page,String path,String body,boolean auth,String origin)throws Exception{
        try(Socket socket=new Socket("127.0.0.1",page.port())){
            socket.setSoTimeout(3000);byte[] bytes=body==null?new byte[0]:body.getBytes(StandardCharsets.UTF_8);
            String headers=(body==null?"GET":"POST")+" "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+page.port()+"\r\nConnection: close\r\n"
                    +(auth?"Authorization: Bearer "+page.url().split("#",2)[1]+"\r\n":"")+(origin==null?"":"Origin: "+origin+"\r\n")
                    +(body==null?"":"Content-Type: application/x-www-form-urlencoded\r\nContent-Length: "+bytes.length+"\r\n")+"\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(bytes);
            String raw=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);return new Response(Integer.parseInt(raw.split(" ")[1]),raw);
        }
    }
    private static void httpActions()throws Exception{
        List<WebControlAction> queued=new CopyOnWriteArrayList<>();
        try(LocalNetworkViewer page=new LocalNetworkViewer(0,action->{queued.add(action);return true;})){
            page.controls("{\"ready\":true,\"bots\":[]}");
            String form="target=all&action=command&key=&value=%23stop";
            check(http(page,"/api/control",form,false,null).status()==401,"Mutation API requires the page token");
            check(http(page,"/api/control",form,true,"https://other.example").status()==403,"Cross-origin mutation is rejected");
            check(queued.isEmpty(),"Rejected HTTP requests never reach the game queue");
            Response response=http(page,"/api/control",form,true,null);
            check(response.status()==202&&queued.size()==1&&queued.get(0).value().equals("#stop")&&queued.get(0).target()==null,"A valid form is queued without touching Minecraft on the HTTP thread");
            check(response.raw().contains(queued.get(0).id().toString()),"The browser receives the request ID used for bot confirmations");
            check(http(page,"/api/control",null,true,null).raw().contains("\"ready\":true"),"The control page reads its live bot state");
            check(http(page,"/api/control","target=all&action=unknown&value=stop",true,null).status()==400,"Unknown control actions fail clearly");
            check(http(page,"/api/control",form+"&value=kill",true,null).status()==400,"Duplicate parameters cannot change the requested command");
            check(http(page,"/api/control",form+"x".repeat(8193),true,null).status()==413,"Oversized HTTP control bodies are bounded");
        }
        try(LocalNetworkViewer busy=new LocalNetworkViewer(0,action->false)){
            check(http(busy,"/api/control","target=all&action=command&value=stop",true,null).status()==429,"A full game queue is reported without pretending an action was accepted");
        }
    }
}
