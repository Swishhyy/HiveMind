/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;
import hivemind.api.utils.IPlayerContext;
import hivemind.HiveMind;
import net.minecraft.world.level.block.Blocks;
import hivemind.utils.network.BotBaseStore;
import hivemind.utils.mining.*;
import hivemind.process.DepositProcess;
import net.minecraft.core.BlockPos;
import java.util.*;
/** Discovers only loaded supported containers inside an explicitly registered base. */
public final class BotBaseAccess {
    private BotBaseAccess(){}
    public static BotBaseStore store(IPlayerContext ctx){return new BotBaseStore(MiningRouteAccess.root(ctx));}
    public static int discoverEnderChests(IPlayerContext ctx){
        if(!HiveMind.settings().botUseEnderChests.value)return 0;var feet=ctx.playerFeet();var routes=MiningRouteAccess.store(ctx);int found=0;
        for(int x=Math.floorDiv(feet.getX()-8,16);x<=Math.floorDiv(feet.getX()+8,16);x++)for(int z=Math.floorDiv(feet.getZ()-8,16);z<=Math.floorDiv(feet.getZ()+8,16);z++){
            if(!ctx.world().hasChunkAt(new BlockPos(x*16,feet.getY(),z*16)))continue;
            for(var point:ctx.world().getChunk(x,z).getBlockEntities().keySet())if(point.distSqr(feet)<=64&&ctx.world().getBlockState(point).is(Blocks.ENDER_CHEST)){
                var endpoint=MiningRouteAccess.endpoint(ctx,point);if(routes.deposits().stream().anyMatch(s->s.endpoint().equals(endpoint)))continue;if(routes.deposits().size()>=64)return found;
                var saved=routes.addDeposit(endpoint);routes.configureStorage(saved.id(),new StorageRule("Private ender chest",StorageRule.Kind.SUPPLIES,Set.of(),40));found++;
            }
        }return found;
    }
    public static int scan(IPlayerContext ctx){int found=0;for(var base:store(ctx).all().values())found+=scan(ctx,base);return found;}
    private static int scan(IPlayerContext ctx,BotBaseStore.Base base){if(!base.point().dimension().equals(MiningRouteAccess.dimension(ctx)))return 0;var center=base.point();int radius=base.radius(),found=0;var routes=MiningRouteAccess.store(ctx);
        for(int x=Math.floorDiv(center.x()-radius,16);x<=Math.floorDiv(center.x()+radius,16);x++)for(int z=Math.floorDiv(center.z()-radius,16);z<=Math.floorDiv(center.z()+radius,16);z++){
            BlockPos sample=new BlockPos(x*16,center.y(),z*16);if(!ctx.world().hasChunkAt(sample))continue;
            for(BlockPos p:ctx.world().getChunk(x,z).getBlockEntities().keySet()){
                var endpoint=MiningRouteAccess.endpoint(ctx,p);if(ctx.world().getBlockState(p).is(Blocks.ENDER_CHEST)&&!HiveMind.settings().botUseEnderChests.value)continue;
                if(!base.contains(endpoint)||!DepositProcess.supports(ctx.world().getBlockState(p).getBlock()))continue;
                var existing=routes.deposits().stream().filter(s->s.endpoint().equals(endpoint)).findFirst().orElse(null);
                if(existing==null){if(routes.deposits().size()>=64)continue;var saved=routes.addDeposit(endpoint);routes.configureStorage(saved.id(),new StorageRule((ctx.world().getBlockState(p).is(Blocks.ENDER_CHEST)?"Private ender chest #":"Bot base #")+saved.id(),StorageRule.Kind.SUPPLIES,Set.of(),50));}
                found++;
            }
        }return found;
    }
}
