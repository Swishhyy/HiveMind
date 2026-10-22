/* This file is part of HiveMind, distributed under the LGPL-3.0 license. */
package hivemind.utils.bot;

/** Adjacent cells of an expanding square spiral cover strips instead of repeating spokes. */
public final class SurveyPolicy {
    public record Offset(int x,int z) {}
    private SurveyPolicy() {}
    public static Offset offset(long index,int configured,double heading){
        if(index<1||index>9_000_000_000L)throw new IllegalArgumentException("Survey index out of bounds");
        int spacing=Math.max(32,Math.min(512,configured));
        long ring=(long)Math.ceil((Math.sqrt(index+1)-1)/2),side=ring*2;
        long n=index-(side-1)*(side-1),x,z;
        if(n<side){x=ring;z=-ring+1+n;}
        else if(n<side*2){x=ring-1-(n-side);z=ring;}
        else if(n<side*3){x=-ring;z=ring-1-(n-side*2);}
        else{x=-ring+1+(n-side*3);z=-ring;}
        return new Offset((int)Math.round((x*Math.cos(heading)-z*Math.sin(heading))*spacing),
                (int)Math.round((x*Math.sin(heading)+z*Math.cos(heading))*spacing));
    }
}
