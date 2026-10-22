/* HiveMind, LGPL-3.0. */
package hivemind.utils.update;

import java.io.*;
import java.net.URI;
import java.util.Properties;

public record UpdateManifest(String version,String commit,long size,String sha256,URI url,String distribution){
    public UpdateManifest(String version,String commit,long size,String sha256,URI url){this(version,commit,size,sha256,url,"legacy");}
    public static final String REPOSITORY="https://github.com/Swishhyy/HiveMind";
    public static final URI LATEST=URI.create(REPOSITORY+"/releases/latest/download/hivemind-26.3-update.properties");
    public static URI latest(String distribution){if(!java.util.Set.of("legacy","bot","player").contains(distribution))throw new IllegalArgumentException("Invalid distribution");return distribution.equals("player")?URI.create(REPOSITORY+"/releases/latest/download/hivemind-player-26.3-update.properties"):LATEST;}
    public UpdateManifest{
        if(!java.util.Set.of("legacy","bot","player").contains(distribution))throw new IllegalArgumentException("Invalid update distribution");
        if(!version.matches("[0-9]{1,6}(\\.[0-9]{1,6}){3}")||!commit.matches("[a-f0-9]{40}")||size<1024||size>64L*1024*1024||!sha256.matches("[a-f0-9]{64}")
                ||!url.toString().startsWith(REPOSITORY+"/releases/download/")||!url.getScheme().equals("https")||url.getRawQuery()!=null||url.getRawFragment()!=null
                ||!url.getPath().matches("/Swishhyy/HiveMind/releases/download/[A-Za-z0-9._-]+/hivemind-[A-Za-z0-9._-]+\\.jar"))throw new IllegalArgumentException("Invalid Fabric 26.3 update manifest");
    }
    public static UpdateManifest read(byte[] bytes)throws IOException{
        if(bytes.length>16384)throw new IOException("Update manifest is too large");Properties p=new Properties();p.load(new ByteArrayInputStream(bytes));
        try{
            if(!"hivemind".equals(p.getProperty("application"))||!"26.3".equals(p.getProperty("minecraft"))||!"fabric".equals(p.getProperty("loader")))throw new IllegalArgumentException("Update is not HiveMind for Fabric 26.3");
            return new UpdateManifest(p.getProperty("version",""),p.getProperty("commit",""),Long.parseLong(p.getProperty("size","0")),p.getProperty("sha256",""),URI.create(p.getProperty("url","")),p.getProperty("distribution","legacy"));
        }catch(IllegalArgumentException e){throw new IOException(e.getMessage(),e);}
    }
    public boolean newerThan(String installed){return compare(version,installed)>0;}
    public static int compare(String left,String right){
        if(!left.matches("[0-9]{1,6}(\\.[0-9]{1,6}){3}")||!right.matches("[0-9]{1,6}(\\.[0-9]{1,6}){3}"))throw new IllegalArgumentException("Expected four-part HiveMind version");
        String[] a=left.split("\\."),b=right.split("\\.");for(int i=0;i<4;i++){int value=Integer.compare(Integer.parseInt(a[i]),Integer.parseInt(b[i]));if(value!=0)return value;}return 0;
    }
}
