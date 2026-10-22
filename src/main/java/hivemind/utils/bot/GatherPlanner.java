/*
 * This file is part of HiveMind.
 *
 * HiveMind is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * HiveMind is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with HiveMind.  If not, see <https://www.gnu.org/licenses/>.
 */

package hivemind.utils.bot;

import java.util.*;
import java.util.function.Function;

/** Finite dependency planning with inventory reuse, recipe alternatives, batching, and cycle rejection. */
public final class GatherPlanner {
    public enum Method { ACQUIRE, CRAFT, SMELT }
    public record Recipe(String id,String output,int count,Method method,List<List<String>> slots){
        public Recipe{if(count<1||count>64||slots.isEmpty()||slots.size()>9||method==Method.ACQUIRE)throw new IllegalArgumentException("Unsupported recipe");slots=slots.stream().map(List::copyOf).toList();}
    }
    public record Action(Method method,String item,int amount,Recipe recipe,List<String> grid,String fuel){public Action{grid=List.copyOf(grid);}}
    public record Plan(List<Action> actions,Map<String,Integer> required){public Plan{actions=List.copyOf(actions);required=Collections.unmodifiableMap(new LinkedHashMap<>(required));}}
    private final Function<String,List<Recipe>> recipes;
    private final int maxActions;
    public GatherPlanner(Function<String,List<Recipe>> recipes,int maxActions){this.recipes=recipes;this.maxActions=Math.max(1,Math.min(4096,maxActions));}
    public Plan plan(String item,int quantity,Map<String,Integer> inventory){CompanionPolicy.quantity(quantity);var state=new State(inventory);require(item,quantity,state,new LinkedHashSet<>(),0);return new Plan(state.actions,state.required);}
    private static final class State {
        final Map<String,Integer> stock;final List<Action> actions;final LinkedHashMap<String,Integer> required;
        State(Map<String,Integer> stock){this.stock=new HashMap<>(stock);actions=new ArrayList<>();required=new LinkedHashMap<>();}
        State(State other){stock=new HashMap<>(other.stock);actions=new ArrayList<>(other.actions);required=new LinkedHashMap<>(other.required);}
        void use(State other){stock.clear();stock.putAll(other.stock);actions.clear();actions.addAll(other.actions);required.clear();required.putAll(other.required);}
    }
    private void require(String item,int amount,State state,Set<String> parents,int depth){
        if(depth>16)throw new IllegalArgumentException("Recipe dependency depth exceeded: "+item);
        if(amount<0||amount>65536)throw new IllegalArgumentException("Recipe material requirement exceeds 65536 items");
        state.required.merge(item,amount,Math::addExact);int available=Math.max(0,state.stock.getOrDefault(item,0)),used=Math.min(available,amount);
        state.stock.put(item,available-used);int missing=amount-used;if(missing==0)return;
        if(parents.contains(item))throw new IllegalArgumentException("Recipe dependency cycle: "+item);
        var branch=new LinkedHashSet<>(parents);branch.add(item);State best=null;int bestCost=Integer.MAX_VALUE;
        for(Recipe recipe:recipes.apply(item).stream().limit(24).toList()){
            try{
                State attempt=new State(state);int batches=(missing+recipe.count()-1)/recipe.count();List<String> grid=new ArrayList<>();Map<String,Integer> ingredients=new LinkedHashMap<>();
                for(List<String> options:recipe.slots()){
                    if(options.isEmpty()){grid.add("");continue;}
                    String selected=options.stream().filter(candidate->!branch.contains(candidate)||attempt.stock.getOrDefault(candidate,0)>=batches).max(Comparator.comparingInt((String candidate)->attempt.stock.getOrDefault(candidate,0)).thenComparing(candidate->candidate.equals("minecraft:oak_planks")||candidate.equals("minecraft:oak_log")?1:0).thenComparing(Comparator.reverseOrder()))
                            .orElseThrow(()->new IllegalArgumentException("Cyclic ingredient"));
                    grid.add(selected);ingredients.merge(selected,batches,Integer::sum);
                }
                for(var entry:ingredients.entrySet())require(entry.getKey(),entry.getValue(),attempt,branch,depth+1);
                String fuel="";
                if(recipe.method()==Method.SMELT){fuel=attempt.stock.getOrDefault("minecraft:charcoal",0)>0?"minecraft:charcoal":"minecraft:coal";require(fuel,(batches+7)/8,attempt,branch,depth+1);}
                attempt.actions.add(new Action(recipe.method(),item,batches*recipe.count(),recipe,grid,fuel));attempt.stock.merge(item,batches*recipe.count()-missing,Integer::sum);
                if(attempt.actions.size()>maxActions)continue;
                int cost=attempt.actions.stream().mapToInt(action->action.method()==Method.ACQUIRE?action.amount()*10:action.amount()).sum();
                if(cost<bestCost){best=attempt;bestCost=cost;}
            }catch(IllegalArgumentException|ArithmeticException ignored){}
        }
        if(best!=null){state.use(best);return;}
        if(!recipes.apply(item).isEmpty()&&!naturalResource(item))throw new IllegalArgumentException("No acyclic recipe for "+item);
        if(state.actions.size()>=maxActions)throw new IllegalArgumentException("Gathering plan is too large");
        state.actions.add(new Action(Method.ACQUIRE,item,missing,null,List.of(),""));
    }
    private static boolean naturalResource(String id){return Set.of("minecraft:coal","minecraft:raw_iron","minecraft:raw_gold","minecraft:raw_copper","minecraft:diamond","minecraft:emerald","minecraft:redstone","minecraft:lapis_lazuli","minecraft:quartz","minecraft:ancient_debris","minecraft:clay_ball","minecraft:gold_nugget","minecraft:bone","minecraft:slime_ball","minecraft:honeycomb").contains(id);}
}
