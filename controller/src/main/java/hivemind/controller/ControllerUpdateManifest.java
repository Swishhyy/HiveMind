/* HiveMind, LGPL-3.0. */
package hivemind.controller;
import java.io.*;
import java.net.URI;
import java.util.Properties;
/** A controller-only channel; Fabric client manifests are never accepted here. */
record ControllerUpdateManifest(String version,String commit,long size,String sha256,URI url) {
    static final String REPOSITORY="https://github.com/Swishhyy/HiveMind";
    static final URI LATEST=URI.create(REPOSITORY+"/releases/latest/download/hivemind-controller-update.properties");
    ControllerUpdateManifest {
        if(!version.matches("[0-9]{1,6}(\\.[0-9]{1,6}){3}")||!commit.matches("[a-f0-9]{40}")||size<1024||size>64L*1024*1024||!sha256.matches("[a-f0-9]{64}")
            ||!url.toString().matches("https://github\\.com/Swishhyy/HiveMind/releases/download/[A-Za-z0-9._-]+/hivemind-controller\\.jar"))throw new IllegalArgumentException("Invalid controller update manifest");
    }
    static ControllerUpdateManifest read(byte[] bytes)throws IOException {
        if(bytes.length>16384)throw new IOException("Controller manifest is too large");Properties p=new Properties();p.load(new ByteArrayInputStream(bytes));
        try{if(!"hivemind-controller".equals(p.getProperty("application"))||!"17".equals(p.getProperty("java")))throw new IllegalArgumentException("Not a Java 17 HiveMind controller release");
            return new ControllerUpdateManifest(p.getProperty("version",""),p.getProperty("commit",""),Long.parseLong(p.getProperty("size","0")),p.getProperty("sha256",""),URI.create(p.getProperty("url","")));
        }catch(IllegalArgumentException e){throw new IOException(e.getMessage(),e);}
    }
    static int compare(String a,String b){
        if(!a.matches("[0-9]{1,6}(\\.[0-9]{1,6}){3}")||!b.matches("[0-9]{1,6}(\\.[0-9]{1,6}){3}"))throw new IllegalArgumentException("Invalid controller version");
        String[] left=a.split("\\."),right=b.split("\\.");for(int i=0;i<4;i++){int n=Integer.compare(Integer.parseInt(left[i]),Integer.parseInt(right[i]));if(n!=0)return n;}return 0;
    }
}
