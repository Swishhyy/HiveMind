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

import baritone.utils.mining.WorldRoutes.Endpoint;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Bounded, mutually authenticated, encrypted observations and explicitly routed client control. */
public final class NetworkProtocol {
    private static final int MAGIC = 0x42544e31, VERSION = 3, MAX_FRAME = 65536;
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final long MAX_AGE_MS = 600000, HISTORY_MS = 30L * 86400000, CLOCK_SKEW_MS = 10000;
    public sealed interface Message permits Sighting, Exploration, ControlPackets.Packet, TerrainPackets.Tile, TerrainPackets.Site {
        UUID reporter();
        String reporterName();
        long observedAt();
        default boolean valid(long now) { return observedAt() <= now + CLOCK_SKEW_MS && now - observedAt() <= HISTORY_MS; }
    }
    public record Identity(UUID id, String name, String scope) {
        public Identity {
            Objects.requireNonNull(id);
            NetworkProtocol.name(name);
            if (scope == null || !scope.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid server scope");
        }
    }
    public record Sighting(UUID reporter, String reporterName, UUID player, String playerName, Endpoint point, long observedAt) implements Message {
        public Sighting {
            Objects.requireNonNull(reporter); Objects.requireNonNull(player); Objects.requireNonNull(point);
            name(reporterName); name(playerName);
            if (point.dimension().length() > 128 || observedAt < 0) throw new IllegalArgumentException("Invalid sighting");
        }
        public boolean fresh(long now, long ttl) {
            return observedAt <= now + CLOCK_SKEW_MS && now - observedAt <= Math.max(10000, Math.min(MAX_AGE_MS, ttl));
        }
    }
    /** A bot's actual feet position marks a visited chunk, without claiming to have explored every loaded chunk. */
    public record Exploration(UUID reporter, String reporterName, Endpoint point, long observedAt) implements Message {
        public Exploration {
            Objects.requireNonNull(reporter); Objects.requireNonNull(point); name(reporterName);
            if (point.dimension().length() > 128 || observedAt < 0) throw new IllegalArgumentException("Invalid exploration");
        }
    }
    private NetworkProtocol() {}
    private static void name(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Invalid player name");
    }
    public static String newKey() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public static String checkKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,128}")) throw new IllegalArgumentException("Use a 16-128 character network key (letters, digits, _ or -); network key generates one");
        return key;
    }
    public static String scope(String server) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(server.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private static byte[] key(String secret) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(checkKey(secret).getBytes(StandardCharsets.UTF_8));
    }
    private static byte[] mac(byte[] key, String label, byte[]... parts) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update(label.getBytes(StandardCharsets.US_ASCII));
        for (byte[] part : parts) mac.update(part);
        return mac.doFinal();
    }
    private static byte[] nonce() { byte[] result = new byte[32]; RANDOM.nextBytes(result); return result; }
    private static byte[] bytes(DataInputStream in, int length) throws IOException {
        byte[] result = new byte[length]; in.readFully(result); return result;
    }
    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeShort(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in, int limit) throws IOException {
        int length = in.readUnsignedShort();
        if (length < 1 || length > limit) throw new IOException("Invalid network text length");
        return new String(bytes(in, length), StandardCharsets.UTF_8);
    }
    private static void uuid(DataOutputStream out, UUID value) throws IOException { out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    public static byte[] encode(Message message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream body = new DataOutputStream(bytes);
        body.writeByte(message == null ? 0 : message instanceof Sighting ? 1 : message instanceof Exploration ? 2 : message instanceof TerrainPackets.Tile?11:message instanceof TerrainPackets.Site?12:ControlPackets.type((ControlPackets.Packet)message));
        if (message != null) {
            uuid(body, message.reporter()); text(body, message.reporterName());
            if(message instanceof ControlPackets.Packet packet)ControlPackets.encode(body,packet);
            else if(message instanceof TerrainPackets.Tile||message instanceof TerrainPackets.Site){body.writeLong(message.observedAt());TerrainPackets.encode(body,message);}
            else{
                if (message instanceof Sighting sighting) { uuid(body, sighting.player()); text(body, sighting.playerName()); }
                Endpoint point = message instanceof Sighting sighting ? sighting.point() : ((Exploration) message).point();
                text(body, point.dimension()); body.writeInt(point.x()); body.writeInt(point.y()); body.writeInt(point.z()); body.writeLong(message.observedAt());
            }
        }
        byte[] encoded=bytes.toByteArray();if(encoded.length>MAX_FRAME-16)throw new IOException("Control packet exceeds frame limit");return encoded;
    }
    public static Message decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_FRAME) throw new IOException("Oversized network message");
        try {
            DataInputStream body = new DataInputStream(new ByteArrayInputStream(bytes)); int type = body.readUnsignedByte(); Message result = null;
            if (type == 1 || type == 2) {
                UUID reporter = uuid(body); String reporterName = text(body, 16);
                UUID player = type == 1 ? uuid(body) : null; String playerName = type == 1 ? text(body, 16) : null;
                Endpoint point = new Endpoint(text(body, 128), body.readInt(), body.readInt(), body.readInt()); long time = body.readLong();
                result = type == 1 ? new Sighting(reporter, reporterName, player, playerName, point, time) : new Exploration(reporter, reporterName, point, time);
            } else if(type==11||type==12){UUID reporter=uuid(body);String name=text(body,16);result=TerrainPackets.decode(body,type,reporter,name,body.readLong());
            } else if(type>=3&&type<=10)result=ControlPackets.decode(body,type,uuid(body),text(body,16));
            else if (type != 0) throw new IOException("Unknown network message");
            if (body.available() != 0) throw new IOException("Trailing network data");
            return result;
        } catch (IllegalArgumentException|NullPointerException e) { throw new IOException("Invalid network message", e); }
    }
    private static byte[] identity(Identity who) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        text(out, who.scope()); uuid(out, who.id()); text(out, who.name()); return bytes.toByteArray();
    }
    private static Identity identity(DataInputStream in) throws IOException {
        String scope = text(in, 64); UUID id = uuid(in); return new Identity(id, text(in, 16), scope);
    }
    private static void header(DataOutputStream out) throws IOException { out.writeInt(MAGIC); out.writeInt(VERSION); }
    private static void header(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Incompatible bot network version");
    }
    private static void checkPeer(Identity local, Identity remote) throws IOException {
        if (!local.scope().equals(remote.scope())) throw new IOException("Bots are connected to different Minecraft servers/scopes");
        if (local.id().equals(remote.id())) throw new IOException("Duplicate bot UUID");
    }
    public static Channel accept(InputStream input, OutputStream output, Identity local, String secret) throws IOException {
        try {
            DataInputStream in = new DataInputStream(input); DataOutputStream out = new DataOutputStream(output);
            byte[] serverNonce = nonce(), key = key(secret), host = identity(local);
            header(out); out.write(serverNonce); out.flush();
            header(in); byte[] clientNonce = bytes(in, 32); Identity remote = identity(in); byte[] client = identity(remote);
            byte[] proof = bytes(in, 32);
            if (!MessageDigest.isEqual(proof, mac(key, "client", serverNonce, clientNonce, client))) throw new IOException("Network key rejected");
            checkPeer(local, remote);
            out.write(host); out.write(mac(key, "server", serverNonce, clientNonce, client, host)); out.flush();
            return new Channel(in, out, remote, mac(key, "client-write", serverNonce, clientNonce, client, host), mac(key, "server-write", serverNonce, clientNonce, client, host));
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw new IOException("Network authentication failed", e); }
    }
    public static Channel connect(InputStream input, OutputStream output, Identity local, String secret) throws IOException {
        try {
            DataInputStream in = new DataInputStream(input); DataOutputStream out = new DataOutputStream(output);
            header(in); byte[] serverNonce = bytes(in, 32), clientNonce = nonce(), key = key(secret), client = identity(local);
            header(out); out.write(clientNonce); out.write(client); out.write(mac(key, "client", serverNonce, clientNonce, client)); out.flush();
            Identity remote = identity(in); byte[] host = identity(remote), proof = bytes(in, 32);
            if (!MessageDigest.isEqual(proof, mac(key, "server", serverNonce, clientNonce, client, host))) throw new IOException("Host network key rejected");
            checkPeer(local, remote);
            return new Channel(in, out, remote, mac(key, "server-write", serverNonce, clientNonce, client, host), mac(key, "client-write", serverNonce, clientNonce, client, host));
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw new IOException("Network authentication failed", e); }
    }
    /** Separate reader/writer threads; directional session keys and implicit counters reject replay and reflection. */
    public static final class Channel {
        private final DataInputStream in;
        private final DataOutputStream out;
        private final byte[] readKey, writeKey;
        private final Identity remote;
        private long readSequence, writeSequence;
        private Channel(DataInputStream in, DataOutputStream out, Identity remote, byte[] readKey, byte[] writeKey) {
            this.in = in; this.out = out; this.remote = remote; this.readKey = readKey; this.writeKey = writeKey;
        }
        public Identity remote() { return remote; }
        private byte[] crypt(int mode, byte[] key, long sequence, byte[] data) throws GeneralSecurityException {
            if (sequence < 0) throw new GeneralSecurityException("Network sequence exhausted");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, ByteBuffer.allocate(12).putInt(VERSION).putLong(sequence).array()));
            return cipher.doFinal(data);
        }
        public synchronized void write(Message message) throws IOException {
            try {
                byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, writeKey, writeSequence++, encode(message));
                out.writeInt(encrypted.length); out.write(encrypted); out.flush();
            } catch (GeneralSecurityException e) { throw new IOException("Cannot encrypt network frame", e); }
        }
        /** null is a keepalive. Length is checked before allocating any peer-controlled buffer. */
        public Message read() throws IOException {
            int length = in.readInt();
            if (length < 17 || length > MAX_FRAME) throw new IOException("Invalid network frame length");
            try {
                return decode(crypt(Cipher.DECRYPT_MODE, readKey, readSequence++, bytes(in, length)));
            } catch (GeneralSecurityException | IllegalArgumentException e) { throw new IOException("Invalid encrypted network frame", e); }
        }
    }
}
