/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;
import hivemind.api.utils.IPlayerContext;
import hivemind.utils.network.BotBaseStore;
import hivemind.utils.mining.*;
import hivemind.process.DepositProcess;
import net.minecraft.core.BlockPos;
import java.util.*;
/** Discovers only loaded supported containers inside an explicitly registered base. */
public final class BotBaseAccess {
    private BotBaseAccess(){}
    public static BotBaseStore store(IPlayerContext ctx){return new BotBaseStore(MiningRouteAccess.root(ctx));}
    public static int scan(IPlayerContext ctx){var base=store(ctx).get();if(base==null||!base.point().dimension().equals(MiningRouteAccess.dimension(ctx)))return 0;var center=base.point();int radius=base.radius(),found=0;var routes=MiningRouteAccess.store(ctx);
        for(int x=Math.floorDiv(center.x()-radius,16);x<=Math.floorDiv(center.x()+radius,16);x++)for(int z=Math.floorDiv(center.z()-radius,16);z<=Math.floorDiv(center.z()+radius,16);z++){
            BlockPos sample=new BlockPos(x*16,center.y(),z*16);if(!ctx.world().hasChunkAt(sample))continue;
            for(BlockPos p:ctx.world().getChunk(x,z).getBlockEntities().keySet()){
                var endpoint=MiningRouteAccess.endpoint(ctx,p);if(!base.contains(endpoint)||!DepositProcess.supports(ctx.world().getBlockState(p).getBlock()))continue;
                var existing=routes.deposits().stream().filter(s->s.endpoint().equals(endpoint)).findFirst().orElse(null);
                if(existing==null){if(routes.deposits().size()>=64)continue;var saved=routes.addDeposit(endpoint);routes.configureStorage(saved.id(),new StorageRule("Bot base #"+saved.id(),StorageRule.Kind.SUPPLIES,Set.of(),50));}
                found++;
            }
        }return found;
    }
}
