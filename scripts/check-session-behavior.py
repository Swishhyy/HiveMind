#!/usr/bin/env python3
"""Run the real session behavior against a small client fixture, without starting a renderer."""
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    'hivemind/behavior/Behavior.java': '''package hivemind.behavior;
import hivemind.HiveMind;import hivemind.api.event.events.TickEvent;
public class Behavior {protected final HiveMind hivemind;protected final HiveMind.Context ctx;
 public Behavior(HiveMind bot){hivemind=bot;ctx=bot.ctx;}public void onTick(TickEvent e){}public void onPostTick(TickEvent e){}}
''',
    'hivemind/HiveMind.java': '''package hivemind;
import java.util.*;import net.minecraft.client.Minecraft;import net.minecraft.client.multiplayer.ClientLevel;
public class HiveMind {
 public final Context ctx;public final Flight flight=new Flight();public final Inputs inputs=new Inputs();
 public static final Settings SETTINGS=new Settings();public HiveMind(Minecraft mc){ctx=new Context(mc);}
 public static Settings settings(){return SETTINGS;}public Flight getFlight(){return flight;}public Inputs getInputOverrideHandler(){return inputs;}
 public Jobs getJobManager(){return new Jobs();}public Autonomy getAutonomy(){return new Autonomy();}
 public Network getNetwork(){return new Network();}public Trust getBotControl(){return new Trust();}
 public static class Setting<T>{public T value;Setting(T v){value=v;}}
 public static class Settings{public Setting<Boolean> botAutoReconnect=new Setting<>(true);public Setting<Long> botReconnectDelayMS=new Setting<>(10000L);}
 public record Context(Minecraft minecraft){public Minecraft.Player player(){return minecraft.player;}public ClientLevel world(){return minecraft.level;}}
 public static class Flight{public int cancelled;public void cancel(){cancelled++;}}
 public static class Inputs{public int cleared;public void clearAllKeys(){cleared++;}}
 public static class Jobs{public List<Job> jobs(){return List.of();}public void resumeJobs(){}}
 public record Job(long id){}public static class Autonomy{public boolean paused(){return false;}}
 public static class Network{public boolean controllerConnected(){return false;}public boolean isTeammate(UUID id){return false;}}
 public static class Trust{public boolean isTrusted(UUID id){return false;}}
}
''',
    'hivemind/api/HiveMindAPI.java': '''package hivemind.api;import hivemind.HiveMind;
public final class HiveMindAPI {public static HiveMind primary;public static HiveMindAPI getProvider(){return new HiveMindAPI();}
 public HiveMind getPrimaryHiveMind(){return primary;}}
''',
    'net/minecraft/client/Minecraft.java': '''package net.minecraft.client;
import java.io.File;import java.util.UUID;import net.minecraft.network.chat.Component;import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;import net.minecraft.client.multiplayer.*;
public final class Minecraft {
 public final File gameDirectory;public final Gui gui=new Gui();public Player player=new Player();public ClientLevel level=new ClientLevel();
 public ClientPacketListener connection=new ClientPacketListener();public int logouts,connects;public String target;
 public Minecraft(File dir){gameDirectory=dir;}public ClientPacketListener getConnection(){return connection;}
 public ServerData getCurrentServer(){return connection==null?null:new ServerData("Test server","example.test",ServerData.Type.OTHER);}
 public void disconnectFromWorld(Component reason){logouts++;player=null;level=null;connection=null;gui.setScreen(new JoinMultiplayerScreen(new TitleScreen()));}
 public void stop(){throw new AssertionError("A server logout must not stop Minecraft");}
 public static class Gui{private Screen screen;public Screen screen(){return screen;}public void setScreen(Screen value){screen=value;}}
 public static class Player{public boolean isAlive(){return true;}public boolean isSpectator(){return false;}public boolean isCreative(){return false;}
  public float getHealth(){return 20;}public UUID getUUID(){return new UUID(0,1);}public double distanceToSqr(Player other){return 100;}}
}
''',
    'net/minecraft/client/multiplayer/ClientLevel.java': '''package net.minecraft.client.multiplayer;
import java.util.*;import net.minecraft.client.Minecraft;import net.minecraft.network.chat.Component;
public class ClientLevel{public List<Minecraft.Player> players(){return List.of();}
 public void disconnect(Component reason){throw new AssertionError("Raw world disconnect skips the menu transition");}}
''',
    'net/minecraft/client/multiplayer/ClientPacketListener.java': 'package net.minecraft.client.multiplayer;public class ClientPacketListener{}',
    'net/minecraft/client/multiplayer/ServerData.java': '''package net.minecraft.client.multiplayer;
public class ServerData{public final String name,ip;public enum Type{OTHER}public ServerData(String name,String ip,Type type){this.name=name;this.ip=ip;}}
''',
    'net/minecraft/client/multiplayer/resolver/ServerAddress.java': '''package net.minecraft.client.multiplayer.resolver;
public record ServerAddress(String address){public static ServerAddress parseString(String address){return new ServerAddress(address);}}
''',
    'net/minecraft/network/chat/Component.java': '''package net.minecraft.network.chat;
public record Component(String value){public static Component literal(String value){return new Component(value);}}
''',
    'net/minecraft/client/gui/screens/Screen.java': 'package net.minecraft.client.gui.screens;public class Screen{}',
    'net/minecraft/client/gui/screens/TitleScreen.java': 'package net.minecraft.client.gui.screens;public class TitleScreen extends Screen{}',
    'net/minecraft/client/gui/screens/DisconnectedScreen.java': 'package net.minecraft.client.gui.screens;public class DisconnectedScreen extends Screen{}',
    'net/minecraft/client/gui/screens/multiplayer/JoinMultiplayerScreen.java': '''package net.minecraft.client.gui.screens.multiplayer;
import net.minecraft.client.gui.screens.Screen;public class JoinMultiplayerScreen extends Screen{public JoinMultiplayerScreen(Screen parent){}}
''',
    'net/minecraft/client/gui/screens/ConnectScreen.java': '''package net.minecraft.client.gui.screens;
import net.minecraft.client.Minecraft;import net.minecraft.client.multiplayer.ServerData;import net.minecraft.client.multiplayer.resolver.ServerAddress;
public class ConnectScreen extends Screen{public static void startConnecting(Screen parent,Minecraft mc,ServerAddress address,ServerData data,boolean quick,Object transfer){mc.connects++;mc.target=data.ip;}}
''',
    'SessionBehaviorChecks.java': '''import hivemind.HiveMind;import hivemind.api.HiveMindAPI;import hivemind.behavior.SessionBehavior;
import hivemind.api.event.events.TickEvent;import hivemind.api.event.events.type.EventState;import hivemind.utils.bot.SessionRuntime;
import net.minecraft.client.Minecraft;import net.minecraft.client.multiplayer.*;import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import java.nio.file.Files;
public final class SessionBehaviorChecks {
 static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 static final TickEvent PRE=new TickEvent(EventState.PRE,TickEvent.Type.IN,1),POST=new TickEvent(EventState.POST,TickEvent.Type.IN,1);
 public static void main(String[] args)throws Exception{
  var dir=Files.createTempDirectory("hivemind-session");var mc=new Minecraft(dir.toFile());var bot=new HiveMind(mc);HiveMindAPI.primary=bot;
  var behavior=new SessionBehavior(bot);var session=SessionRuntime.get(dir);behavior.onTick(PRE);
  long now=System.currentTimeMillis();session.command("session logout 60",now);behavior.onTick(PRE);
  check(mc.player!=null&&mc.level!=null&&mc.logouts==0,"Logout unloaded the world during the in-world tick");
  check(bot.flight.cancelled==1&&bot.inputs.cleared==1,"Logout left forced controls running");
  behavior.onPostTick(POST);check(mc.logouts==1&&mc.player==null&&mc.level==null,"Logout did not leave the server");
  check(mc.gui.screen() instanceof JoinMultiplayerScreen,"Logout did not return to the server list");
  behavior.onPostTick(POST);check(mc.logouts==1,"Logout executed twice");behavior.onTick(PRE);
  check(mc.connects==0&&!session.shouldConnect(now+59000,false),"Combat logout ignored the wait");
  check(session.shouldConnect(now+60001,false),"Temporary logout never became eligible to reconnect");
  session.command("session logout 5",System.currentTimeMillis()-6000);behavior.onTick(PRE);
  check(mc.connects==1&&"example.test".equals(mc.target),"Expired combat logout cannot reconnect from the server list");
  session.command("session reconnect",System.currentTimeMillis());behavior.onTick(PRE);
  check(mc.connects==2,"Explicit reconnect cannot start from the server list");
  mc.player=new Minecraft.Player();mc.level=new ClientLevel();mc.connection=new ClientPacketListener();behavior.onTick(PRE);
  session.command("session logout 60",System.currentTimeMillis());behavior.onTick(PRE);mc.connection=new ClientPacketListener();behavior.onPostTick(POST);
  check(mc.logouts==1&&mc.player!=null,"Queued logout disconnected a replacement session");
  session.command("session logout",System.currentTimeMillis());behavior.onTick(PRE);behavior.onPostTick(POST);behavior.onTick(PRE);
  check(mc.logouts==2&&mc.connects==2,"Manual indefinite logout reconnected automatically");
  System.out.println("Session behavior checks passed: tick-boundary logout, menu transition, reconnect wait, server-list reconnect and connection replacement");
 }
}
''',
}

with tempfile.TemporaryDirectory(prefix='hivemind-session-check-') as directory:
    root = Path(directory)
    for name, content in SOURCES.items():
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)
    actual = [
        'src/main/java/hivemind/behavior/SessionBehavior.java',
        'src/main/java/hivemind/utils/bot/SessionState.java',
        'src/main/java/hivemind/utils/bot/SessionRuntime.java',
        'src/main/java/hivemind/utils/bot/AtomicProperties.java',
        'src/api/java/hivemind/api/event/events/TickEvent.java',
        'src/api/java/hivemind/api/event/events/type/EventState.java',
    ]
    compiler = ['javac'] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    subprocess.run(compiler + ['--release', '17', '-d', str(root / 'classes')] +
                   [str(root / name) for name in SOURCES] + [str(ROOT / name) for name in actual], check=True)
    subprocess.run(['java', '-cp', str(root / 'classes'), 'SessionBehaviorChecks'], check=True)
