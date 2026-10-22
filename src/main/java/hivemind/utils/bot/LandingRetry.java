/* HiveMind, LGPL-3.0. */
package hivemind.utils.bot;
/** Exhausting a terrain pass must not restart a full scan and announce it every game tick. */
public final class LandingRetry {
    private long retryAt,messageAt;
    private boolean announced;
    public boolean canSearch(long now){return now>=retryAt;}
    public void exhausted(long now){retryAt=now+2000;}
    public boolean announce(long now){if(announced&&now<messageAt)return false;announced=true;messageAt=now+5000;return true;}
    public void reset(){retryAt=messageAt=0;announced=false;}
    public static boolean finished(boolean grounded,boolean requested,boolean landingRoute,boolean landingState){return grounded&&(requested||landingRoute||landingState);}
}
