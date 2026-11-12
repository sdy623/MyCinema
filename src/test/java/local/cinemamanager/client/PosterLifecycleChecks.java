package local.cinemamanager.client;

import com.google.gson.JsonParser;
import local.cinemamanager.panels.PanelLayout;
import local.cinemamanager.panels.PanelNetwork;
import local.cinemamanager.panels.PanelScope;
import local.cinemamanager.panels.PanelSettings;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PosterLifecycleChecks {
    private static int checks;
    private static void check(boolean yes,String reason){checks++;if(!yes)throw new AssertionError(reason);}
    public static void main(String[]args)throws Exception{
        PosterTexturePoolChecks.main(new String[0]);
        PosterRequestAdmissionChecks.main(new String[0]);
        AssetRequestAdmissionChecks.main(new String[0]);
        check(PosterViewState.of("https://saved.invalid/image","")==PosterViewState.State.WITHHELD,"redacted public tile cannot turn a denied saved owner source into no-poster");
        check(PosterViewState.of("","")==PosterViewState.State.EMPTY,"genuinely empty source differs from denied source");
        check(PosterViewState.of("saved","saved")==PosterViewState.State.AVAILABLE,"matching server-approved source can be diagnosed");
        check(PosterViewState.of("old","new")==PosterViewState.State.CHANGED,"stale owner snapshot never labels new-source output as its old source");
        check(PosterViewState.of("","approved",true)==PosterViewState.State.AUTOMATIC_AVAILABLE,"blank saved override may inherit the actual approved film poster");
        check(PosterViewState.of("","",true)==PosterViewState.State.AUTOMATIC_EMPTY,"missing automatic poster is distinct from empty manual source");
        check(PosterViewState.of("","approved",false)==PosterViewState.State.CHANGED,"manual and lobby scopes never assume automatic inheritance");
        check(PosterViewState.of("old","new",true)==PosterViewState.State.CHANGED,"automatic context cannot override a nonblank saved manual source mismatch");
        check(PosterViewState.of("manual","manual",true)==PosterViewState.State.AVAILABLE,"manual source takes precedence in automatic auditorium mode");
        check(PosterViewState.of("manual","",true)==PosterViewState.State.WITHHELD,"automatic mode cannot hide withdrawal of a saved manual source");
        check(!PosterViewState.guestMayDisplay(PanelSettings.Layout.TEXT,"tile","tile","approved","approved"),"text-only layout never requests a guest poster");
        for(var layout:java.util.List.of(PanelSettings.Layout.POSTER_TOP,PanelSettings.Layout.POSTER_LEFT)){
            check(PosterViewState.guestMayDisplay(layout,"tile","tile","approved","approved"),"poster layout accepts a matching live approved source");
            check(!PosterViewState.guestMayDisplay(layout,"old-tile","replacement-tile","approved","approved"),"replaced panel cannot reuse old guest identity");
            check(!PosterViewState.guestMayDisplay(layout,"tile","tile","stale","current"),"old guest refresh cannot fetch or display a replaced publication");
            check(!PosterViewState.guestMayDisplay(layout,"tile","tile","approved",""),"withdrawal suppresses the old guest source immediately");
            check(!PosterViewState.guestMayDisplay(layout,"tile","tile","","approved"),"empty guest source cannot borrow a newer tile publication");
            var areas=PanelLayout.areas(400,220,layout);
            check(areas.text().width()>0&&areas.text().height()>0&&areas.poster().width()>0&&areas.poster().height()>0,"both guest areas retain positive dimensions");
            check(areas.poster().x()+areas.poster().width()<=areas.text().x()||areas.poster().y()+areas.poster().height()<=areas.text().y(),"poster area never overlaps guest text");
        }
        var textAreas=PanelLayout.areas(400,220,PanelSettings.Layout.TEXT);
        check(textAreas.poster().width()==0&&textAreas.poster().height()==0&&textAreas.text().width()==400&&textAreas.text().height()==220,"existing text layout keeps its full reading area");
        var oldData=new PanelNetwork.Data(PanelSettings.DEFAULT,java.util.List.of(),1,true,"",PanelScope.HALL);
        check(oldData.binding().equals(PanelNetwork.BindingData.UNKNOWN),"historical Data constructor retains safe unknown binding status");
        var legacy=PanelNetwork.JSON.toJsonTree(oldData).getAsJsonObject();legacy.remove("binding");
        var restored=PanelNetwork.JSON.fromJson(legacy,PanelNetwork.Data.class);
        check(restored.binding().equals(PanelNetwork.BindingData.UNKNOWN)&&restored.settings().equals(oldData.settings()),"legacy Gson snapshot gets safe binding default without changing settings");
        var bound=new PanelNetwork.BindingData(PanelNetwork.BindingState.HALL_BOUND,"Auditorium 2",PanelNetwork.ScheduleState.AVAILABLE);
        check(PanelNetwork.JSON.fromJson(PanelNetwork.JSON.toJson(bound),PanelNetwork.BindingData.class).equals(bound),"owner-only auditorium name and state round-trip without an address or source");
        PosterCompletion<String> missing=new PosterCompletion<>();
        check(missing.complete(null,new PosterFailure(PosterFailure.Code.LOCAL_MISSING)),"failure delivered");
        var failed=missing.take();check(failed.value()==null&&failed.failure().code()==PosterFailure.Code.LOCAL_MISSING,"accurate failure displayed");
        check(missing.take()==null,"completion published once");
        PosterCompletion<String> retry=new PosterCompletion<>();
        check(retry.complete("new pixels",null),"retry can succeed");
        check(retry.take().failure()==null,"success clears previous error");
        PosterCompletion<String> oldSource=new PosterCompletion<>();oldSource.cancel();
        check(!oldSource.complete("old URL pixels",null)&&oldSource.take()==null,"changed-source callback cannot revive cancelled image");
        PosterCompletion<String> changedSource=new PosterCompletion<>();changedSource.complete("current pixels",null);
        check(changedSource.take().value().equals("current pixels"),"old callback never overwrites the replacement source slot");
        PosterCompletion<String> cancelledAfterSuccess=new PosterCompletion<>();cancelledAfterSuccess.complete("not uploaded",null);cancelledAfterSuccess.cancel();
        check(cancelledAfterSuccess.take()==null,"unload or withdrawal discards completed work before texture upload");
        boolean secretRejected=false;try{new PosterFailure(PosterFailure.Code.NETWORK,"https://token:secret@example.org/private");}catch(IllegalArgumentException expected){secretRejected=true;}
        check(secretRejected,"diagnostic cannot contain a token or private path");
        check(PosterFailure.assetCode("asset_busy")==PosterFailure.Code.BUSY,"asset congestion has its own retry diagnostic");
        check(PosterFailure.assetCode("asset_missing")==PosterFailure.Code.UNAVAILABLE,"missing assets remain unavailable rather than busy");
        check(PosterFailure.assetCode("asset_permission")==PosterFailure.Code.PERMISSION,"congestion mapping cannot hide a permission denial");
        check(PosterFailure.assetCode(null)==PosterFailure.Code.NETWORK,"unknown asset errors retain the network fallback");
        var en=JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/mycinema/lang/en_us.json"))).getAsJsonObject();
        var zh=JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/mycinema/lang/zh_cn.json"))).getAsJsonObject();
        check(en.keySet().equals(zh.keySet()),"English source and Chinese translations match");
        for(var code:PosterFailure.Code.values())for(var lang:new com.google.gson.JsonObject[]{en,zh})check(lang.has("mycinema.poster_error."+code.name().toLowerCase(java.util.Locale.ROOT)),"every failure category is translated");
        for(var lang:new com.google.gson.JsonObject[]{en,zh})check(lang.has("mycinema.poster_error.download_budget_atleast"),"unknown length has an explicit lower-bound message");
        for(var state:PanelNetwork.BindingState.values())for(var lang:new com.google.gson.JsonObject[]{en,zh})check(lang.has("mycinema.panel.binding."+state.name().toLowerCase(java.util.Locale.ROOT)),"every owner binding state is translated");
        for(var state:PanelNetwork.ScheduleState.values())for(var lang:new com.google.gson.JsonObject[]{en,zh})check(lang.has("mycinema.panel.schedule."+state.name().toLowerCase(java.util.Locale.ROOT)),"every owner schedule state is translated");
        System.out.println("PASS: "+checks+" poster asynchronous completion, safe diagnostic and translation checks.");
    }
}
