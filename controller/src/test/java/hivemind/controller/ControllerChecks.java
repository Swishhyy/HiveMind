/* HiveMind, LGPL-3.0. */
package hivemind.controller;
import hivemind.utils.bot.SessionState;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import hivemind.utils.network.*;
import hivemind.utils.network.ControlPackets.*;
import hivemind.utils.network.NetworkProtocol.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
public final class ControllerChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void until(BooleanSupplier condition,String message)throws Exception{for(int i=0;i<400;i++){if(condition.getAsBoolean())return;Thread.sleep(10);}throw new AssertionError(message);}
    private static final HttpClient HTTP=HttpClient.newHttpClient();
    private static HttpResponse<String> request(URI page,String method,String body)throws Exception{
        URI uri=URI.create(page.getScheme()+"://"+page.getRawAuthority()+"/api/control");
        var request=HttpRequest.newBuilder(uri).header("Authorization","Bearer "+page.getFragment());
        if(method.equals("POST"))request.header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));else request.GET();
        return HTTP.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static State state(Identity bot,long now,Map<String,String> settings,Map<String,String> session){return new State(bot.id(),bot.name(),"Testing",6,20,new Endpoint("minecraft:overworld",30,64,40),Map.of(),settings,Map.of(),0,now,Map.of("version","0.1.0.0"),session);}
    public static void main(String[] args)throws Exception{
        Path data=Files.createTempDirectory("hivemind-controller");long now=System.currentTimeMillis();
        check(NetworkSettings.all().size()<=128,"Settings exceed protocol bounds");
        SessionState session=new SessionState(data);session.observe(true,"example.test","Example",now,10000);session.command("session logout 60",now);check(session.takeDisconnect(),"Logout not requested");session.observe(false,null,null,now+1,10000);
        check(!session.shouldConnect(now+59000,false)&&session.shouldConnect(now+60001,false),"Reconnect did not respect cooldown");
        session.command("session logout",now);check(!new SessionState(data).shouldConnect(now+86400000,false),"Manual offline intent did not survive restart");
        session.command("session reconnect",now+1);check(session.shouldConnect(now+1,true),"Explicit reconnect could not run through controller");session.attempted(now,5000);check(!session.shouldConnect(now+1000,false),"Reconnect backoff missing");
        Identity a=new Identity(UUID.randomUUID(),"BotA",NetworkProtocol.scope("server-label:test")),b=new Identity(UUID.randomUUID(),"BotB",a.scope());
        SessionDecisions decisions=new SessionDecisions();Map<String,String> dangerous=Map.of("connected","true","server","example.test","damageAt",""+now,"hostiles","1");
        check(decisions.decide(state(a,now,Map.of("botCombatLog","false"),dangerous),now)==null,"Combat logging was enabled implicitly");
        check("session logout 60".equals(decisions.decide(state(a,now,Map.of("botCombatLog","true"),dangerous),now)),"Controller did not combat log endangered bot");
        check(decisions.decide(state(a,now,Map.of("botCombatLog","true"),dangerous),now+100)==null,"Combat logout spammed");
        check(decisions.decide(state(b,now,Map.of("botCombatLog","true"),Map.of("connected","true","damageAt",""+now,"hostiles","0")),now)==null,"Environmental damage caused combat log");
        Controller controller=new Controller(data.resolve("hub"),"127.0.0.1",0,"127.0.0.1",0,null,"127.0.0.1","test");
        until(()->controller.port()!=0,"Relay failed to bind");URI page=URI.create(controller.url());String key=controller.key();int port=controller.port();
        BotNetwork first=BotNetwork.join(a,key,"127.0.0.1",port),second=BotNetwork.join(b,key,"127.0.0.1",port);
        try{
            until(()->first.connected()&&second.connected(),"Bots did not connect");
            first.publish(state(a,System.currentTimeMillis(),Map.of(),Map.of("connected","true")));second.publish(state(b,System.currentTimeMillis(),Map.of(),Map.of("connected","true")));
            until(()->{try{return request(page,"GET","").body().contains("BotA")&&request(page,"GET","").body().contains("BotB");}catch(Exception e){return false;}},"Dashboard missed client telemetry");
            check(request(page,"POST","target="+a.id()+"&action=command&value=%23session+logout+60").statusCode()==202,"HTTP command not accepted");
            List<Message> commands=new ArrayList<>();until(()->{commands.addAll(first.drain(128));return commands.stream().anyMatch(message->message instanceof Execute e&&e.command().equals("session logout 60"));},"Controller did not route session command");
            first.publish(state(a,System.currentTimeMillis()+1,Map.of(),Map.of("connected","false","holdUntil",""+Long.MAX_VALUE)));
            Thread.sleep(650);check(request(page,"GET","").body().contains("\"connected\":\"false\""),"Disconnected game lost its control connection");
            check(request(page,"POST","target=all&action=setting&key=botCombatLog&value=true").statusCode()==202,"Global setting rejected");
            List<Message> policies=new ArrayList<>();until(()->{policies.addAll(second.drain(128));return policies.stream().anyMatch(message->message instanceof Policy p&&p.settings().get("botCombatLog").equals("true"));},"Global policy not distributed");
            first.close();Thread.sleep(300);check(request(page,"GET","").statusCode()==200&&second.connected(),"One client disconnect stopped the controller");
            check(request(page,"POST","target="+b.id()+"&action=command&value=%23update+check").statusCode()==202,"Surviving client command rejected");
            List<Message> updates=new ArrayList<>();until(()->{updates.addAll(second.drain(128));return updates.stream().anyMatch(message->message instanceof Execute e&&e.command().equals("update check"));},"Surviving client was not controllable");
        }finally{first.close();second.close();controller.close();}
        try(Controller restarted=new Controller(data.resolve("hub"),"127.0.0.1",0,"127.0.0.1",0,null,"127.0.0.1","test")){
            check(restarted.key().equals(key)&&URI.create(restarted.url()).getFragment().equals(page.getFragment()),"Controller identity/key changed after restart");
            until(()->{try{String json=request(URI.create(restarted.url()),"GET","").body();return json.contains("BotA")&&json.contains("\"online\":false")&&json.contains("\"botCombatLog\":\"true\"");}catch(Exception e){return false;}},"Offline history or saved policy disappeared");
        }
        // Explicit remote origin remains restricted to the configured host and origin.
        try(LocalNetworkViewer remote=new LocalNetworkViewer(0,action->true,"127.0.0.1",URI.create("https://controller.example"),NetworkProtocol.newKey())){
            check(remote.url().startsWith("https://controller.example/#"),"Remote public URL ignored");
            var wrong=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+remote.port()+"/api/control")).header("Authorization","Bearer "+URI.create(remote.url()).getFragment()).header("Origin","https://evil.example").GET().build();
            check(HTTP.send(wrong,HttpResponse.BodyHandlers.ofString()).statusCode()==403,"Cross-origin control accepted");
        }
        System.out.println("Controller/session checks passed: real two-client routing, offline control, crash isolation, durable maps/policies/identity, update commands, remote-origin checks, combat-log decisions and reconnect backoff");
    }
}
