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
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Actual loopback clients, authenticated frame corruption/replay, reconnects, durable atlas, and HTTP access checks. */
public final class NetworkChecks {
    private static final String KEY = "a-long-generated-test-network-key", SCOPE = NetworkProtocol.scope("server:example");
    private static final Identity HOST = who("Relay"), A = who("ScoutA"), B = who("ScoutB");
    private static Identity who(String name) { return new Identity(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name, SCOPE); }
    private static Sighting sight(Identity source, int x, long time) { return new Sighting(source.id(), source.name(), who("Player").id(), "Player", new Endpoint("minecraft:overworld", x, 64, -1), time); }
    private static void check(boolean good, String message) { if (!good) throw new AssertionError(message); }
    private static void await(BooleanSupplier condition, String message) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) { if (System.nanoTime() > until) throw new AssertionError(message); Thread.sleep(10); }
    }
    public static void main(String[] args) throws Exception {
        runAll(); System.out.println("Network checks passed: real multi-client relay, authentication, replay/tampering, reconnects, server/dimension isolation, history, search stability, localhost viewer");
    }
    public static void runAll() throws Exception { marksAndHistory(); encryptedFrames(); relayAndReconnect(); localPage(); }
    private static void marksAndHistory() throws Exception {
        long now = System.currentTimeMillis(); SharedSightings marks = new SharedSightings();
        Sighting first = sight(A, -1, now - 60000);
        check(marks.accept(first, now, 120000), "A fresh visual peer report becomes a mark");
        check(!marks.accept(first, now, 120000) && !marks.accept(sight(A, 80, now - 70000), now, 120000), "Duplicate or older positions never overwrite a recent mark");
        check(!marks.accept(sight(A, 99, now + 60000), now, 120000), "Future clock outliers are rejected");
        Sighting nether = new Sighting(A.id(), A.name(), first.player(), "Player", new Endpoint("minecraft:the_nether", 8, 60, 8), now);
        marks.accept(nether, now, 120000);
        check(marks.latest(first.player(), "minecraft:overworld", now, 120000).equals(first), "Nether coordinates cannot replace Overworld coordinates");
        check(marks.latest(first.player(), "minecraft:overworld", now + 180000, 120000) == null, "Live search marks expire independently of durable history");
        check(SharedSightings.shouldRedirect(first, null, 0, 0, now), "A search can use a peer's fresh report");
        check(!SharedSightings.shouldRedirect(first, null, now, 0, now), "Newer local visual evidence wins over a remote report");
        check(!SharedSightings.shouldRedirect(sight(A, 0, now), first, 0, now - 12000, now), "Repeated reports of a stationary target do not restart a search");
        check(!SharedSightings.shouldRedirect(sight(A, 64, now), first, 0, now - 5000, now), "Moving reports respect route hysteresis");
        check(SharedSightings.shouldRedirect(sight(A, 64, now), first, 0, now - 12000, now), "A materially changed position can redirect a search");
        Path root = Files.createTempDirectory("hivemind-atlas-check-");
        try {
            NetworkAtlas atlas = new NetworkAtlas(root);
            for (long ago : new long[]{300000,270000,240000,30000,0}) {
                Sighting report = sight(A, -1, now - ago); atlas.accept(report, now); atlas.accept(report, now);
                atlas.accept(sight(B, -1, now - ago), now);
            }
            check(atlas.hotspots(now).size() == 1 && atlas.hotspots(now).get(0).visits() == 2, "Duplicate relays and multiple observers do not inflate return visits");
            check(atlas.hotspots(now).get(0).sources().size() == 2 && atlas.hotspots(now).get(0).x() == -32, "Frequent areas retain their sources and use floor coordinates below zero");
            Exploration walk = new Exploration(A.id(), A.name(), new Endpoint("minecraft:overworld", -1, 64, -1), now);
            atlas.accept(walk, now); atlas.accept(new Exploration(B.id(), B.name(), walk.point(), now - 1), now);
            atlas.accept(nether, now); atlas.save();
            NetworkAtlas loaded = new NetworkAtlas(root);
            check(loaded.snapshot(now).equals(atlas.snapshot(now)), "History and both bot identities survive save/load in a shared chunk");
            int count = loaded.snapshot(now).size();
            for (Message report : atlas.snapshot(now)) loaded.accept(report, now);
            check(loaded.snapshot(now).size() == count, "Initial replay is idempotent after restart");
            check(new NetworkAtlas(root.resolve("other-server")).snapshot(now).isEmpty(), "Different Minecraft-server stores never share history");
            String json = loaded.json(now, "Connected \"test\" <status>", "minecraft:overworld", List.of(A,B));
            check(json.contains("\\\"test\\\"") && !json.contains("<status>") && json.contains("\"visits\":2"), "JSON safely escapes text and includes real observation statistics");
            NetworkConfig config = new NetworkConfig(root);
            check(config.options().mode() == NetworkConfig.Mode.OFF && config.options().webPort() == 0, "Network and page are disabled until configured");
            config.save(config.options().key(KEY).mode(NetworkConfig.Mode.JOIN, "127.0.0.1", 28766).web(28767));
            check(new NetworkConfig(root).options().equals(config.options()), "Per-server local endpoints persist");
            check(!Files.exists(root.resolve("player-sightings.properties")), "Peer history never creates local base evidence");
            NetworkAtlas bounded = new NetworkAtlas();
            for (int i = 0; i < 4500; i++) bounded.accept(sight(A, i, now - i), now);
            check(bounded.snapshot(now).size() == 4096, "Unbounded player traffic cannot grow the sighting journal");
            Files.writeString(root.resolve("bot-network-atlas.properties"), "count=999999\n");
            try { new NetworkAtlas(root); throw new AssertionError("Malformed history must fail visibly"); } catch (IllegalStateException expected) {}
            check(Files.readString(root.resolve("bot-network-atlas.properties")).contains("999999"), "Invalid history is never silently overwritten");
        } finally { try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    }
    private static final class Pair implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        final Socket client = new Socket("127.0.0.1", server.getLocalPort());
        final Socket accepted = server.accept();
        final ByteArrayOutputStream capture = new ByteArrayOutputStream();
        boolean hold;
        final Channel reader, writer;
        Pair() throws Exception {
            accepted.setSoTimeout(3000); client.setSoTimeout(3000);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<Channel> handshake = worker.submit(() -> NetworkProtocol.accept(accepted.getInputStream(), accepted.getOutputStream(), HOST, KEY));
                OutputStream tee = new OutputStream() {
                    @Override public void write(int b) throws IOException { if (!hold) client.getOutputStream().write(b); capture.write(b); }
                    @Override public void write(byte[] b, int offset, int length) throws IOException { if (!hold) client.getOutputStream().write(b,offset,length); capture.write(b,offset,length); }
                    @Override public void flush() throws IOException { client.getOutputStream().flush(); }
                };
                writer = NetworkProtocol.connect(client.getInputStream(), tee, A, KEY); reader = handshake.get(5, TimeUnit.SECONDS); capture.reset();
            } finally { worker.shutdownNow(); }
        }
        public void close() throws IOException { client.close(); accepted.close(); server.close(); }
    }
    private static void encryptedFrames() throws Exception {
        Sighting report = sight(A, 42, System.currentTimeMillis());
        try (Pair pair = new Pair()) {
            pair.writer.write(report); check(report.equals(pair.reader.read()), "Encrypted frames round-trip without changing provenance");
            byte[] captured = pair.capture.toByteArray();
            check(!new String(captured, StandardCharsets.ISO_8859_1).contains("Player"), "Coordinates and player payloads are not plaintext on the transport");
            pair.client.getOutputStream().write(captured);
            try { pair.reader.read(); throw new AssertionError("Replayed frames must be rejected"); } catch (IOException expected) {}
        }
        try (Pair pair = new Pair()) {
            pair.hold = true; pair.writer.write(report);
            byte[] changed = pair.capture.toByteArray(); changed[changed.length-1] ^= 1; pair.client.getOutputStream().write(changed);
            try { pair.reader.read(); throw new AssertionError("Tampered frames must be rejected"); } catch (IOException expected) {}
        }
        try (Pair pair = new Pair()) {
            new DataOutputStream(pair.client.getOutputStream()).writeInt(Integer.MAX_VALUE);
            try { pair.reader.read(); throw new AssertionError("Oversized frames must be rejected before allocating"); } catch (IOException expected) {}
        }
    }
    private static void relayAndReconnect() throws Exception {
        BotNetwork relay = BotNetwork.host(HOST, KEY, "127.0.0.1", 0);
        BotNetwork one = null, two = null, late = null, wrongKey = null, wrongScope = null;
        try {
            BotNetwork initial = relay; await(() -> initial.boundPort() > 0, "Relay did not bind");
            int port = relay.boundPort();
            one = BotNetwork.join(A, KEY, "127.0.0.1", port); two = BotNetwork.join(B, KEY, "127.0.0.1", port);
            await(() -> initial.peers().size() == 2, "Clients did not mutually authenticate");
            BotNetwork scout = one, watcher = two; List<Message> hostInbox = new ArrayList<>(), watcherInbox = new ArrayList<>();
            Sighting report = sight(A, 80, System.currentTimeMillis()); check(one.publish(report), "Local visual report publishes");
            await(() -> { hostInbox.addAll(initial.drain(128)); watcherInbox.addAll(watcher.drain(128)); return hostInbox.contains(report) && watcherInbox.contains(report); }, "Relay did not share a third player's sighting with another bot");
            check(one.drain(128).isEmpty(), "Relay does not echo a report back to its source");
            Exploration explored = new Exploration(A.id(), A.name(), report.point(), System.currentTimeMillis());
            one.publish(explored); await(() -> { watcherInbox.addAll(watcher.drain(128)); return watcherInbox.contains(explored); }, "Exploration did not reach the other bot");
            late = BotNetwork.join(who("LateBot"), KEY, "127.0.0.1", port); BotNetwork newcomer = late;
            List<Message> lateInbox = new ArrayList<>(); await(() -> { lateInbox.addAll(newcomer.drain(128)); return lateInbox.contains(report) && lateInbox.contains(explored); }, "Late join did not replay the original observations");
            wrongKey = BotNetwork.join(who("WrongKey"), NetworkProtocol.newKey(), "127.0.0.1", port);
            wrongScope = BotNetwork.join(new Identity(who("OtherServer").id(), "OtherServer", NetworkProtocol.scope("other-server")), KEY, "127.0.0.1", port);
            BotNetwork badKey = wrongKey, badScope = wrongScope;
            await(() -> badKey.status().contains("retrying") && badScope.status().contains("retrying"), "Invalid authentication/scope was not rejected");
            check(initial.peers().size() == 3 && !badKey.connected() && !badScope.connected(), "Other keys and Minecraft servers cannot join the client group");
            check(!one.publish(sight(B, 999, System.currentTimeMillis())), "A local client cannot publish as a different reporter");
            try (Socket forger = new Socket("127.0.0.1", port)) {
                forger.setSoTimeout(3000); Identity identity = who("Forger");
                Channel channel = NetworkProtocol.connect(forger.getInputStream(), forger.getOutputStream(), identity, KEY);
                channel.write(sight(B, 999, System.currentTimeMillis()));
                try { while (true) channel.read(); }
                catch (SocketTimeoutException e) { throw new AssertionError("Forged reporter stayed connected", e); }
                catch (IOException expected) {}
            }
            relay.close();
            relay = BotNetwork.host(HOST, KEY, "127.0.0.1", port); BotNetwork restarted = relay;
            await(() -> restarted.peers().size() == 3 && scout.connected() && watcher.connected(), "Clients did not reconnect after relay restart");
            List<Message> replay = new ArrayList<>(); await(() -> { replay.addAll(restarted.drain(128)); return replay.contains(report); }, "The source did not restore its history to the restarted relay");
            Sighting next = sight(A, 150, System.currentTimeMillis()); one.publish(next);
            await(() -> { watcherInbox.addAll(watcher.drain(128)); return watcherInbox.contains(next); }, "Sharing did not resume after reconnect");
        } finally {
            for (BotNetwork network : new BotNetwork[]{wrongKey,wrongScope,late,one,two,relay}) if (network != null) network.close();
        }
    }
    private static int request(LocalNetworkViewer page, String method, boolean auth, String origin) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", page.port())) {
            socket.setSoTimeout(3000);
            String request = method + " /api/snapshot HTTP/1.1\r\nHost: 127.0.0.1:" + page.port() + "\r\nConnection: close\r\n"
                    + (auth ? "Authorization: Bearer " + page.url().split("#", 2)[1] + "\r\n" : "")
                    + (origin != null ? "Origin: " + origin + "\r\n" : "") + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            return Integer.parseInt(new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine().split(" ")[1]);
        }
    }
    private static void localPage() throws Exception {
        try (LocalNetworkViewer page = new LocalNetworkViewer(0)) {
            page.snapshot("{\"test\":\"no secrets\"}");
            check(request(page,"GET",false,null) == 401, "Local API requires the independent page token");
            check(request(page,"GET",true,null) == 200, "The local page can read its own snapshot");
            check(request(page,"POST",true,null) == 405, "The exploration snapshot remains read-only; controls use their separate authenticated endpoint");
            check(request(page,"GET",true,"https://unrelated.example") == 403, "An unrelated web origin cannot read coordinates");
            try (Socket socket = new Socket("127.0.0.1", page.port())) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: unrelated.example\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                check(new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine().contains("403"), "DNS rebinding hosts are rejected");
            }
        }
    }
}
