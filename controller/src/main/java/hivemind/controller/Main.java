/* HiveMind, LGPL-3.0. */
package hivemind.controller;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CountDownLatch;
public final class Main {
    private Main(){}
    public static void main(String[] args)throws Exception{
        Map<String,String> options=new HashMap<>();
        Set<String> allowed=Set.of("data","bind","port","web-bind","web-port","public-url","advertise","scope");
        for(int i=0;i<args.length;i++){
            if(args[i].equals("--help")){System.out.println("HiveMind Controller 0.1.0.0\n--data <directory> --bind <relay-IP> --port <28766> --web-bind <127.0.0.1> --web-port <28767> --public-url <http(s)://host:port> --advertise <host-for-bots> --scope <server-label>\nRun one controller/data directory per Minecraft server. Defaults work on this computer.");return;}
            if(!args[i].startsWith("--")||!allowed.contains(args[i].substring(2))||i+1==args.length)throw new IllegalArgumentException("Unknown/incomplete option; use --help");
            String key=args[i].substring(2);if(options.put(key,args[++i])!=null)throw new IllegalArgumentException("Duplicate option "+key);
        }
        Path root=Path.of(options.getOrDefault("data","hivemind-controller-data"));
        Controller controller=new Controller(root,options.getOrDefault("bind","0.0.0.0"),Integer.parseInt(options.getOrDefault("port","28766")),options.getOrDefault("web-bind","127.0.0.1"),Integer.parseInt(options.getOrDefault("web-port","28767")),options.containsKey("public-url")?URI.create(options.get("public-url")):null,options.getOrDefault("advertise","127.0.0.1"),options.getOrDefault("scope","hivemind"));
        Runtime.getRuntime().addShutdownHook(new Thread(controller::close,"HiveMind-controller-shutdown"));
        System.out.println("HiveMind Controller 0.1.0.0\nDashboard: "+controller.url()+"\nOpen the dashboard and copy its bot connection command. Data: "+root.toAbsolutePath());
        new CountDownLatch(1).await();
    }
}
