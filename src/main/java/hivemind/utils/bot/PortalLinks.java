/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;

import hivemind.utils.mining.WorldRoutes;
import hivemind.utils.mining.WorldRoutes.Endpoint;
import java.nio.file.Path;
import java.util.*;

/** Actual observed portal exits, scoped to one server/save. Unobserved coordinate scaling is never a link. */
public final class PortalLinks {
    public record Link(Endpoint from, Endpoint to, long seen) {}
    private final Path file;
    private final List<Link> links = new ArrayList<>();

    public PortalLinks(Path root) {
        file = root.resolve("portal-links.properties");
        Properties saved = AtomicProperties.read(file);
        int count = Math.min(64, Math.max(0, number(saved.getProperty("count"), 0)));
        for (int i = 0; i < count; i++) try {
            Endpoint from = endpoint(saved, "link." + i + ".from");
            Endpoint to = endpoint(saved, "link." + i + ".to");
            long seen = Long.parseLong(saved.getProperty("link." + i + ".seen"));
            if (WorldRoutes.connects(from.dimension(), to.dimension()) && seen >= 0 && seen <= System.currentTimeMillis() + 60000)
                links.add(new Link(from, to, seen));
        } catch (RuntimeException ignored) { /* A corrupt observation cannot invent a route. */ }
    }

    public synchronized List<Link> recent() {
        return links.stream().sorted(Comparator.comparingLong(Link::seen).reversed()).toList();
    }

    public synchronized void record(Endpoint from, Endpoint to) {
        if (!WorldRoutes.connects(from.dimension(), to.dimension())) throw new IllegalArgumentException("Unconnected portal dimensions");
        // An entrance may lead to a new exit after a server changes its portal network.
        links.removeIf(link -> link.from().equals(from));
        links.add(new Link(from, to, System.currentTimeMillis()));
        if (links.size() > 64) links.remove(links.stream().min(Comparator.comparingLong(Link::seen)).orElseThrow());
        save();
    }

    public synchronized Endpoint best(String source, String destination, Endpoint here, Endpoint goal) {
        return links.stream().filter(link -> link.from().dimension().equals(source) && link.to().dimension().equals(destination))
                .min(Comparator.comparingDouble(link -> distance(here, link.from()) + distance(goal, link.to())))
                .map(Link::from).orElse(null);
    }

    public synchronized void forgetDimension(String dimension) {
        if (links.removeIf(link -> link.from().dimension().equals(dimension) || link.to().dimension().equals(dimension))) save();
    }

    private static double distance(Endpoint a, Endpoint b) {
        if (a == null || !a.dimension().equals(b.dimension())) return 0;
        return Math.hypot(Math.hypot((double) a.x() - b.x(), (double) a.z() - b.z()), (double) a.y() - b.y());
    }
    private static int number(String value, int fallback) { try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return fallback; } }
    private static Endpoint endpoint(Properties p, String prefix) {
        return new Endpoint(p.getProperty(prefix + ".dimension"), Integer.parseInt(p.getProperty(prefix + ".x")),
                Integer.parseInt(p.getProperty(prefix + ".y")), Integer.parseInt(p.getProperty(prefix + ".z")));
    }
    private static void put(Properties p, String prefix, Endpoint value) {
        p.setProperty(prefix + ".dimension", value.dimension()); p.setProperty(prefix + ".x", "" + value.x());
        p.setProperty(prefix + ".y", "" + value.y()); p.setProperty(prefix + ".z", "" + value.z());
    }
    private void save() {
        Properties p = new Properties(); p.setProperty("count", "" + links.size());
        for (int i = 0; i < links.size(); i++) {
            Link link = links.get(i); String prefix = "link." + i;
            put(p, prefix + ".from", link.from()); put(p, prefix + ".to", link.to()); p.setProperty(prefix + ".seen", "" + link.seen());
        }
        AtomicProperties.write(file, p);
    }
}
