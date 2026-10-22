/* This file is part of HiveMind, distributed under the LGPL-3.0 license. */
package hivemind.utils.bot;

import hivemind.utils.mining.WorldRoutes.Endpoint;
import java.nio.file.*;
import java.util.*;

public final class SurveyChecks {
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{runAll();System.out.println("Survey checks passed: expanding adjacent coverage, durable progress, route isolation, reanchoring and coordinate bounds");}
    public static void runAll()throws Exception{
        Set<SurveyPolicy.Offset> frontier=new HashSet<>();
        SurveyPolicy.Offset prior=null;
        for(int i=1;i<=10000;i++){
            SurveyPolicy.Offset point=SurveyPolicy.frontierOffset(i,128,768,0);
            check(point.x()>=768&&frontier.add(point),"Idle scouts must leave the starting area and never revisit an earlier outbound cell");
            if(prior!=null)check(point.x()>=prior.x()&&Math.hypot(point.x()-prior.x(),point.z()-prior.z())<=Math.sqrt(2)*128+.001,"Outbound sweeps keep advancing with short adjacent legs");
            prior=point;
        }
        check(prior.x()>13000,"Continued scouting must expand by kilometers instead of circling the spawn area");
        check(SurveyPolicy.frontierOffset(1,128,2048,0).equals(new SurveyPolicy.Offset(2048,0)),"The configured roam distance must set the actual initial departure");
        check(SurveyPolicy.frontierOffset(1,128,768,Math.PI).equals(new SurveyPolicy.Offset(-768,0)),"Separate team headings send scouts toward different frontiers");
        Set<SurveyPolicy.Offset> visited=new HashSet<>();SurveyPolicy.Offset previous=new SurveyPolicy.Offset(0,0);
        for(int i=1;i<=10000;i++){
            SurveyPolicy.Offset point=SurveyPolicy.offset(i,128,0);
            check(visited.add(point),"A survey must not repeat earlier cells");
            check(Math.abs(point.x()-previous.x())+Math.abs(point.z()-previous.z())==128,"Consecutive survey cells must be adjacent, including new rings");
            previous=point;
        }
        check(Math.max(Math.abs(previous.x()),Math.abs(previous.z()))>512,"Survey coverage must expand beyond the old local-search cap");
        SurveyPolicy.Offset east=SurveyPolicy.offset(1,128,0),north=SurveyPolicy.offset(1,128,Math.PI/2);
        check(east.equals(new SurveyPolicy.Offset(128,0))&&north.equals(new SurveyPolicy.Offset(0,128)),"Team headings must rotate the survey grid");
        Path root=Files.createTempDirectory("hivemind-surveys");Endpoint origin=new Endpoint("minecraft:overworld",100,70,-200);
        try{
            SurveyRoute roam=new SurveyRoute(root,"roam-air",origin,128,0);
            SurveyRoute outbound=new SurveyRoute(root,"roam-air-frontier",origin,128,0);
            for(int i=0;i<17;i++)outbound.nextFrontier(768);outbound.save();Endpoint nextFrontier=outbound.nextFrontier(768);
            SurveyRoute resumeFrontier=new SurveyRoute(root,"roam-air-frontier",new Endpoint(origin.dimension(),9000,70,9000),128,Math.PI);
            check(resumeFrontier.step()==17&&resumeFrontier.nextFrontier(768).equals(nextFrontier),"An outbound route resumes its saved origin, heading and sweep after restart");
            for(int i=0;i<25;i++)roam.next();roam.save();Endpoint expected=roam.next();
            SurveyRoute restored=new SurveyRoute(root,"roam-air",new Endpoint(origin.dimension(),5000,64,5000),128,Math.PI);
            check(restored.step()==25&&restored.origin().equals(origin)&&restored.next().equals(expected),"Restart and changed team membership must resume the saved grid instead of circling a new anchor");
            SurveyRoute hunt=new SurveyRoute(root,"search-player-air",origin,128,0);hunt.next();hunt.save();
            restored.save();
            check(new SurveyRoute(root,"search-player-air",origin,128,0).step()==1,"Saving another live route must preserve independent route progress");
            check(new SurveyRoute(root,"roam-ground",origin,64,0).step()==0,"Walking and flying must keep separate coverage checkpoints");
            check(new SurveyRoute(root,"roam-air",new Endpoint("minecraft:the_nether",100,70,-200),128,0).step()==0,"Dimensions must not inherit another dimension's route");
            check(new SurveyRoute(root.resolve("other-server"),"roam-air",origin,128,0).step()==0,"Server route histories must remain separate");
            Endpoint evidence=new Endpoint(origin.dimension(),500,80,900);restored.reanchor(evidence);restored.next();restored.save();
            SurveyRoute redirected=new SurveyRoute(root,"roam-air",origin,128,0);
            check(redirected.origin().equals(evidence)&&redirected.step()==1,"A deliberate new evidence anchor must persist");
            try{restored.reanchor(new Endpoint("minecraft:the_end",0,80,0));throw new AssertionError("Cross-dimension anchor accepted");}catch(IllegalArgumentException expectedError){}
            SurveyRoute edge=new SurveyRoute(root,"edge",new Endpoint(origin.dimension(),30_000_000,70,30_000_000),512,0);
            for(int i=0;i<20;i++){Endpoint point=edge.next();check(Math.abs((long)point.x())<=30_000_000&&Math.abs((long)point.z())<=30_000_000,"Border candidates must remain valid route coordinates");}
            Properties broken=AtomicProperties.read(root.resolve("bot-surveys.properties"));broken.setProperty("roam-air.minecraft:overworld.step","-1");AtomicProperties.write(root.resolve("bot-surveys.properties"),broken);
            try{new SurveyRoute(root,"roam-air",origin,128,0);throw new AssertionError("Corrupt progress silently accepted");}catch(IllegalStateException expectedError){}
        }finally{try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
}
