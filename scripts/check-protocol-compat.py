#!/usr/bin/env python3
"""Exercise real protocol-7 code from the last stable release against current encrypted channels."""
import os, pathlib, shutil, subprocess, tempfile, time

ROOT = pathlib.Path(__file__).resolve().parents[1]
os.chdir(ROOT)
REFERENCE = 'v0.1.1.2'
JAVA = pathlib.Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-17-openjdk-amd64')) / 'bin/java'
if not JAVA.exists(): JAVA = pathlib.Path(shutil.which('java'))
JAVAC = [str(JAVA), '-m', 'jdk.compiler/com.sun.tools.javac.Main']
FIXTURE = r'''
import hivemind.utils.network.*;
import hivemind.utils.network.NetworkProtocol.*;
import hivemind.utils.network.ControlPackets.*;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import java.net.*;import java.nio.file.*;import java.util.*;
public final class CompatFixture {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static int protocol(Channel channel){try{return (int)channel.getClass().getMethod("protocol").invoke(channel);}catch(NoSuchMethodException old){return 7;}catch(Exception e){throw new RuntimeException(e);}}
 public static void main(String[] args)throws Exception{
  boolean server=args[0].equals("server"),modern=Boolean.parseBoolean(args[2]);long now=System.currentTimeMillis();
  Identity who=new Identity(UUID.nameUUIDFromBytes((server?"Host":"Client").getBytes()),server?"Host":"Client",NetworkProtocol.scope("compatibility-test"));
  String key="compatibility_test_key_0123456789";Socket socket;ServerSocket listener=null;
  if(server){listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress());Files.writeString(Path.of(args[1]),""+listener.getLocalPort());socket=listener.accept();}else socket=new Socket(InetAddress.getLoopbackAddress(),Integer.parseInt(args[1]));socket.setSoTimeout(10000);
  try(socket){Channel channel=server?NetworkProtocol.accept(socket.getInputStream(),socket.getOutputStream(),who,key):NetworkProtocol.connect(socket.getInputStream(),socket.getOutputStream(),who,key);int protocol=protocol(channel);Endpoint p=new Endpoint("minecraft:overworld",1,64,2);
   if(server){
    Message request=channel.read();check(request instanceof State,"state crossed versions");State state=(State)request;if(protocol==7)check(!state.settings().containsKey("botFleetHold"),"legacy state settings are filtered");
    UUID id=UUID.randomUUID();Map<String,String> settings=new LinkedHashMap<>();settings.put("stalkDistance","40");if(modern)settings.put("botFleetHold","false");
    channel.write(new Policy(who.id(),who.name(),id,who.id(),state.reporter(),1,settings,Map.of(),now));
    channel.write(new Receipt(who.id(),who.name(),id,state.reporter(),true,"Accepted and started",now));
    Map<String,String> details=new LinkedHashMap<>();details.put("task","compatibility");for(int i=0;i<(modern?49:9);i++)details.put("detail"+i,"value"+i);
    channel.write(new OperationsPackets.Snapshot(who.id(),who.name(),details,List.of(),false,now));
    channel.write(new TeamPackets.Intent(who.id(),who.name(),TeamPackets.Kind.valueOf(modern?"STORAGE":"MINE"),null,p,p,false,false,now));
    check(channel.read() instanceof Receipt,"receipt returned over the same channel");channel.write(null);
   }else{
    Map<String,String> settings=new LinkedHashMap<>();settings.put("botAutoRoam","true");if(modern)settings.put("botFleetHold","false");
    channel.write(new State(who.id(),who.name(),"Idle",20,20,p,Map.of(),settings,Map.of(),0,now));
    Policy policy=(Policy)channel.read();check(protocol>=8==policy.settings().containsKey("botFleetHold"),"policy capability filtering");
    check(channel.read() instanceof Receipt,"receipt crossed versions");OperationsPackets.Snapshot snapshot=(OperationsPackets.Snapshot)channel.read();check(snapshot.details().size()<=(protocol>=8?64:40),"snapshot respects peer capacity");
    TeamPackets.Intent intent=(TeamPackets.Intent)channel.read();check(protocol>=8||intent.kind().ordinal()<5,"new reservation kind is not sent to old clients");
    channel.write(new Receipt(who.id(),who.name(),policy.id(),policy.requester(),true,"Received",now));check(channel.read()==null,"encrypted keepalive crossed versions");
   }
  }finally{if(listener!=null)listener.close();}
 }
}
'''

def run(*args): subprocess.run([str(a) for a in args], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
try: subprocess.check_output(['git','rev-parse',REFERENCE],stderr=subprocess.DEVNULL)
except subprocess.CalledProcessError: subprocess.run(['git','fetch','origin','tag',REFERENCE,'--depth=1'],check=True)

with tempfile.TemporaryDirectory(prefix='hivemind-protocol-') as temporary:
    temp=pathlib.Path(temporary);old=temp/'old';old.mkdir();sources=[]
    files=subprocess.check_output(['git','ls-tree','-r','--name-only',REFERENCE],text=True).splitlines()
    extras={'AtomicProperties.java','StorageRule.java','TrustedControl.java','WorldRoutes.java'}
    for name in files:
        if name.startswith('src/main/java/hivemind/utils/network/') and name.endswith('.java') or name.startswith('src/main/java/hivemind/utils/') and pathlib.Path(name).name in extras:
            target=old/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(subprocess.check_output(['git','show',REFERENCE+':'+name]));sources.append(target)
    fixture=temp/'CompatFixture.java';fixture.write_text(FIXTURE)
    old_classes=temp/'old-classes';old_classes.mkdir();listing=temp/'old.sources';listing.write_text('\n'.join(str(p) for p in sources+[fixture]))
    run(*JAVAC,'--release','17','--add-modules','jdk.httpserver','-d',old_classes,'@'+str(listing))
    new_classes=temp/'new-classes';new_classes.mkdir();run(*JAVAC,'--release','17','-cp',ROOT/'build/controller-classes','-d',new_classes,fixture)
    legacy=str(old_classes);modern=os.pathsep.join([str(new_classes),str(ROOT/'build/controller-classes')])
    for server_new,client_new in [(False,True),(True,False),(True,True)]:
        port=temp/'port';port.unlink(missing_ok=True)
        server=subprocess.Popen([str(JAVA),'-cp',modern if server_new else legacy,'CompatFixture','server',str(port),str(server_new).lower()],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        try:
            deadline=time.monotonic()+10
            while not port.exists() and server.poll() is None and time.monotonic()<deadline:time.sleep(.02)
            if not port.exists():raise RuntimeError('Compatibility server failed: '+server.communicate(timeout=2)[1])
            run(JAVA,'-cp',modern if client_new else legacy,'CompatFixture','client',port.read_text(),str(client_new).lower())
            stdout,stderr=server.communicate(timeout=10)
            if server.returncode:raise RuntimeError(stderr)
        finally:
            if server.poll() is None:server.kill();server.wait()
    print('Encrypted interoperability passed: old host/new client, new host/old client, new host/new client')
