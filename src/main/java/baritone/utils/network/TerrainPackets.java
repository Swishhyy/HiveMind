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
import baritone.utils.network.NetworkProtocol.Message;
import java.io.*;
import java.util.*;
import static baritone.utils.network.ControlPackets.*;

/** One surface sample per block, from chunks the client actually received. */
public final class TerrainPackets {
    public record Tile(UUID reporter,String reporterName,String dimension,int x,int z,String colors,String heights,long observedAt) implements Message {
        public Tile {
            base(reporter,reporterName);if(Math.abs((long)x)>1875000||Math.abs((long)z)>1875000)throw new IllegalArgumentException("Invalid terrain coordinate");new Endpoint(dimension,x*16,0,z*16);
            if(!colors.matches("[0-9a-f]{1536}")||!heights.matches("[0-9a-f]{1024}")||observedAt<0)throw new IllegalArgumentException("Invalid terrain tile");
        }
        public int height(int x,int z){return (short)Integer.parseInt(heights.substring((z*16+x)*4,(z*16+x)*4+4),16);}
        public int color(int x,int z){return Integer.parseInt(colors.substring((z*16+x)*6,(z*16+x)*6+6),16);}
    }
    public record Site(UUID reporter,String reporterName,Endpoint point,String label,String evidence,boolean confirmed,long observedAt) implements Message {
        public Site {base(reporter,reporterName);Objects.requireNonNull(point);check(label,64);check(evidence,128);if(observedAt<0)throw new IllegalArgumentException("Invalid site time");}
    }
    private TerrainPackets(){}
    static void encode(DataOutputStream out,Message message)throws IOException {
        if(message instanceof Tile p){text(out,p.dimension());out.writeInt(p.x());out.writeInt(p.z());text(out,p.colors());text(out,p.heights());}
        else if(message instanceof Site p){text(out,p.point().dimension());out.writeInt(p.point().x());out.writeInt(p.point().y());out.writeInt(p.point().z());text(out,p.label());text(out,p.evidence());out.writeBoolean(p.confirmed());}
    }
    static Message decode(DataInputStream in,int type,UUID reporter,String name,long time)throws IOException {
        if(type==11)return new Tile(reporter,name,text(in,128),in.readInt(),in.readInt(),text(in,1536),text(in,1024),time);
        return new Site(reporter,name,new Endpoint(text(in,128),in.readInt(),in.readInt(),in.readInt()),text(in,64),text(in,128),in.readBoolean(),time);
    }
}
