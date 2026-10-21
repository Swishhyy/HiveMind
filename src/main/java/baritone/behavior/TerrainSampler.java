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
import baritone.utils.mining.ConstructionPolicy;
import baritone.utils.mining.MiningRouteAccess;
import baritone.utils.network.TerrainPackets.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Samples one already-loaded chunk per half second; never asks the world to generate or load a chunk. */
final class TerrainSampler {
    private final NetworkBehavior parent;
    private final Baritone bot;
    private final Map<String,Long> sampled=new HashMap<>();
    private long next;
    TerrainSampler(NetworkBehavior parent,Baritone bot){this.parent=parent;this.bot=bot;}
    void tick(long now){
        if(now<next)return;next=now+500;var ctx=bot.getPlayerContext();var world=ctx.world();String dimension=MiningRouteAccess.dimension(ctx);
        int cx=Math.floorDiv(ctx.playerFeet().getX(),16),cz=Math.floorDiv(ctx.playerFeet().getZ(),16),bestX=0,bestZ=0;double best=Double.POSITIVE_INFINITY;boolean found=false;
        sampled.values().removeIf(time->now-time>120000);
        for(int x=cx-4;x<=cx+4;x++)for(int z=cz-4;z<=cz+4;z++){
            BlockPos point=new BlockPos(x*16,ctx.playerFeet().getY(),z*16);if(!world.hasChunkAt(point))continue;
            String key=dimension+":"+x+":"+z;long previous=sampled.getOrDefault(key,0L);if(now-previous<30000)continue;
            double score=(previous==0?0:1000)+(x-cx)*(x-cx)+(z-cz)*(z-cz);if(score<best){best=score;bestX=x;bestZ=z;found=true;}
        }
        if(!found)return;sampled.put(dimension+":"+bestX+":"+bestZ,now);
        int[] colors=new int[256],heights=new int[256];
        boolean ceiling=dimension.equals("minecraft:the_nether")&&ctx.playerFeet().getY()<128;
        for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            int wx=bestX*16+x,wz=bestZ*16+z,y=world.getHeight(Heightmap.Types.WORLD_SURFACE,wx,wz)-1;
            if(ceiling){
                y=Math.min(120,ctx.playerFeet().getY()+24);
                while(y>world.getMinY()&&!world.getBlockState(new BlockPos(wx,y,wz)).isAir())y--;
                while(y>world.getMinY()&&world.getBlockState(new BlockPos(wx,y,wz)).isAir())y--;
            }
            BlockPos pos=new BlockPos(wx,y,wz);var state=world.getBlockState(pos);
            colors[z*16+x]=state.getMapColor(world,pos).col&0xffffff;heights[z*16+x]=y;
        }
        StringBuilder rgb=new StringBuilder(1536),h=new StringBuilder(1024);
        for(int i=0;i<256;i++){rgb.append(String.format(Locale.ROOT,"%06x",colors[i]));h.append(String.format(Locale.ROOT,"%04x",heights[i]&0xffff));}
        var who=parent.localIdentity();parent.record(new Tile(who.id(),who.name(),dimension,bestX,bestZ,rgb.toString(),h.toString(),now));
        int checked=0;for(BlockPos pos:world.getChunk(bestX,bestZ).getBlockEntities().keySet()){
            if(++checked>64)break;if(pos.distSqr(ctx.playerFeet())>48*48)continue;
            var state=world.getBlockState(pos);String block=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            if(!ConstructionPolicy.isContainer(block)&&!block.endsWith("_bed")&&!block.equals("furnace")&&!block.equals("blast_furnace")&&!block.equals("smoker"))continue;
            var ray=world.clip(new ClipContext(ctx.playerHead(),Vec3.atCenterOf(pos),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,ctx.player()));
            if(ray.getType()==HitResult.Type.BLOCK&&ray.getBlockPos().equals(pos)){
                parent.record(new Site(who.id(),who.name(),MiningRouteAccess.endpoint(ctx,pos),"Possible base","Visible "+block.replace('_',' ')+"; could also be a generated structure",false,now));break;
            }
        }
    }
}
