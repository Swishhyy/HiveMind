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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;

/** Read-only localhost page; an independent random access token protects coordinates from other web origins. */
public final class LocalNetworkViewer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService workers;
    private final String token = NetworkProtocol.newKey();
    private final byte[] page;
    private volatile byte[] snapshot = "{\"now\":0,\"status\":\"Waiting for world data\",\"dimension\":\"minecraft:overworld\",\"samples\":0,\"peers\":[],\"chunks\":[],\"bots\":[],\"sightings\":[],\"hotspots\":[]}".getBytes(StandardCharsets.UTF_8);
    public LocalNetworkViewer(int port) throws IOException {
        try (InputStream stream = LocalNetworkViewer.class.getResourceAsStream("/assets/baritone/network/index.html")) {
            if (stream == null) throw new IOException("Network viewer resource is missing");
            page = stream.readAllBytes();
        }
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
            Thread thread = new Thread(task, "Baritone-local-viewer"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        server.setExecutor(workers); server.createContext("/", this::handle); server.start();
    }
    public int port() { return server.getAddress().getPort(); }
    public String url() { return "http://127.0.0.1:" + port() + "/#" + token; }
    public void snapshot(String json) { snapshot = json.getBytes(StandardCharsets.UTF_8); }
    private void handle(HttpExchange request) throws IOException {
        try {
            var headers = request.getResponseHeaders();
            headers.set("Cache-Control", "no-store"); headers.set("X-Content-Type-Options", "nosniff"); headers.set("Referrer-Policy", "no-referrer");
            headers.set("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src data:; frame-ancestors 'none'; base-uri 'none'");
            String host = request.getRequestHeaders().getFirst("Host"), origin = request.getRequestHeaders().getFirst("Origin");
            if (!("127.0.0.1:" + port()).equals(host) && !("localhost:" + port()).equals(host)) { send(request, 403, "text/plain", "Local host required".getBytes(StandardCharsets.UTF_8)); return; }
            if (origin != null && !origin.equals("http://" + host)) { send(request, 403, "text/plain", new byte[0]); return; }
            if (!request.getRequestMethod().equals("GET")) { send(request, 405, "text/plain", new byte[0]); return; }
            switch (request.getRequestURI().getPath()) {
                case "/" -> send(request, 200, "text/html; charset=utf-8", page);
                case "/api/snapshot" -> {
                    String auth = request.getRequestHeaders().getFirst("Authorization");
                    if (auth == null || !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.US_ASCII), auth.getBytes(StandardCharsets.UTF_8)))
                        send(request, 401, "text/plain", "Open the address printed by #network web".getBytes(StandardCharsets.UTF_8));
                    else send(request, 200, "application/json; charset=utf-8", snapshot);
                }
                default -> send(request, 404, "text/plain", new byte[0]);
            }
        } finally { request.close(); }
    }
    private void send(HttpExchange request, int status, String type, byte[] body) throws IOException {
        request.getResponseHeaders().set("Content-Type", type); request.sendResponseHeaders(status, body.length);
        request.getResponseBody().write(body);
    }
    @Override public void close() { server.stop(0); workers.shutdownNow(); }
}
