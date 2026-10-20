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

package baritone.utils.social;

import baritone.utils.bot.AtomicProperties;
import baritone.utils.mining.WorldRoutes.Endpoint;
import java.nio.file.Path;
import java.util.*;

/** Per-server observations keyed by authenticated UUID, never by a file path supplied as a username. */
public final class PlayerMemory {
    public record Place(UUID player, String name, Endpoint point, int visits, long observedSeconds,
                        long firstSeen, long lastSeen, boolean confirmedBase) {
        public Place {
            Objects.requireNonNull(player); Objects.requireNonNull(point);
            if (name == null || !name.matches("[A-Za-z0-9_]{1,16}") || visits < 1 || observedSeconds < 0
                    || firstSeen < 0 || lastSeen < firstSeen) throw new IllegalArgumentException("Invalid player observation");
        }
        public boolean possibleBase() { return !confirmedBase && StealthPolicy.frequentPlace(visits,observedSeconds); }
        public String label() { return confirmedBase ? "confirmed base" : possibleBase() ? "frequent spot / possible base" : "sighting"; }
    }
    private static final long DAY = 86400000L;
    private final Path file;
    private final List<Place> places = new ArrayList<>();
    private UUID reportTo;

    public PlayerMemory(Path root) {
        file = root.resolve("player-sightings.properties"); Properties values = AtomicProperties.read(file);
        try {
            int count = Integer.parseInt(values.getProperty("count","0"));
            if (count < 0 || count > 512) throw new IllegalArgumentException("Observation limit exceeded");
            for (int i=0;i<count;i++) {
                String key="place."+i+".";
                places.add(new Place(UUID.fromString(values.getProperty(key+"uuid")),values.getProperty(key+"name"),
                        new Endpoint(values.getProperty(key+"dimension"),Integer.parseInt(values.getProperty(key+"x")),
                                Integer.parseInt(values.getProperty(key+"y")),Integer.parseInt(values.getProperty(key+"z"))),
                        Integer.parseInt(values.getProperty(key+"visits")),Long.parseLong(values.getProperty(key+"seconds")),
                        Long.parseLong(values.getProperty(key+"first")),Long.parseLong(values.getProperty(key+"last")),
                        Boolean.parseBoolean(values.getProperty(key+"base","false"))));
            }
            String reporter=values.getProperty("reportTo","");if(!reporter.isEmpty())reportTo=UUID.fromString(reporter);
        } catch(RuntimeException error) { throw new IllegalStateException("Invalid player-sightings.properties; correct it before recording more observations",error); }
    }
    public synchronized UUID reportTo() { return reportTo; }
    public synchronized void reportTo(UUID recipient) { save(places,recipient); reportTo=recipient; }
    public synchronized List<Place> all(long now, int retentionDays) {
        long oldest=now-StealthPolicy.bounded(retentionDays,1,90)*DAY;
        return places.stream().filter(place->place.confirmedBase()||place.lastSeen()>=oldest)
                .sorted(Comparator.comparingLong(Place::lastSeen).reversed()).toList();
    }
    public synchronized UUID resolve(String name, long now, int retentionDays) {
        return all(now,retentionDays).stream().filter(place->place.name().equalsIgnoreCase(name)).map(Place::player).findFirst().orElse(null);
    }
    public synchronized List<Place> places(UUID player, String dimension, long now, int retentionDays) {
        return all(now,retentionDays).stream().filter(place->place.player().equals(player)&&place.point().dimension().equals(dimension))
                .sorted(Comparator.comparingInt((Place p)->p.confirmedBase()?0:p.possibleBase()?1:2)
                        .thenComparing(Comparator.comparingLong(Place::lastSeen).reversed())).limit(12).toList();
    }
    public synchronized Place observe(UUID player, String name, Endpoint point, long now, int observedSeconds, int retentionDays) {
        List<Place> updated=new ArrayList<>(all(now,retentionDays)); Place previous=null;
        for(Place place:updated)if(place.player().equals(player)&&place.point().dimension().equals(point.dimension())&&distanceSquared(place.point(),point)<=32*32
                &&(previous==null||distanceSquared(place.point(),point)<distanceSquared(previous.point(),point)))previous=place;
        Place next;
        if(previous!=null) {
            updated.remove(previous);boolean returnVisit=now-previous.lastSeen()>=120000;
            next=new Place(player,name,previous.point(),previous.visits()+(returnVisit?1:0),
                    Math.min(365*86400L,previous.observedSeconds()+(returnVisit?0:StealthPolicy.bounded(observedSeconds,0,30))),
                    previous.firstSeen(),Math.max(now,previous.lastSeen()),previous.confirmedBase());
        } else next=new Place(player,name,point,1,0,now,now,false);
        updated.add(next);trim(updated);save(updated,reportTo);places.clear();places.addAll(updated);return next;
    }
    public synchronized void confirm(UUID player,String name,Endpoint point,long now,int retentionDays) {
        List<Place> updated=new ArrayList<>(all(now,retentionDays));
        updated.removeIf(place->place.player().equals(player)&&place.point().dimension().equals(point.dimension())&&distanceSquared(place.point(),point)<=32*32);
        updated.add(new Place(player,name,point,3,180,now,now,true));trim(updated);save(updated,reportTo);places.clear();places.addAll(updated);
    }
    public synchronized void forget(UUID player) {
        List<Place> updated=new ArrayList<>(places);updated.removeIf(place->player==null||place.player().equals(player));save(updated,reportTo);places.clear();places.addAll(updated);
    }
    private static void trim(List<Place> values) {
        values.sort(Comparator.comparingLong(Place::lastSeen).reversed()); Map<UUID,Integer> counts=new HashMap<>();
        values.removeIf(place->counts.merge(place.player(),1,Integer::sum)>12);
        while(values.size()>512)values.remove(values.size()-1);
    }
    private static double distanceSquared(Endpoint a,Endpoint b) {
        double dx=(double)a.x()-b.x(),dy=(double)a.y()-b.y(),dz=(double)a.z()-b.z();return dx*dx+dy*dy+dz*dz;
    }
    private void save(List<Place> observations,UUID reporter) {
        Properties values=new Properties();values.setProperty("count",""+observations.size());if(reporter!=null)values.setProperty("reportTo",reporter.toString());
        for(int i=0;i<observations.size();i++) {
            Place p=observations.get(i);String k="place."+i+".";
            values.setProperty(k+"uuid",p.player().toString());values.setProperty(k+"name",p.name());values.setProperty(k+"dimension",p.point().dimension());
            values.setProperty(k+"x",""+p.point().x());values.setProperty(k+"y",""+p.point().y());values.setProperty(k+"z",""+p.point().z());
            values.setProperty(k+"visits",""+p.visits());values.setProperty(k+"seconds",""+p.observedSeconds());
            values.setProperty(k+"first",""+p.firstSeen());values.setProperty(k+"last",""+p.lastSeen());values.setProperty(k+"base",""+p.confirmedBase());
        }
        AtomicProperties.write(file,values);
    }
}
