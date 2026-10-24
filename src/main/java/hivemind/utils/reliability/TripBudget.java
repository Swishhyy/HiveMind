/* HiveMind, LGPL-3.0. */
package hivemind.utils.reliability;

/** Conservative initial estimates learn from confirmed consumption; budgets never create usable items. */
public final class TripBudget {
    public record Estimate(int rockets,int food,int durability,double metres,String reason) {}
    private double metresPerRocket=160,metresPerDurability=80;
    public Estimate estimate(double outward,double toResupply,int emergencyReserve){
        double metres=Math.max(0,outward)+Math.max(0,toResupply);
        int rockets=(int)Math.min(512,Math.ceil(metres/metresPerRocket*1.25)+Math.max(8,emergencyReserve));
        int food=(int)Math.min(128,Math.max(8,Math.ceil(metres/1200)+8));
        int durability=(int)Math.min(432,Math.max(32,Math.ceil(metres/metresPerDurability*1.25)+16));
        return new Estimate(rockets,food,durability,metres,"Journey and nearest known resupply allowance");
    }
    public void observe(double metres,int rockets,int durability){if(metres<32)return;if(rockets>0)metresPerRocket=.8*metresPerRocket+.2*Math.max(32,Math.min(1000,metres/rockets));if(durability>0)metresPerDurability=.8*metresPerDurability+.2*Math.max(16,Math.min(1000,metres/durability));}
    public double metresPerRocket(){return metresPerRocket;}
}
