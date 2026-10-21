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

package baritone.behavior;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.RenderEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.Helper;
import baritone.utils.IRenderer;
import baritone.utils.mining.MiningRouteAccess;
import baritone.utils.network.*;
import baritone.utils.network.NetworkProtocol.*;
import baritone.utils.social.StealthPolicy;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** The only network adapter that reads the Minecraft world. It snapshots observations on the client thread. */
public final class NetworkBehavior extends Behavior {
    private final SharedSightings marks = new SharedSightings();
    private final ClientNetworkKey clientKey;
    private final NetworkTeamwork team;
    private final Map<UUID, Sighting> published = new HashMap<>();
    private final ExecutorService saves = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, "Baritone-atlas-save"); thread.setDaemon(true); return thread; });
    private final Set<NetworkAtlas> saving = ConcurrentHashMap.newKeySet();
    private Path root;
    private NetworkConfig config;
    private NetworkAtlas atlas;
    private TerrainStore terrain;
    private TerrainSampler sampler;
    private volatile boolean savingTerrain;
    private NetworkControls controls;
    private BotNetwork network;
    private NetworkConfig.Options started;
    private LocalNetworkViewer viewer;
    private Exploration lastExploration;
    private boolean sharingSightings, sharingExploration;
    private long lastScan, lastPage, lastSave, nextError, nextSetup, nextWebAttempt;
    private int scanCursor;
    private String webError = "";
    private volatile String storageError;
    public NetworkBehavior(Baritone bot) { super(bot);clientKey=new ClientNetworkKey(bot.getDirectory());team=new NetworkTeamwork(this,bot); }
    public NetworkConfig config() {
        Path current = MiningRouteAccess.root(ctx);
        if (!current.equals(root)) {
            NetworkConfig next = new NetworkConfig(current,clientKey.key()); NetworkAtlas history = new NetworkAtlas(current);TerrainStore surface=new TerrainStore(current);
            clearWorld(); config = next; atlas = history;terrain=surface;sampler=new TerrainSampler(this,baritone); root = current;
        }
        if(controls==null)controls=new NetworkControls(this,baritone,current);
        return config;
    }
    public void configure(NetworkConfig.Options options) {
        config().save(options); nextSetup = nextWebAttempt = 0; ensure();
    }
    public void reload() {
        Path current = MiningRouteAccess.root(ctx); NetworkConfig next = new NetworkConfig(current,clientKey.key());
        config = next; nextSetup = nextWebAttempt = 0; ensure();
    }
    Identity localIdentity() {
        String server;
        if (!config.options().scope().isEmpty()) server = "server-label:" + config.options().scope();
        else {
            var current = ctx.minecraft().getCurrentServer();
            server = current == null ? "singleplayer:" + root : "server:" + current.ip.strip().toLowerCase(Locale.ROOT).replaceFirst(":25565$", "");
        }
        return new Identity(ctx.player().getUUID(), ctx.player().getGameProfile().name(), NetworkProtocol.scope(server));
    }
    private boolean sameTransport(NetworkConfig.Options a, NetworkConfig.Options b) {
        return a != null && a.mode() == b.mode() && a.key().equals(b.key()) && a.host().equals(b.host()) && a.port() == b.port() && a.bind().equals(b.bind()) && a.scope().equals(b.scope());
    }
    private void ensure() {
        config(); NetworkConfig.Options options = config.options(); Identity who = localIdentity();
        boolean sightings = Baritone.settings().networkShareSightings.value, exploration = Baritone.settings().networkShareExploration.value;
        if (!sameTransport(started, options) || sharingSightings != sightings || sharingExploration != exploration || network != null && !network.identity().equals(who)) {
            if(started!=null&&(!started.key().equals(options.key())||!started.scope().equals(options.scope())||options.mode()==NetworkConfig.Mode.OFF))team.reset(true);
            stopNetwork(); started = options; sharingSightings = sightings; sharingExploration = exploration;
            List<Message> retained=new ArrayList<>(atlas.snapshot(System.currentTimeMillis()));retained.addAll(terrain.snapshot(System.currentTimeMillis()));
            List<Message> history = retained.stream().filter(message ->
                    !message.reporter().equals(who.id()) || (message instanceof Sighting ? sightings : exploration)).toList();
            network = switch (options.mode()) {
                case OFF -> null;
                case HOST -> BotNetwork.host(who, options.key(), options.bind(), options.port(), history);
                case JOIN -> BotNetwork.join(who, options.key(), options.host(), options.port(), history);
            };
        }
        if (viewer != null && viewer.port() != options.webPort()) { viewer.close(); viewer = null; }
        if (options.webPort() != 0 && viewer == null && System.currentTimeMillis() >= nextWebAttempt) {
            try { NetworkControls owner=controls;viewer = new LocalNetworkViewer(options.webPort(),owner::enqueue);viewer.terrain(terrain); webError = ""; }
            catch (IOException e) { webError = "Cannot start local page: " + e.getMessage(); nextWebAttempt = System.currentTimeMillis() + 30000; }
        }
    }
    public String status() {
        return (network == null ? "Network off" : network.status()) + "; " + marks.all(System.currentTimeMillis(), ttl()).size() + " recent peer marks"
                + (viewer != null ? "; local page on port " + viewer.port() : webError.isEmpty() ? "" : "; " + webError);
    }
    public String pageUrl() { if (viewer == null) throw new IllegalStateException(webError.isEmpty() ? "Use network web [port] to start the local page" : webError); return viewer.url(); }
    public List<Identity> peers() { return network == null ? List.of() : network.peers(); }
    BotNetwork transport(){return network;}
    public NetworkTeamwork team(){return team;}
    public boolean isTeammate(UUID id){return team.ally(id);}
    public boolean visited(net.minecraft.core.BlockPos point){return atlas!=null&&atlas.visited(MiningRouteAccess.dimension(ctx),Math.floorDiv(point.getX(),16),Math.floorDiv(point.getZ(),16));}
    public List<Sighting> sightings(String name) {
        return marks.all(System.currentTimeMillis(), ttl()).stream().filter(s -> name == null || s.playerName().equalsIgnoreCase(name)).limit(32).toList();
    }
    public Sighting hint(UUID player, String dimension) { return marks.latest(player, dimension, System.currentTimeMillis(), ttl()); }
    public UUID resolve(String name) { return marks.resolve(name, System.currentTimeMillis(), ttl()); }
    private long ttl() { return StealthPolicy.bounded(Baritone.settings().networkSightingTTLMS.value, 10000L, NetworkProtocol.MAX_AGE_MS); }
    public void clearHistory() {
        marks.clear(); published.clear(); lastExploration = null;
        if(terrain!=null)terrain.clear();
        if (atlas != null) { atlas.clear(); save(atlas); }
        stopNetwork(); started = null; // Rebuild the relay's replay cache from the cleared atlas.
    }
    void record(Message message){
        long now=System.currentTimeMillis();if(!terrain.accept(message,now))return;
        if(network!=null&&Baritone.settings().networkShareExploration.value)network.publish(message);
    }
    public void confirmSite(net.minecraft.core.BlockPos point,String name){
        config();var who=localIdentity();record(new TerrainPackets.Site(who.id(),who.name(),MiningRouteAccess.endpoint(ctx,point),name+" base","Manually confirmed by a controller",true,System.currentTimeMillis()));
    }
    public List<TerrainPackets.Marker> markers(){config();return terrain.markers(System.currentTimeMillis());}
    public TerrainPackets.Marker addMarker(String kind,String label,String detail,baritone.utils.mining.WorldRoutes.Endpoint point,UUID subject,long seenAt){
        config();var who=localIdentity();long now=System.currentTimeMillis();
        UUID id=kind.equals("logout")?UUID.nameUUIDFromBytes(("logout:"+subject+":"+point.dimension()).getBytes(java.nio.charset.StandardCharsets.UTF_8)):UUID.randomUUID();
        var marker=new TerrainPackets.Marker(who.id(),who.name(),id,kind,point,label,detail,subject,seenAt,false,now);record(marker);saveTerrain();return marker;
    }
    public void removeMarker(UUID id){
        config();var who=localIdentity();var old=terrain.marker(who.id(),id);
        if(old==null||old.deleted())throw new IllegalArgumentException("No marker with that ID belongs to this bot; select its source bot");
        record(new TerrainPackets.Marker(who.id(),who.name(),id,old.kind(),old.point(),old.label(),old.detail(),old.subject(),old.seenAt(),true,Math.max(System.currentTimeMillis(),old.observedAt()+1)));saveTerrain();
    }
    public TerrainPackets.Marker logout(UUID player){return markers().stream().filter(p->p.kind().equals("logout")&&player.equals(p.subject())&&p.point().dimension().equals(MiningRouteAccess.dimension(ctx))).findFirst().orElse(null);}
    private void saveTerrain(){
        TerrainStore current=terrain;if(current==null||!current.dirty()||savingTerrain)return;savingTerrain=true;
        saves.execute(()->{try{current.save();}catch(RuntimeException error){storageError=error.getMessage();}finally{savingTerrain=false;}});
    }
    private void save(NetworkAtlas history) {
        if (!history.dirty() || !saving.add(history)) return;
        saves.execute(() -> { try { do { history.save(); } while (history.dirty()); } catch (RuntimeException e) { storageError = e.getMessage(); } finally { saving.remove(history); } });
    }
    private void stopNetwork() { team.reset(false);if (network != null) network.close(); network = null; marks.clear(); published.clear(); lastExploration = null;if(controls!=null)controls.connectionChanged(); }
    private void closeRuntime() { stopNetwork(); started = null; if (viewer != null) viewer.close(); viewer = null; }
    private void clearWorld() {
        if (atlas != null) save(atlas);saveTerrain();
        closeRuntime();team.reset(true);if(controls!=null)controls.close();controls=null;root = null; config = null; atlas = null; lastScan = lastPage = lastSave = 0; scanCursor = 0; webError = "";
    }
    @Override public void onTick(TickEvent event) {
        if (event.getState() != EventState.PRE) return;
        if (event.getType() == TickEvent.Type.OUT || ctx.world() == null || ctx.player() == null || ctx.minecraft().getConnection() == null) {
            if (ctx.minecraft().getConnection() == null) clearWorld(); else closeRuntime();
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextSetup) return;
        try {
            ensure();
            if (network != null) for (Message message : network.drain(128)) {
                if(message instanceof ControlPackets.Packet packet){team.accept(packet,now);controls.accept(packet,now);continue;}
                atlas.accept(message, now);
                terrain.accept(message,now);
                if (message instanceof Sighting sighting && !sighting.reporter().equals(ctx.player().getUUID())) marks.accept(sighting, now, ttl());
            }
            team.tick(now);controls.tick(now);
            if(ctx.player().isAlive()&&(network!=null||viewer!=null||baritone.getRoamProcess().isActive()))sampler.tick(now);
            if ((network != null || viewer != null || baritone.getRoamProcess().isActive()) && ctx.player().isAlive() && now - lastScan >= 1000) { scan(now); lastScan = now; }
            if (viewer != null && now - lastPage >= 1000) { viewer.snapshot(atlas.json(now, network == null ? "Local observations · network off" : network.status(), MiningRouteAccess.dimension(ctx), peers()));viewer.controls(controls.json(now));lastPage = now; }
            if (now - lastSave >= 30000) { save(atlas);saveTerrain(); lastSave = now; }
            if (storageError != null) { String problem = storageError; storageError = null; throw new IllegalStateException(problem); }
        } catch (RuntimeException e) {
            nextSetup = now + 30000;
            if (now >= nextError) { Helper.HELPER.logDirect("Bot network: " + e.getMessage()); nextError = now + 30000; }
        }
    }
    private void scan(long now) {
        Identity who = localIdentity(); var point = MiningRouteAccess.endpoint(ctx, ctx.playerFeet());
        if (lastExploration == null || now - lastExploration.observedAt() >= 5000 || !lastExploration.point().dimension().equals(point.dimension())
                || Math.floorDiv(lastExploration.point().x(), 16) != Math.floorDiv(point.x(), 16) || Math.floorDiv(lastExploration.point().z(), 16) != Math.floorDiv(point.z(), 16)) {
            lastExploration = new Exploration(who.id(), who.name(), point, now); atlas.accept(lastExploration, now);
            if (network != null && Baritone.settings().networkShareExploration.value) network.publish(lastExploration);
        }
        published.values().removeIf(s -> now - s.observedAt() > NetworkProtocol.MAX_AGE_MS);
        List<Player> players = new ArrayList<>(ctx.world().players());
        int sent = 0;
        for (int checked = 0; checked < players.size() && sent < 32; checked++) {
            Player player = players.get(Math.floorMod(scanCursor++, players.size()));
            if (player == ctx.player() || isTeammate(player.getUUID()) || !player.isAlive() || player.isSpectator() || player.getEffect(MobEffects.INVISIBILITY) != null || !ctx.player().hasLineOfSight(player)) continue;
            Sighting previous = published.get(player.getUUID()); var location = MiningRouteAccess.endpoint(ctx, player.blockPosition());
            long interval = StealthPolicy.bounded(Baritone.settings().networkSightingIntervalMS.value, 2000L, 60000L);
            if (previous != null && previous.point().dimension().equals(location.dimension()) && now - previous.observedAt() < interval
                    && (now - previous.observedAt() < 2000 || distance(previous, location) < 64)) continue;
            Sighting sighting = new Sighting(who.id(), who.name(), player.getUUID(), player.getGameProfile().name(), location, now);
            atlas.accept(sighting, now); published.put(player.getUUID(), sighting); sent++;
            if (network != null && Baritone.settings().networkShareSightings.value) network.publish(sighting);
        }
    }
    private double distance(Sighting sighting, baritone.utils.mining.WorldRoutes.Endpoint p) {
        double dx = (double) sighting.point().x() - p.x(), dy = (double) sighting.point().y() - p.y(), dz = (double) sighting.point().z() - p.z();
        return dx * dx + dy * dy + dz * dz;
    }
    @Override public void onRenderPass(RenderEvent event) {
        if (!Baritone.settings().networkRenderSightings.value || ctx.world() == null || ctx.player() == null
                || BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().world() != ctx.world()) return;
        String dimension = MiningRouteAccess.dimension(ctx);
        List<Sighting> visible = sightings(null).stream().filter(s -> s.point().dimension().equals(dimension) && !s.player().equals(ctx.player().getUUID())).toList();
        if (visible.isEmpty()) return;
        var buffer = IRenderer.startLines(new Color(100, 210, 245), .6f);
        for (Sighting sighting : visible) {
            var p = sighting.point();
            IRenderer.emitAABB(buffer, event.getModelViewStack(), new AABB(p.x() + .2, p.y(), p.z() + .2, p.x() + .8, p.y() + 1.8, p.z() + .8), .002, Baritone.settings().pathRenderLineWidthPixels.value);
        }
        IRenderer.endLines(buffer, true);
    }
}
