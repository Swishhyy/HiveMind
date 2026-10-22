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
import hivemind.utils.network.NetworkProtocol.Identity;
import hivemind.utils.network.TeamPackets.*;
import java.util.*;

/** Host arbitrates ore-vein leases; deterministic roles and spacing use the same authenticated plan. */
public final class Teamwork {
    private final long notBefore;
    public Teamwork(){this(0);}
    public Teamwork(long notBefore){this.notBefore=notBefore;}
    private final Map<UUID,Intent> intents=new HashMap<>();
    private final Map<UUID,Lease> leases=new HashMap<>();
    public void accept(Intent intent,long now){
        Intent previous=intents.get(intent.reporter());
        if(intent.valid(now)&&(previous==null||intent.observedAt()>previous.observedAt()))intents.put(intent.reporter(),intent);
    }
    public Plan plan(Identity host,List<Identity> online,long now){
        Map<UUID,String> members=new LinkedHashMap<>();members.put(host.id(),host.name());online.forEach(id->members.put(id.id(),id.name()));
        intents.values().removeIf(intent->!intent.valid(now)||!members.containsKey(intent.reporter()));
        // Explicit release is immediate. A disconnected holder retains its grace period, so stale grants cannot overlap.
        leases.entrySet().removeIf(entry->{
            Intent intent=intents.get(entry.getKey());
            return now>entry.getValue().until()+NetworkProtocol.CLOCK_SKEW_MS||intent!=null&&(intent.kind()!=Kind.MINE||!entry.getValue().point().equals(intent.work()));
        });
        List<Intent> ordered=intents.values().stream().sorted(Comparator.comparing(p->p.reporter().toString())).toList();
        for(Intent intent:ordered){
            if(intent.kind()!=Kind.MINE||intent.work()==null)continue;
            Lease existing=leases.get(intent.reporter());
            if(existing!=null){leases.put(intent.reporter(),new Lease(existing.point(),now+4000));continue;}
            if(now>=notBefore&&leases.size()<64&&leases.values().stream().noneMatch(lease->near(lease.point(),intent.work(),6)))leases.put(intent.reporter(),new Lease(intent.work(),now+4000));
        }
        return new Plan(host.id(),host.name(),members,ordered,leases,now);
    }
    public static boolean near(Endpoint a,Endpoint b,double radius){
        if(a==null||b==null||!a.dimension().equals(b.dimension()))return false;
        double x=(double)a.x()-b.x(),y=(double)a.y()-b.y(),z=(double)a.z()-b.z();return x*x+y*y+z*z<=radius*radius;
    }
    public static List<Intent> group(Plan plan,Kind kind,UUID target,String dimension){
        return plan.intents().stream().filter(p->p.kind()==kind&&Objects.equals(p.target(),target)&&p.point().dimension().equals(dimension))
                .sorted(Comparator.comparing(p->p.reporter().toString())).toList();
    }
    public static UUID blastLeader(Plan plan,UUID target,String dimension){
        return group(plan,Kind.HUNT,target,dimension).stream().filter(Intent::explosive).map(Intent::reporter).findFirst().orElse(null);
    }
    public static boolean clearStrike(double sx,double sz,double tx,double tz,double ax,double az,boolean sweep){
        double dx=tx-sx,dz=tz-sz,den=dx*dx+dz*dz,t=den<0.001?0:Math.max(0,Math.min(1,((ax-sx)*dx+(az-sz)*dz)/den));
        double cx=sx+t*dx-ax,cz=sz+t*dz-az,ex=tx-ax,ez=tz-az;
        return cx*cx+cz*cz>0.81&&(!sweep||ex*ex+ez*ez>9);
    }
}
