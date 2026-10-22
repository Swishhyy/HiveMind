/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;
import hivemind.utils.mining.WorldRoutes;
import java.util.*;
/** A dimension-pinned, bounded order from the atlas. Coordinates are block coordinates. */
public record MapOrder(String kind,String dimension,int x,int z,int radius) {
    public MapOrder {
        new WorldRoutes.Endpoint(dimension,x,64,z);
        if(!Set.of("go","explore").contains(kind)||kind.equals("go")&&radius!=0||kind.equals("explore")&&(radius<32||radius>2048))throw new IllegalArgumentException("Use go or explore with a radius of 32–2048 blocks");
    }
    public List<SurveyPolicy.Offset> waypoints(int spacing){
        List<SurveyPolicy.Offset> result=new ArrayList<>();result.add(new SurveyPolicy.Offset(x,z));
        if(kind.equals("explore")){
            int step=Math.min(radius,Math.max(32,Math.min(128,spacing))),rings=(int)Math.ceil((double)radius/step),count=(2*rings+1)*(2*rings+1)-1;
            for(int i=1;i<=count;i++){var p=SurveyPolicy.offset(i,step,0);long px=(long)x+p.x(),pz=(long)z+p.z();
                if(Math.hypot(p.x(),p.z())<=radius&&Math.abs(px)<=30000000&&Math.abs(pz)<=30000000)result.add(new SurveyPolicy.Offset((int)px,(int)pz));}
        }
        return List.copyOf(result);
    }
}
