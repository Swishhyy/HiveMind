/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */
package baritone.utils.network;

import baritone.utils.network.ControlPackets.Action;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A bounded HTTP form becomes an immutable request; no Minecraft APIs run on HTTP worker threads. */
public record WebControlAction(UUID id,UUID target,Action action,String key,String value,long time) {
    public static WebControlAction parse(byte[] body,long now){
        if(body.length>8192)throw new IllegalArgumentException("Request is too large");
        Map<String,String> values=new HashMap<>();
        for(String pair:new String(body,StandardCharsets.UTF_8).split("&")){
            String[] fields=pair.split("=",2);String key=URLDecoder.decode(fields[0],StandardCharsets.UTF_8);
            if(!Set.of("target","action","key","value").contains(key)||fields.length!=2||values.put(key,URLDecoder.decode(fields[1],StandardCharsets.UTF_8))!=null)
                throw new IllegalArgumentException("Invalid or duplicate form field");
        }
        String target=values.getOrDefault("target","all");
        Action action=Action.valueOf(values.getOrDefault("action","").toUpperCase(Locale.ROOT));
        String key=values.getOrDefault("key",""),value=values.getOrDefault("value","");
        if(key.length()>128||value.length()>512)throw new IllegalArgumentException("Control value is too long");
        return new WebControlAction(UUID.randomUUID(),target.equals("all")?null:UUID.fromString(target),action,key,value,now);
    }
}
