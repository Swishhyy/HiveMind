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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Local control page. Authenticated HTTP actions are queued for execution on the Minecraft client thread. */
public final class LocalNetworkViewer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService workers;
    private final String token;
    private final URI publicOrigin;
    private final byte[] page;
    private final Predicate<WebControlAction> actions;
    private volatile byte[] snapshot = "{\"now\":0,\"status\":\"Waiting for world data\",\"dimension\":\"minecraft:overworld\",\"samples\":0,\"peers\":[],\"chunks\":[],\"bots\":[],\"sightings\":[],\"hotspots\":[]}".getBytes(StandardCharsets.UTF_8);
    private volatile byte[] control = "{\"ready\":false,\"bots\":[],\"schema\":[],\"actions\":[]}".getBytes(StandardCharsets.UTF_8);
    private volatile TerrainStore terrain=new TerrainStore();
    public LocalNetworkViewer(int port) throws IOException { this(port,action->false); }
    public LocalNetworkViewer(int port,Predicate<WebControlAction> actions) throws IOException {this(port,actions,"127.0.0.1",null,NetworkProtocol.newKey());}
    public LocalNetworkViewer(int port,Predicate<WebControlAction> actions,String bind,URI publicOrigin,String token) throws IOException {
        this.actions=actions;this.token=NetworkProtocol.checkKey(token);
        if(publicOrigin!=null&&(!java.util.Set.of("http","https").contains(publicOrigin.getScheme())||publicOrigin.getHost()==null||publicOrigin.getUserInfo()!=null||publicOrigin.getRawQuery()!=null||publicOrigin.getRawFragment()!=null||!java.util.Set.of("","/").contains(publicOrigin.getPath())))throw new IllegalArgumentException("Public URL must be http(s)://host[:port] without a path");
        if(publicOrigin==null&&!java.net.InetAddress.getByName(bind).isLoopbackAddress())throw new IllegalArgumentException("Set --public-url when binding the web controller remotely");
        this.publicOrigin=publicOrigin;

        try (InputStream stream = LocalNetworkViewer.class.getResourceAsStream("/assets/hivemind/network/index.html")) {
            if (stream == null) throw new IOException("Network viewer resource is missing");
            page = stream.readAllBytes();
        }
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
            Thread thread = new Thread(task, "HiveMind-local-viewer"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bind), port), 16);
        server.setExecutor(workers); server.createContext("/", this::handle); server.start();
    }
    public int port() { return server.getAddress().getPort(); }
    public String url() { return (publicOrigin==null?"http://127.0.0.1:"+port():publicOrigin.toString().replaceFirst("/$",""))+"/#"+token; }
    public void snapshot(String json) { snapshot = json.getBytes(StandardCharsets.UTF_8); }
    public void controls(String json) { control=json.getBytes(StandardCharsets.UTF_8); }
    public void terrain(TerrainStore store){terrain=java.util.Objects.requireNonNull(store);}
    private boolean authenticated(HttpExchange request){
        String auth=request.getRequestHeaders().getFirst("Authorization");
        return auth!=null&&MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.US_ASCII),auth.getBytes(StandardCharsets.UTF_8));
    }
    private void handle(HttpExchange request) throws IOException {
        try {
            var headers = request.getResponseHeaders();
            headers.set("Cache-Control", "no-store"); headers.set("X-Content-Type-Options", "nosniff"); headers.set("Referrer-Policy", "no-referrer");
            headers.set("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src data:; frame-ancestors 'none'; base-uri 'none'");
            String host = request.getRequestHeaders().getFirst("Host"), origin = request.getRequestHeaders().getFirst("Origin");
            boolean local=("127.0.0.1:"+port()).equals(host)||("localhost:"+port()).equals(host);
            boolean remote=publicOrigin!=null&&publicOrigin.getRawAuthority().equals(host);
            if(!local&&!remote){send(request,403,"text/plain",new byte[0]);return;}
            String expected=remote?publicOrigin.getScheme()+"://"+publicOrigin.getRawAuthority():"http://"+host;
            if(origin!=null&&!origin.equals(expected)){send(request,403,"text/plain",new byte[0]);return;}
            String path=request.getRequestURI().getPath(),method=request.getRequestMethod();
            if(path.startsWith("/api/")&&!authenticated(request)){send(request,401,"text/plain","Open the address printed by #network web".getBytes(StandardCharsets.UTF_8));return;}
            if(!method.equals("GET")&&!(method.equals("POST")&&path.equals("/api/control"))){send(request,405,"text/plain",new byte[0]);return;}
            switch (request.getRequestURI().getPath()) {
                case "/" -> send(request, 200, "text/html; charset=utf-8", page);
                case "/api/snapshot" -> {
                    send(request, 200, "application/json; charset=utf-8", snapshot);
                }
                case "/api/terrain" -> send(request,200,"application/json; charset=utf-8",terrain.index(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
                case "/api/tile" -> {
                    try{
                        String query=request.getRequestURI().getRawQuery();if(query==null||query.length()>1024)throw new IllegalArgumentException();
                        java.util.Map<String,String> fields=new java.util.HashMap<>();
                        for(String part:query.split("&")){String[] pair=part.split("=",2);if(pair.length!=2||fields.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair[1],StandardCharsets.UTF_8))!=null)throw new IllegalArgumentException();}
                        if(!java.util.Set.of("d","x","z").equals(fields.keySet()))throw new IllegalArgumentException();
                        int x=Integer.parseInt(fields.get("x")),z=Integer.parseInt(fields.get("z"));if(Math.abs((long)x)>1875000||Math.abs((long)z)>1875000||fields.get("d").length()>128)throw new IllegalArgumentException();
                        var tile=terrain.tile(fields.get("d"),x,z);send(request,tile==null?404:200,"application/json; charset=utf-8",(tile==null?"{}":TerrainStore.json(tile)).getBytes(StandardCharsets.UTF_8));
                    }catch(IllegalArgumentException error){send(request,400,"text/plain",new byte[0]);}
                }
                case "/api/control" -> {
                    if(method.equals("GET"))send(request,200,"application/json; charset=utf-8",control);
                    else{
                        String type=request.getRequestHeaders().getFirst("Content-Type");
                        if(type==null||!type.toLowerCase(java.util.Locale.ROOT).startsWith("application/x-www-form-urlencoded")){send(request,415,"text/plain",new byte[0]);return;}
                        byte[] body=request.getRequestBody().readNBytes(8193);
                        if(body.length>8192){send(request,413,"text/plain",new byte[0]);return;}
                        try{
                            WebControlAction action=WebControlAction.parse(body,System.currentTimeMillis());
                            if(!actions.test(action)){send(request,429,"text/plain","Control queue is busy; try again shortly".getBytes(StandardCharsets.UTF_8));return;}
                            send(request,202,"application/json; charset=utf-8",("{\"id\":\""+action.id()+"\"}").getBytes(StandardCharsets.UTF_8));
                        }catch(IllegalArgumentException error){send(request,400,"text/plain",error.getMessage().getBytes(StandardCharsets.UTF_8));}
                    }
                }
                default -> send(request, 404, "text/plain", new byte[0]);
            }
        } finally { request.close(); }
    }
    private void send(HttpExchange request, int status, String type, byte[] body) throws IOException {
        String encoding=request.getRequestHeaders().getFirst("Accept-Encoding");
        if(body.length>1024&&encoding!=null&&java.util.Arrays.stream(encoding.split(",")).anyMatch(value->value.strip().equals("gzip"))){
            var bytes=new java.io.ByteArrayOutputStream();try(var gzip=new java.util.zip.GZIPOutputStream(bytes)){gzip.write(body);}
            body=bytes.toByteArray();request.getResponseHeaders().set("Content-Encoding","gzip");
            request.getResponseHeaders().set("Vary","Accept-Encoding");
        }
        request.getResponseHeaders().set("Content-Type", type); request.sendResponseHeaders(status, body.length);
        request.getResponseBody().write(body);
    }
    @Override public void close() { server.stop(0); workers.shutdownNow(); }
}
