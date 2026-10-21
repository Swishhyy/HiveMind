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

package baritone.fabric;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.utils.SettingsUtil;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** A settings form within Mods > Baritone > Configure, without a gameplay dashboard or hotkey. */
final class BotSettingsScreen extends Screen {
    private final Screen parent;
    private final List<Settings.Setting<?>> entries=new ArrayList<>();
    private final Map<Settings.Setting<?>,String> draft=new LinkedHashMap<>();
    private final Map<Settings.Setting<?>,EditBox> fields=new LinkedHashMap<>();
    private int page;
    private String error="";
    private int rows;
    BotSettingsScreen(Screen parent){
        super(Component.literal("Baritone settings"));this.parent=parent;
        for(String key:List.of("mineAutoDeposit","mineDiscardWaste","mineWasteKeepStacks","mineTravelBetweenDimensions","preferAvoidConstruction","protectContainers","mineAvoidSculk","sculkAvoidRadius","botSurvival","botAutoResupply","botMinimumHealth","botEatAtHunger","botMinimumFood","botRestockFood","botMinimumBlocks","botRestockBlocks","botToolReserve","fleeConflicts","botConflictMode","conflictFleeDistance","stalkDistance","stalkSearchRadius","stalkSearchTimeoutMS","stalkMinimalEvidence","stalkPreferOpenAir","stalkPositionTolerance","stalkLostSightTicks","stalkRetryDelayMS","stalkSneakWhileMoving","stalkSneakHearingRange","stalkSneakViewAngle","stalkUseInvisibility","stalkVerticalRange","stalkViewConeDegrees","stalkGazeTicks","stalkScatterTicks","stalkReportSightings","stalkReportIntervalMS","stalkMemoryDays","killAllowTrustedTargets","killMinimumHealth","killMaxAttempts","killWatchMinimumMS","killWatchMaximumMS","killAttackTimeoutMS","killRetreatHealth","killRetreatPlayers","killUseTotems","killTimeoutMS","killCombatStyle","killMaximumSelfDamage","killMinimumBlastDamage","killBlastHealthReserve","killExplosiveDelayTicks","killUseBlastCover","killMaxSupportBlocks","killLureOpponent","killLureTimeoutMS","escortDistance","escortDefendOwner","deliveryRequireHandoff","deliveryApproachDistance","recoveryMaxAttempts","recoverySearchRadius","repairToolsBeforeReplace","repairAtDurability","repairTargetPercent","repairNoProgressTimeoutMS","companionTaskTimeoutMS","gatherMaxActions")){
            var setting=Baritone.settings().byLowerName.get(key.toLowerCase(Locale.ROOT));entries.add(setting);draft.put(setting,setting.value.toString());
        }
    }
    private void remember(){fields.forEach((setting,field)->draft.put(setting,field.getValue()));}
    @Override protected void init(){
        fields.clear();rows=Math.max(1,Math.min(8,(height-110)/26));page=Math.max(0,Math.min(page,(entries.size()-1)/rows));
        int left=Math.max(8,width/2-170);int fieldX=width/2+55;int fieldWidth=Math.max(45,Math.min(115,width-fieldX-8));
        for(int i=page*rows;i<Math.min(entries.size(),(page+1)*rows);i++){
            var setting=entries.get(i);int y=42+(i-page*rows)*26;String value=draft.get(setting);
            if(setting.value instanceof Boolean){
                addRenderableWidget(Button.builder(Component.literal(value),(button)->{String next=Boolean.toString(!Boolean.parseBoolean(draft.get(setting)));draft.put(setting,next);button.setMessage(Component.literal(next));}).bounds(fieldX,y,fieldWidth,20).build());
            }else if(setting==Baritone.settings().killCombatStyle){
                addRenderableWidget(Button.builder(Component.literal(value),button->{var styles=List.of("auto","melee","crystal","anchor");String next=styles.get(Math.floorMod(styles.indexOf(draft.get(setting))+1,styles.size()));draft.put(setting,next);button.setMessage(Component.literal(next));}).bounds(fieldX,y,fieldWidth,20).build());
            }else if(setting==Baritone.settings().botConflictMode){
                addRenderableWidget(Button.builder(Component.literal(value),button->{String next=draft.get(setting).equalsIgnoreCase("defend")?"flee":"defend";draft.put(setting,next);button.setMessage(Component.literal(next));}).bounds(fieldX,y,fieldWidth,20).build());
            }else{
                EditBox field=new EditBox(font,fieldX,y,fieldWidth,20,Component.literal(setting.getName()));field.setMaxLength(16);field.setValue(value);addRenderableWidget(field);fields.put(setting,field);
            }
        }
        int bottom=height-48;
        int buttonWidth=Math.min(80,(width-left*2-12)/4);
        addRenderableWidget(Button.builder(Component.literal("Previous"),button->{remember();page=Math.max(0,page-1);rebuildWidgets();}).bounds(left,bottom,buttonWidth,20).build());
        addRenderableWidget(Button.builder(Component.literal("Next"),button->{remember();page=Math.min((entries.size()-1)/rows,page+1);rebuildWidgets();}).bounds(left+buttonWidth+4,bottom,buttonWidth,20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"),button->save()).bounds(left+(buttonWidth+4)*2,bottom,buttonWidth,20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"),button->onClose()).bounds(left+(buttonWidth+4)*3,bottom,buttonWidth,20).build());
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private void save(){
        remember();Map<Settings.Setting<?>,Object> parsed=new LinkedHashMap<>();
        try{
            for(var setting:entries){String value=draft.get(setting).strip();Object result;
                if(setting.value instanceof Boolean)result=Boolean.parseBoolean(value);
                else if(setting.value instanceof Integer){int number=Integer.parseInt(value);if(number<0||number>1000000)throw new IllegalArgumentException(setting.getName()+" requires 0-1000000");result=number;}
                else if(setting.value instanceof Long){long number=Long.parseLong(value);if(number<0||number>3600000L)throw new IllegalArgumentException(setting.getName()+" requires 0-3600000");result=number;}
                else result=value;
                parsed.put(setting,result);
            }
            parsed.forEach((setting,value)->((Settings.Setting)setting).value=value);SettingsUtil.save(Baritone.settings());onClose();
        }catch(RuntimeException e){error=e.getMessage()==null?"Invalid value":e.getMessage();}
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float delta){
        super.extractRenderState(graphics,mouseX,mouseY,delta);int left=Math.max(8,width/2-170);
        graphics.text(font,title,left,14,0xffffffff,true);
        for(int i=page*rows;i<Math.min(entries.size(),(page+1)*rows);i++)graphics.text(font,entries.get(i).getName(),left,48+(i-page*rows)*26,0xffdddddd,true);
        graphics.text(font,"Page "+(page+1)+" / "+((entries.size()+rows-1)/rows),left,height-72,0xffaaaaaa,true);
        if(!error.isEmpty())graphics.text(font,error.substring(0,Math.min(65,error.length())),left,height-22,0xffff7777,true);
    }
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
}
