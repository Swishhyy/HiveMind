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

package hivemind.behavior;

import hivemind.HiveMind;
import hivemind.utils.mining.ConstructionPolicy;
import hivemind.utils.mining.MiningRouteAccess;
import hivemind.utils.network.TerrainPackets.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Samples nearby loaded terrain in a small tick budget, including the sides of a fast flight. */
final class TerrainSampler {
    private final NetworkBehavior parent;
    private final HiveMind bot;
    private final Map<String,Long> sampled=new HashMap<>();
    private long next;
    private BlockPos lastPosition;
    private boolean stationary;
    private PendingTile pending;
    private static final class PendingTile {
        final Object world;final String dimension;final int x,z;final int[] colors=new int[256],heights=new int[256];int cursor;
        PendingTile(Object world,String dimension,int x,int z){this.world=world;this.dimension=dimension;this.x=x;this.z=z;}
    }
    private final Map<String,String> fingerprints=new LinkedHashMap<>();
    TerrainSampler(NetworkBehavior parent,HiveMind bot){this.parent=parent;this.bot=bot;}
    void tick(long now){
        if(now<next)return;var feet=bot.getPlayerContext().playerFeet();stationary=lastPosition!=null&&lastPosition.distSqr(feet)<4;lastPosition=feet.immutable();next=now+(stationary&&pending==null?250:50);
        if(parent.transport()!=null&&parent.transport().congested()){next=now+1000;return;}
        sampled.values().removeIf(time->now-time>300000);
        long deadline=System.nanoTime()+3_000_000;
        for(int tiles=0;tiles<8;tiles++){
            if(tiles>0&&System.nanoTime()>=deadline)return;
            if(!sample(now,deadline))return;
        }
    }
    private boolean sample(long now,long deadline){
        var ctx=bot.getPlayerContext();var world=ctx.world();String dimension=MiningRouteAccess.dimension(ctx);
        if(pending!=null&&(pending.world!=world||!dimension.equals(pending.dimension)||!world.hasChunkAt(new BlockPos(pending.x*16,ctx.playerFeet().getY(),pending.z*16))))pending=null;
        int cx=Math.floorDiv(ctx.playerFeet().getX(),16),cz=Math.floorDiv(ctx.playerFeet().getZ(),16),bestX=0,bestZ=0;double best=Double.POSITIVE_INFINITY;boolean found=false;
        int requested=(Math.max(32,Math.min(512,HiveMind.settings().botTerrainViewRange.value))+15)/16;
        int radius=Math.min(requested,Math.max(2,ctx.minecraft().options.renderDistance().get()));
        if(pending==null)for(int x=cx-radius;x<=cx+radius;x++)for(int z=cz-radius;z<=cz+radius;z++){
            BlockPos point=new BlockPos(x*16,ctx.playerFeet().getY(),z*16);if(!world.hasChunkAt(point))continue;
            String key=dimension+":"+x+":"+z;long previous=sampled.getOrDefault(key,0L);if(now-previous<(stationary?120000:30000))continue;
            double score=(previous==0?0:1000)+(x-cx)*(x-cx)+(z-cz)*(z-cz);if(score<best){best=score;bestX=x;bestZ=z;found=true;}
        }
        if(pending==null){if(!found)return false;pending=new PendingTile(world,dimension,bestX,bestZ);}
        bestX=pending.x;bestZ=pending.z;int[] colors=pending.colors,heights=pending.heights;
        boolean ceiling=dimension.equals("minecraft:the_nether")&&ctx.playerFeet().getY()<128;
        for(;pending.cursor<256;pending.cursor++){
            if(System.nanoTime()>=deadline)return true;int x=pending.cursor%16,z=pending.cursor/16;
            int wx=bestX*16+x,wz=bestZ*16+z,y=world.getHeight(Heightmap.Types.WORLD_SURFACE,wx,wz)-1;
            if(ceiling){
                y=Math.min(120,ctx.playerFeet().getY()+24);
                while(y>world.getMinY()&&!world.getBlockState(new BlockPos(wx,y,wz)).isAir())y--;
                while(y>world.getMinY()&&world.getBlockState(new BlockPos(wx,y,wz)).isAir())y--;
            }
            BlockPos pos=new BlockPos(wx,y,wz);var state=world.getBlockState(pos);
            int color=state.getMapColor(world,pos).col&0xffffff;
            // MapColor is a flat palette; block tint supplies biome grass, foliage and water colors.
            var source=ctx.minecraft().getBlockColors().getTintSource(state,0);
            int tint=source==null?-1:source.colorInWorld(state,ctx.minecraft().level,pos);
            colors[z*16+x]=(tint==-1?color:tint)&0xffffff;heights[z*16+x]=y;
        }
        // Publish a complete tile only. A budget boundary or unloaded chunk cannot introduce black partial tiles.
        pending=null;sampled.put(dimension+":"+bestX+":"+bestZ,now);
        StringBuilder rgb=new StringBuilder(1536),h=new StringBuilder(1024);
        HexFormat hex=HexFormat.of();
        for(int i=0;i<256;i++){rgb.append(hex.toHexDigits(colors[i]),2,8);h.append(hex.toHexDigits((short)heights[i]));}
        var who=parent.localIdentity();String tileKey=dimension+":"+bestX+":"+bestZ,fingerprint=rgb.toString()+h;
        if(!fingerprint.equals(fingerprints.get(tileKey))){fingerprints.put(tileKey,fingerprint);while(fingerprints.size()>4096)fingerprints.remove(fingerprints.keySet().iterator().next());parent.record(new Tile(who.id(),who.name(),dimension,bestX,bestZ,rgb.toString(),h.toString(),now));}
        int checked=0;for(BlockPos pos:world.getChunk(bestX,bestZ).getBlockEntities().keySet()){
            int range=ctx.player().isFallFlying()?96:48;
            if(++checked>64)break;if(pos.distSqr(ctx.playerFeet())>range*range)continue;
            var state=world.getBlockState(pos);String block=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            if(!ConstructionPolicy.isContainer(block)&&!block.endsWith("_bed")&&!block.equals("furnace")&&!block.equals("blast_furnace")&&!block.equals("smoker"))continue;
            var ray=world.clip(new ClipContext(ctx.playerHead(),Vec3.atCenterOf(pos),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,ctx.player()));
            if(ray.getType()==HitResult.Type.BLOCK&&ray.getBlockPos().equals(pos)){
                parent.record(new Site(who.id(),who.name(),MiningRouteAccess.endpoint(ctx,pos),"Possible base","Visible "+block.replace('_',' ')+"; could also be a generated structure",false,now));break;
            }
        }
        return true;
    }
}
