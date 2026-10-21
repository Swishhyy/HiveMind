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

package baritone.utils.bot;

import com.google.gson.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagKey;
import java.io.*;
import java.util.*;

/** Read bundled vanilla recipe data; actual workstation output must still be confirmed by the server. */
public final class VanillaRecipes {
    private static Map<String,List<JsonObject>> bundled;
    private final Map<String,List<GatherPlanner.Recipe>> cache=new HashMap<>();
    public VanillaRecipes(){load();}
    private static synchronized void load(){
        if(bundled!=null)return;Map<String,List<JsonObject>> found=new HashMap<>();
        try(var resources=new MultiPackResourceManager(PackType.SERVER_DATA,List.of(ServerPacksSource.createVanillaPackSource().fullResources()))){
            for(var entry:resources.listResources("recipe",id->id.getPath().endsWith(".json")).entrySet()){
                try(Reader reader=new InputStreamReader(entry.getValue().open(),java.nio.charset.StandardCharsets.UTF_8)){
                    JsonObject json=JsonParser.parseReader(reader).getAsJsonObject();String type=json.get("type").getAsString();
                    if(!Set.of("minecraft:crafting_shaped","minecraft:crafting_shapeless","minecraft:smelting").contains(type))continue;
                    String output=result(json.get("result"));json.addProperty("botRecipeId",entry.getKey().toString());found.computeIfAbsent(output,key->new ArrayList<>()).add(json);
                }catch(RuntimeException ignored){}
            }
        }catch(IOException e){throw new IllegalStateException("Cannot load vanilla recipes",e);}
        found.values().forEach(list->list.sort(Comparator.comparing(json->json.get("botRecipeId").getAsString())));bundled=Map.copyOf(found);
    }
    private static String result(JsonElement element){return element.isJsonPrimitive()?element.getAsString():element.getAsJsonObject().get("id").getAsString();}
    public List<GatherPlanner.Recipe> recipes(String item){return cache.computeIfAbsent(item,this::parse);}
    private List<GatherPlanner.Recipe> parse(String item){
        List<GatherPlanner.Recipe> result=new ArrayList<>();
        for(JsonObject json:bundled.getOrDefault(item,List.of()))try{
            String type=json.get("type").getAsString();List<List<String>> slots=new ArrayList<>();GatherPlanner.Method method=type.equals("minecraft:smelting")?GatherPlanner.Method.SMELT:GatherPlanner.Method.CRAFT;
            if(type.equals("minecraft:crafting_shaped")){
                JsonArray pattern=json.getAsJsonArray("pattern");JsonObject keys=json.getAsJsonObject("key");
                for(int y=0;y<3;y++)for(int x=0;x<3;x++){String row=y<pattern.size()?pattern.get(y).getAsString():"";char key=x<row.length()?row.charAt(x):' ';slots.add(key==' '?List.of():ingredient(keys.get(""+key)));}
            }else if(method==GatherPlanner.Method.CRAFT){for(JsonElement ingredient:json.getAsJsonArray("ingredients"))slots.add(ingredient(ingredient));while(slots.size()<9)slots.add(List.of());}
            else slots.add(ingredient(json.get("ingredient")));
            if(slots.stream().allMatch(List::isEmpty))continue;
            int count=json.get("result").isJsonObject()&&json.getAsJsonObject("result").has("count")?json.getAsJsonObject("result").get("count").getAsInt():1;
            result.add(new GatherPlanner.Recipe(json.get("botRecipeId").getAsString(),item,count,method,slots));
        }catch(RuntimeException ignored){}
        result.sort(Comparator.comparingInt(recipe->recipe.slots().stream().flatMap(Collection::stream).anyMatch(id->id.startsWith("minecraft:raw_"))?0:1));
        return List.copyOf(result);
    }
    private List<String> ingredient(JsonElement element){
        if(element==null)return List.of();if(element.isJsonArray()){var options=new TreeSet<String>();for(JsonElement part:element.getAsJsonArray())options.addAll(ingredient(part));return List.copyOf(options);}
        String id;if(element.isJsonObject()){JsonObject object=element.getAsJsonObject();id=object.has("item")?object.get("item").getAsString():"#"+object.get("tag").getAsString();}else id=element.getAsString();
        if(!id.startsWith("#"))return List.of(BotItems.require(id));
        var tag=TagKey.create(Registries.ITEM,Identifier.parse(id.substring(1)));List<String> matches=new ArrayList<>();
        for(var item:BuiltInRegistries.ITEM)if(item.getDefaultInstance().is(tag))matches.add(BuiltInRegistries.ITEM.getKey(item).toString());
        if(matches.isEmpty())throw new IllegalArgumentException("Server did not provide ingredient tag "+id);Collections.sort(matches);return List.copyOf(matches);
    }
}
