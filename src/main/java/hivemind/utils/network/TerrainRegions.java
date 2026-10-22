/* HiveMind, LGPL-3.0. */
package hivemind.utils.network;

import hivemind.utils.network.TerrainPackets.Tile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Compressed 32-by-32-chunk archives. Updating one area never rewrites or discards the rest of the world. */
final class TerrainRegions {
    record Region(String dimension,int x,int z) {}
    private final Path root;
    TerrainRegions(Path root){this.root=root.resolve("bot-network-tiles");}
    static Region region(Tile tile){return new Region(tile.dimension(),Math.floorDiv(tile.x(),32),Math.floorDiv(tile.z(),32));}
    private static String name(Region region){
        return Base64.getUrlEncoder().withoutPadding().encodeToString(region.dimension().getBytes(StandardCharsets.UTF_8))
                +"."+region.x()+"."+region.z()+".tiles.gz";
    }
    List<Tile> load()throws IOException{
        List<Tile> result=new ArrayList<>();if(!Files.exists(root))return result;
        try(var paths=Files.list(root)){
            for(Path file:paths.filter(path->path.getFileName().toString().endsWith(".tiles.gz")).toList()){
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>6_000_000)throw new IOException("Invalid terrain region file");
                byte[] bytes;try(var in=new GZIPInputStream(Files.newInputStream(file))){bytes=in.readNBytes(6_000_001);}
                if(bytes.length>6_000_000)throw new IOException("Terrain region exceeds its size limit");
                Properties p=new Properties();p.load(new ByteArrayInputStream(bytes));
                int count=Integer.parseInt(p.getProperty("count","0"));if(count<0||count>1024)throw new IOException("Invalid terrain region count");
                for(int i=0;i<count;i++){
                    String entry=p.getProperty("entry."+i,"");if(entry.length()>6000)throw new IOException("Oversized terrain tile");
                    var message=NetworkProtocol.decode(Base64.getDecoder().decode(entry));
                    if(!(message instanceof Tile tile)||!name(region(tile)).equals(file.getFileName().toString()))throw new IOException("Tile is in the wrong terrain region");
                    result.add(tile);
                }
            }
        }return result;
    }
    void save(Region region,List<Tile> tiles)throws IOException{
        Path file=root.resolve(name(region));
        if(tiles.isEmpty()){Files.deleteIfExists(file);return;}
        if(tiles.size()>1024)throw new IOException("Terrain region contains too many tiles");
        Files.createDirectories(root);Properties p=new Properties();p.setProperty("count",""+tiles.size());
        for(int i=0;i<tiles.size();i++)p.setProperty("entry."+i,Base64.getEncoder().encodeToString(NetworkProtocol.encode(tiles.get(i))));
        Path temp=Files.createTempFile(root,"terrain-",".tmp");
        try{
            try(var out=new GZIPOutputStream(Files.newOutputStream(temp))){p.store(out,"HiveMind explored terrain");}
            try{Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }
}
