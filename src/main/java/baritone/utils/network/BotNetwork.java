/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils.network;

import baritone.utils.network.NetworkProtocol.*;
import baritone.utils.network.ControlPackets.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** One client hosts a small sighting relay; other clients reconnect to it. All socket work stays off the game thread. */
public final class BotNetwork implements AutoCloseable {
    private static final int MAX_PEERS = 16;
    private final Identity identity;
    private final String key, address;
    private final int port;
    private final boolean host;
    private final ThreadPoolExecutor io;
    private final Semaphore handshakes = new Semaphore(MAX_PEERS);
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Peer> peers = new ConcurrentHashMap<>();
    private final BlockingQueue<Message> received = new ArrayBlockingQueue<>(512);
    private final BlockingQueue<Packet> controls = new ArrayBlockingQueue<>(128);
    private final Map<UUID,State> states = new ConcurrentHashMap<>();
    private volatile Defaults defaults;
    private volatile long connectionEpoch;
    private final NetworkAtlas cache = new NetworkAtlas();
    private final TerrainStore terrain = new TerrainStore();
    private volatile ServerSocket listener;
    private volatile boolean closed;
    private volatile int boundPort;
    private volatile String error = "";

    private BotNetwork(Identity identity, String key, String address, int port, boolean host, List<Message> history) {
        this.identity = identity; this.key = NetworkProtocol.checkKey(key); this.address = address; this.port = port; this.host = host;
        long now = System.currentTimeMillis();
        for (Message message : history) if (host || message.reporter().equals(identity.id())) {cache.accept(message, now);terrain.accept(message,now);}
        AtomicInteger number = new AtomicInteger();
        io = new ThreadPoolExecutor(0, 64, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "Baritone-network-" + number.incrementAndGet()); thread.setDaemon(true); return thread;
        });
        io.execute(host ? this::listen : this::join);
    }
    public static BotNetwork host(Identity identity, String key, String bind, int port) { return host(identity, key, bind, port, List.of()); }
    public static BotNetwork join(Identity identity, String key, String host, int port) { return join(identity, key, host, port, List.of()); }
    public static BotNetwork host(Identity identity, String key, String bind, int port, List<Message> history) { return new BotNetwork(identity, key, bind, port, true, history); }
    public static BotNetwork join(Identity identity, String key, String host, int port, List<Message> history) { return new BotNetwork(identity, key, host, port, false, history); }
    public Identity identity() { return identity; }
    public boolean isHost() { return host; }
    public List<State> states() {
        long now=System.currentTimeMillis();
        return states.values().stream().filter(state->state.valid(now)&&(!host||state.reporter().equals(identity.id())||peers.containsKey(state.reporter())))
                .sorted(Comparator.comparing(State::reporterName)).toList();
    }
    public int boundPort() { return boundPort; }
    public List<Identity> peers() { return peers.values().stream().map(peer -> peer.channel.remote()).sorted(Comparator.comparing(Identity::name)).toList(); }
    public long connectionEpoch(){return connectionEpoch;}
    public boolean connected() { return !peers.isEmpty(); }
    public String status() {
        if (closed) return "Network off";
        String state = host ? boundPort == 0 ? "Starting network host" : "Hosting on " + address + ":" + boundPort : connected() ? "Connected to " + address + ":" + port : "Connecting to " + address + ":" + port;
        return state + "; " + peers.size() + " peer(s)" + (error.isEmpty() ? "" : "; retrying: " + error);
    }
    public List<Message> drain(int limit) {
        List<Message> result = new ArrayList<>();
        int count=Math.max(0,Math.min(512,limit));
        controls.drainTo(result,count);received.drainTo(result,count-result.size());return result;
    }
    /** Only firsthand reports from this client's identity may enter through this API. Received reports never return here. */
    public boolean publish(Message report) {
        if (closed || !report.reporter().equals(identity.id()) || !report.reporterName().equals(identity.name())) return false;
        if(report instanceof Packet packet){
            if(!packet.valid(System.currentTimeMillis())||(packet instanceof Execute||packet instanceof Policy||packet instanceof Defaults||packet instanceof TeamPackets.Plan)&&!host||packet instanceof Request&&host)return false;
            if(packet instanceof State state)states.put(state.reporter(),state);
            if(packet instanceof Defaults current)defaults=current;
            boolean delivered=true;for(Peer peer:peers.values())delivered&=peer.offer(packet);return delivered;
        }
        if (!(report instanceof TerrainPackets.Tile||report instanceof TerrainPackets.Site||report instanceof TerrainPackets.Marker?terrain.accept(report,System.currentTimeMillis()):cache.accept(report, System.currentTimeMillis()))) return false;
        for (Peer peer : peers.values()) peer.offer(report);
        return true;
    }
    private void route(Peer sender, Message report) throws IOException {
        if (host && !report.reporter().equals(sender.channel.remote().id()))
            throw new IOException("Peer attempted to impersonate another reporter");
        if(report instanceof Packet packet){
            if(host&&(packet instanceof Execute||packet instanceof Policy||packet instanceof Defaults||packet instanceof TeamPackets.Plan))throw new IOException("Only the network host can dispatch control");
            if(!host&&(packet instanceof Request||(packet instanceof Execute||packet instanceof Policy||packet instanceof Defaults||packet instanceof TeamPackets.Plan)&&!packet.reporter().equals(sender.channel.remote().id())))
                throw new IOException("Invalid control authority");
            if(!packet.valid(System.currentTimeMillis()))return;
            if(packet instanceof State state){
                long now=System.currentTimeMillis();states.values().removeIf(value->!value.valid(now));
                State old=states.get(state.reporter());if(old!=null&&old.observedAt()>=state.observedAt())return;
                if(states.size()>=256&&!states.containsKey(state.reporter()))throw new IOException("Bot state limit exceeded");
                states.put(state.reporter(),state);
            }
            if(packet instanceof Defaults current)defaults=current;
            if(!controls.offer(packet))throw new IOException("Control inbox is full; action was not queued");
            // Requests reach the host's client thread for validation before any bot is told to execute them.
            if(host&&!(packet instanceof Request)&&!(packet instanceof TeamPackets.Intent))for(Peer peer:peers.values())if(peer!=sender)peer.offer(packet);
            return;
        }
        if (!report.valid(System.currentTimeMillis())) return;
        if (!(report instanceof TerrainPackets.Tile||report instanceof TerrainPackets.Site||report instanceof TerrainPackets.Marker?terrain.accept(report,System.currentTimeMillis()):cache.accept(report, System.currentTimeMillis()))) return;
        if (!received.offer(report)) { received.poll(); received.offer(report); }
        if (host) for (Peer peer : peers.values()) if (peer != sender) peer.offer(report);
    }
    private void listen() {
        while (!closed) {
            try (ServerSocket server = new ServerSocket()) {
                listener = server; server.setReuseAddress(true); server.bind(new InetSocketAddress(address, port), MAX_PEERS); boundPort = server.getLocalPort(); error = "";
                while (!closed) {
                    Socket socket = server.accept(); sockets.add(socket);
                    if (closed || !handshakes.tryAcquire()) { forget(socket); continue; }
                    try { io.execute(() -> accept(socket)); }
                    catch (RejectedExecutionException e) { handshakes.release(); forget(socket); }
                }
            } catch (IOException | RuntimeException e) { failed(e); }
            finally { listener = null; boundPort = 0; }
            backoff(5000);
        }
    }
    private void configure(Socket socket) throws SocketException { socket.setTcpNoDelay(true); socket.setSoTimeout(5000); }
    private void accept(Socket socket) {
        Peer peer = null;
        try {
            configure(socket);
            NetworkProtocol.Channel channel = NetworkProtocol.accept(socket.getInputStream(), socket.getOutputStream(), identity, key);
            peer = new Peer(socket, channel); register(peer);
        } catch (IOException | RuntimeException e) { failed(e); }
        finally { handshakes.release(); if (peer == null || !peer.registered) forget(socket); }
        if (peer != null && peer.registered) peer.read();
    }
    private void join() {
        int failures = 0;
        while (!closed) {
            Socket socket = new Socket(); sockets.add(socket); Peer peer = null;
            try {
                configure(socket); socket.connect(new InetSocketAddress(address, port), 5000);
                peer = new Peer(socket, NetworkProtocol.connect(socket.getInputStream(), socket.getOutputStream(), identity, key));
                register(peer); failures = 0; peer.read();
            } catch (IOException | RuntimeException e) { failed(e); }
            finally { if (peer != null) peer.close(); else forget(socket); }
            backoff(Math.min(30000, 1000L << Math.min(5, failures++)));
        }
    }
    private synchronized void register(Peer peer) throws IOException {
        UUID id = peer.channel.remote().id();
        if (closed || peers.size() >= MAX_PEERS || peers.containsKey(id)) throw new IOException("Network full or duplicate bot UUID");
        peer.socket.setSoTimeout(20000); peers.put(id, peer); peer.registered = true;connectionEpoch++;
        try { io.execute(peer::write); }
        catch (RejectedExecutionException e) { peer.close(); throw new IOException("Network shutting down", e); }
        error = "";
    }
    private void failed(Exception exception) {
        if (!closed) {
            String message = exception.getMessage();
            error = message == null ? exception.getClass().getSimpleName() : message.substring(0, Math.min(160, message.length()));
        }
    }
    private void backoff(long millis) {
        synchronized (this) {
            if (!closed) try { wait(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    private void forget(Socket socket) { sockets.remove(socket); try { socket.close(); } catch (IOException ignored) {} }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; notifyAll();
        ServerSocket server = listener; if (server != null) try { server.close(); } catch (IOException ignored) {}
        for (Peer peer : peers.values()) peer.close();
        for (Socket socket : sockets) forget(socket);
        peers.clear(); cache.clear();terrain.clear(); received.clear(); controls.clear();states.clear();io.shutdownNow();
    }
    private final class Peer {
        private final Socket socket;
        private final NetworkProtocol.Channel channel;
        private final BlockingQueue<Message> outgoing = new ArrayBlockingQueue<>(256);
        private final BlockingQueue<Packet> priority = new ArrayBlockingQueue<>(64);
        private final Deque<Message> history;
        private volatile boolean stopped, registered;
        private Peer(Socket socket, NetworkProtocol.Channel channel) {
            this.socket = socket; this.channel = channel;
            List<Message> retained=new ArrayList<>(cache.snapshot(System.currentTimeMillis()));retained.addAll(terrain.snapshot(System.currentTimeMillis()));
            history = new ArrayDeque<>(retained.stream()
                    .filter(message -> !message.reporter().equals(channel.remote().id()) && (host || message.reporter().equals(identity.id()))).toList());
            for(State state:states())if(!state.reporter().equals(channel.remote().id())&&(host||state.reporter().equals(identity.id())))history.addFirst(state);
            Defaults current=defaults;if(host&&current!=null&&current.valid(System.currentTimeMillis()))history.addFirst(current);
        }
        private boolean offer(Message sighting) {
            if(sighting instanceof Packet packet){
                if(stopped)return false;
                if(priority.offer(packet))return true;
                close();return false;
            }
            if (!stopped && !outgoing.offer(sighting)) { outgoing.poll(); outgoing.offer(sighting); }
            return !stopped;
        }
        private void write() {
            try {
                while (!closed && !stopped) {
                    Message report = priority.poll();
                    if(report==null)report=outgoing.poll();
                    if (report == null) report = history.pollFirst();
                    if (report == null) report = priority.poll(500, TimeUnit.MILLISECONDS);
                    if (closed || stopped) return;
                    channel.write(report); // An empty queue sends an authenticated keepalive.
                    if (report != null) Thread.sleep(8); // Bound initial history replay below the receiver's rate limit.
                }
            } catch (IOException | InterruptedException e) { if (!stopped) failed(e); }
            finally { close(); }
        }
        private void read() {
            long window = System.currentTimeMillis(); int count = 0;
            try {
                while (!closed && !stopped) {
                    Message report = channel.read();
                    long now = System.currentTimeMillis();
                    if (now - window >= 1000) { count = 0; window = now; }
                    if (++count > 256) throw new IOException("Peer exceeded network message limit");
                    if (report != null) route(this, report);
                }
            } catch (IOException | RuntimeException e) { if (!stopped) failed(e); }
            finally { close(); }
        }
        private void close() { stopped = true; peers.remove(channel.remote().id(), this);if(host)states.remove(channel.remote().id());outgoing.clear();priority.clear();forget(socket); }
    }
}
