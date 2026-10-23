/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;
/** Ender inventory is private reserve stock, never a destination for automatic or deposit-all unloading. */
public final class StorageAccessPolicy {
    private StorageAccessPolicy(){}
    public static boolean permits(boolean enderChest,boolean withdrawing,boolean enderEnabled){return !enderChest||withdrawing&&enderEnabled;}
}
