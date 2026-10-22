/* This file is part of Baritone, distributed under the LGPL-3.0 license. */
package baritone.utils.bot;

public final class PortableSupplyPolicy {
    private PortableSupplyPolicy(){}
    /** QUICK_MOVE can move the entire stack: capacity excludes one slot reserved for the recovered box. */
    public static boolean canWithdraw(int stack,int matchingCapacity,int emptySlots,int maximumStack){
        return emptySlots>=1&&stack>0&&maximumStack>0&&stack<=(long)Math.max(0,matchingCapacity)+(long)Math.max(0,emptySlots-1)*maximumStack;
    }
    public static boolean mayRecover(boolean confirmedPlacement,boolean expectedBlock,boolean emptyCursor){return confirmedPlacement&&expectedBlock&&emptyCursor;}
}
