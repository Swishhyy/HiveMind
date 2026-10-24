/* This file is part of HiveMind, distributed under the LGPL-3.0 license. */
package hivemind.utils.bot;

public final class PortableSupplyPolicy {
    private PortableSupplyPolicy(){}
    /** QUICK_MOVE can move the entire stack: capacity excludes one slot reserved for the recovered box. */
    public static boolean canWithdraw(int stack,int matchingCapacity,int emptySlots,int maximumStack){
        return emptySlots>=1&&stack>0&&maximumStack>0&&stack<=(long)Math.max(0,matchingCapacity)+(long)Math.max(0,emptySlots-1)*maximumStack;
    }
    public static boolean mayRecover(boolean confirmedPlacement,boolean expectedBlock,boolean emptyCursor){return confirmedPlacement&&expectedBlock&&emptyCursor;}
    /** Keep a nearby placed box safe without grounding travel forever for a distant recovery record. */
    public static boolean blocksTravel(boolean active,boolean pending,boolean sameDimension,double distanceSquared,boolean loaded,boolean retryReady){
        return active||pending&&sameDimension&&distanceSquared<=64D*64&&loaded&&retryReady;
    }

}
