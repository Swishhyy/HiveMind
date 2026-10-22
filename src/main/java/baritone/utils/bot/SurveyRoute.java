/* This file is part of Baritone, distributed under the LGPL-3.0 license. */
package baritone.utils.bot;

import baritone.utils.mining.WorldRoutes.Endpoint;
import java.nio.file.Path;
import java.util.*;

/** Per-server, dimension and purpose checkpoints; assigning a leg does not imply seeing its terrain. */
public final class SurveyRoute {
    private final Path file;
    private final String prefix;
    private Endpoint origin;
    private long step;
    private final int spacing;
    private final double heading;
    public SurveyRoute(Path root,String purpose,Endpoint fallback,int spacing,double heading){
        file=root.resolve("bot-surveys.properties");prefix=purpose+"."+fallback.dimension()+".";this.spacing=spacing;
        Properties p=AtomicProperties.read(file);
        try{
            origin=new Endpoint(fallback.dimension(),Integer.parseInt(p.getProperty(prefix+"x",""+fallback.x())),Integer.parseInt(p.getProperty(prefix+"y",""+fallback.y())),Integer.parseInt(p.getProperty(prefix+"z",""+fallback.z())));
            step=Long.parseLong(p.getProperty(prefix+"step","0"));if(step<0||step>9_000_000_000L)throw new IllegalArgumentException();
            this.heading=Double.parseDouble(p.getProperty(prefix+"heading",""+heading));if(!Double.isFinite(this.heading))throw new IllegalArgumentException();
        }catch(IllegalArgumentException e){throw new IllegalStateException("Invalid saved survey route",e);}
    }
    public Endpoint origin(){return origin;}
    public long step(){return step;}
    public void reanchor(Endpoint next){if(!origin.dimension().equals(next.dimension()))throw new IllegalArgumentException("Survey dimension changed");origin=next;step=0;}
    public Endpoint next(){
        if(step>=9_000_000_000L)throw new IllegalStateException("Survey route reached its coordinate limit");
        SurveyPolicy.Offset offset=SurveyPolicy.offset(++step,spacing,heading);
        return new Endpoint(origin.dimension(),Math.max(-30_000_000,Math.min(30_000_000,origin.x()+offset.x())),origin.y(),Math.max(-30_000_000,Math.min(30_000_000,origin.z()+offset.z())));
    }
    public void save(){
        Properties p=AtomicProperties.read(file);
        p.setProperty(prefix+"x",""+origin.x());p.setProperty(prefix+"y",""+origin.y());p.setProperty(prefix+"z",""+origin.z());
        p.setProperty(prefix+"step",""+step);p.setProperty(prefix+"at",""+System.currentTimeMillis());
        p.setProperty(prefix+"heading",""+heading);
        List<String> routes=p.stringPropertyNames().stream().filter(key->key.endsWith(".step")).map(key->key.substring(0,key.length()-4))
                .sorted(Comparator.comparing(key->p.getProperty(key+"at","0"))).toList();
        for(int i=0;i<routes.size()-128;i++){String old=routes.get(i);p.stringPropertyNames().stream().filter(key->key.startsWith(old)).toList().forEach(p::remove);}
        AtomicProperties.write(file,p);
    }
}
