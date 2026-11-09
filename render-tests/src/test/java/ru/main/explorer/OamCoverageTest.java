package ru.main.explorer;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.TextView;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34,qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class OamCoverageTest {
    interface Edit {void run(JSONObject j)throws Exception;}
    void reason(OamCoverage.Reason reason,Edit edit)throws Exception {JSONObject j=OamImageryTest.item();edit.run(j);OamCoverage c=new OamCoverage(j);assertNull(c.source);assertTrue(reason+" "+c.reasons,c.reasons.contains(reason));assertFalse(c.polygons.isEmpty());}
    @Test public void independentlyReportsEachRejectionCategory()throws Exception {
        reason(OamCoverage.Reason.LICENSE,j->j.getJSONObject("properties").put("license","CC-BY-SA-4.0"));
        reason(OamCoverage.Reason.METADATA,j->j.getJSONObject("properties").remove("oam:producer_name"));
        reason(OamCoverage.Reason.GSD,j->j.getJSONObject("properties").put("gsd",.6));
        reason(OamCoverage.Reason.CRS,j->j.getJSONObject("assets").getJSONObject("visual").put("proj:code","EPSG:3857"));
        reason(OamCoverage.Reason.ROTATION,j->j.getJSONObject("assets").getJSONObject("visual").getJSONArray("proj:transform").put(1,.001));
        reason(OamCoverage.Reason.EXTENT,j->j.getJSONObject("assets").getJSONObject("visual").getJSONArray("proj:shape").put(0,999));
        reason(OamCoverage.Reason.SHAPE,j->j.getJSONObject("assets").getJSONObject("visual").getJSONArray("proj:shape").put(0,0));
        reason(OamCoverage.Reason.GEOMETRY,j->j.getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0).getJSONArray(1).put(1,20.005));
        reason(OamCoverage.Reason.ASSET,j->j.getJSONObject("assets").getJSONObject("visual").put("href","http://invalid/"));
        reason(OamCoverage.Reason.ID,j->j.put("id","../bad"));
        reason(OamCoverage.Reason.COLLECTION,j->j.put("collection","other"));
    }
    @Test public void accumulatesReasonsRetainsIdsAndDoesNotInventGeometry()throws Exception {
        JSONObject j=OamImageryTest.item();j.getJSONObject("properties").put("license","unknown").put("gsd",1).remove("title");j.remove("assets");j.remove("geometry");
        OamImagery.Search s=OamImagery.parseSearch(OamImageryTest.search(j),new OamImagery.Bounds(9.8,19.8,10.2,20.2));
        assertEquals(1,s.items.size());OamCoverage c=s.items.get(0);assertEquals("fixture-1",c.id);assertEquals(0,s.sources.size());assertTrue(c.polygons.isEmpty());
        assertTrue(c.reasons.containsAll(Arrays.asList(OamCoverage.Reason.LICENSE,OamCoverage.Reason.GSD,OamCoverage.Reason.METADATA,OamCoverage.Reason.ASSET,OamCoverage.Reason.CRS,OamCoverage.Reason.ROTATION,OamCoverage.Reason.SHAPE,OamCoverage.Reason.EXTENT,OamCoverage.Reason.GEOMETRY)));
    }
    @Test public void malformedGeometryNeverFallsBackToBbox()throws Exception {
        for(String geometry:new String[]{"null","{}","{\"type\":\"Point\",\"coordinates\":[10,20]}","{\"type\":\"Polygon\",\"coordinates\":[[[10,20],[11,20],[11,21],[10,21]]]}","{\"type\":\"Polygon\",\"coordinates\":[[[10,20],[11,20],[11,91],[10,20]]]}","{\"type\":\"Polygon\",\"coordinates\":[[[10,20],[\"NaN\",20],[11,21],[10,20]]]}"}){
            JSONObject j=OamImageryTest.item();j.put("geometry",new JSONTokener(geometry).nextValue());OamCoverage c=new OamCoverage(j);assertTrue(geometry,c.polygons.isEmpty());assertNull(c.source);assertTrue(c.reasons.contains(OamCoverage.Reason.GEOMETRY));
        }
        JSONObject degenerate=OamImageryTest.item();degenerate.getJSONObject("geometry").put("coordinates",new JSONArray("[[[10,20],[10,20],[10,20],[10,20]]]"));assertTrue(new OamCoverage(degenerate).polygons.isEmpty());
        JSONObject response=new JSONObject("{\"features\":[null,7,{}]}");assertEquals(3,OamImagery.parseSearch(response,OamImageryTest.bounds()).items.size());
    }
    @Test public void actualNonrectangularMultipartAndHolesRemainDrawable()throws Exception {
        JSONObject j=OamImageryTest.item();JSONArray rings=j.getJSONObject("geometry").getJSONArray("coordinates");rings.put(new JSONArray("[[10.002,20.002],[10.004,20.002],[10.004,20.004],[10.002,20.002]]"));
        j.getJSONObject("geometry").put("type","MultiPolygon").put("coordinates",new JSONArray().put(rings).put(new JSONArray().put(new JSONArray("[[10.02,20],[10.03,20],[10.025,20.01],[10.02,20]]"))));
        OamCoverage c=new OamCoverage(j);assertNull(c.source);assertEquals(2,c.polygons.size());assertEquals(2,c.polygons.get(0).size());assertTrue(c.reasons.contains(OamCoverage.Reason.GEOMETRY));
    }
    @Test public void discoveryAreaAndPartialOverlapDoNotExcludeEligibleSources()throws Exception {
        for(OamImagery.Bounds b:new OamImagery.Bounds[]{new OamImagery.Bounds(9.8,19.8,10.2,20.2),new OamImagery.Bounds(10.009,20.009,10.011,20.011)}){
            OamImagery.Search s=OamImagery.parseSearch(OamImageryTest.search(OamImageryTest.item()),b);assertEquals(1,s.sources.size());assertEquals(0,s.rejected);assertFalse(s.sources.get(0).coverage.contains(b));
            assertThrows(IllegalArgumentException.class,()->OamImagery.rasterUrl(s.sources.get(0),b));
        }
    }
    ActivityController<ImageryTraceActivity> activity(){ActivityController<ImageryTraceActivity> ctrl=Robolectric.buildActivity(ImageryTraceActivity.class,new Intent().putExtra("latitude",20.005).putExtra("longitude",10.005).putExtra("zoom",14)).setup();ctrl.get().coverage.layout(0,0,600,600);return ctrl;}
    void click(ImageryTraceActivity a,String key){OamImageryTest.button(a,key).performClick();}
    void pan(ImageryCoverageMap map,float dx,float dy){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));long time=android.os.SystemClock.uptimeMillis();MotionEvent a=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,300,300,0),b=MotionEvent.obtain(time,time+80,MotionEvent.ACTION_MOVE,300+dx,300+dy,0),c=MotionEvent.obtain(time,time+160,MotionEvent.ACTION_UP,300+dx,300+dy,0);map.onTouchEvent(a);map.onTouchEvent(b);map.onTouchEvent(c);a.recycle();b.recycle();c.recycle();}
    @Test public void actualCoverageControlsFootprintTapPanLoadTraceSaveReopen()throws Exception {
        ActivityController<ImageryTraceActivity> ctrl=activity();ImageryTraceActivity a=ctrl.get();OamImageryTest.clean(a.drafts);
        List<String> urls=Collections.synchronizedList(new ArrayList<>());byte[] json=OamImageryTest.search(OamImageryTest.item()).put("links",new JSONArray().put(new JSONObject().put("rel","next").put("href","https://invalid/not-followed"))).toString().getBytes(StandardCharsets.UTF_8),png=OamImageryTest.png();
        a.client=new OamImagery((u,l,c)->{urls.add(u);return u.contains("/stac/")?json:png;});
        try {
            assertTrue(a.coverageMode);assertTrue(urls.isEmpty());double old=a.coverage.centerLongitude();pan(a.coverage,20,0);assertNotEquals(old,a.coverage.centerLongitude(),1e-8);assertTrue(urls.isEmpty());
            OamImagery.Bounds expected=a.coverage.viewport();click(a,"imagery_search");OamImageryTest.await(a);assertEquals(1,urls.size());assertTrue(urls.get(0).endsWith(expected.csv()));assertEquals(1,a.coverage.items.size());assertTrue(a.status.getText().toString().contains(a.t("imagery_more_yes")));assertTrue(a.status.getText().toString().contains(a.t("imagery_search_limit")));
            // Native Path/Region hit testing, rather than invoking the inspector directly.
            assertNotNull("source: "+a.coverage.items.get(0).reasons,a.coverage.items.get(0).source);assertTrue(a.coverage.tapPhotos(a.coverage.screenX(10.005),a.coverage.sy(20.005)));AlertDialog list=ShadowAlertDialog.getLatestAlertDialog();list.getListView().performItemClick(null,0,0);AlertDialog details=ShadowAlertDialog.getLatestAlertDialog();details.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();assertNotNull(a.coverage.selected);
            OamImagery.Bounds before=a.coverage.fragment();pan(a.coverage,-25,0);OamImagery.Bounds after=a.coverage.fragment();assertNotEquals(before.w,after.w,1e-9);assertEquals(1,urls.size());
            click(a,"imagery_load_selection");OamImageryTest.await(a);assertNotNull(a.raster);assertFalse(a.coverageMode);assertEquals(after.csv(),a.raster.bounds.csv());assertEquals(2,urls.size());assertTrue(urls.get(1).contains("/512x512.png?"));a.raster.bounds.fragment();
            a.image.layout(0,0,512,512);OamImageryTest.tap(a,0,10,10);OamImageryTest.tap(a,1,20,20);OamImageryTest.tap(a,2,15,15);click(a,"imagery_trace");OamImageryTest.await(a);assertNotNull(a.candidate);click(a,"imagery_save");OamImageryTest.await(a);assertNotNull(a.savedId);
            String id=a.savedId;click(a,"imagery_coverage");assertTrue(a.coverageMode);a.openDraft(id);OamImageryTest.await(a);assertFalse(a.coverageMode);assertNotNull(a.candidate);assertTrue(a.export.isEnabled());assertEquals(2,urls.size());
            click(a,"imagery_export");assertEquals(Intent.ACTION_CREATE_DOCUMENT,shadowOf(a).getNextStartedActivityForResult().intent.getAction());
        }finally{OamImageryTest.clean(a.drafts);ctrl.pause().stop().destroy();}
    }
    @Test public void rejectedItemUiShowsAllReasonsAndCannotBeSelected()throws Exception {
        ActivityController<ImageryTraceActivity> ctrl=activity();ImageryTraceActivity a=ctrl.get();JSONObject denied=OamImageryTest.item();denied.getJSONObject("properties").put("license","unknown").put("gsd",2).remove("oam:producer_name");denied.remove("geometry");byte[] json=OamImageryTest.search(denied).toString().getBytes(StandardCharsets.UTF_8);a.client=new OamImagery((u,l,c)->json);
        try{click(a,"imagery_search");OamImageryTest.await(a);assertEquals(1,a.coverageResult.rejected);click(a,"imagery_sources");ShadowAlertDialog.getLatestAlertDialog().getListView().performItemClick(null,0,0);AlertDialog details=ShadowAlertDialog.getLatestAlertDialog();String message=((TextView)details.findViewById(android.R.id.message)).getText().toString();for(String key:new String[]{"license","gsd","metadata","geometry"})assertTrue(message.contains(a.t("imagery_reason_"+key)));assertTrue(message.contains(a.t("imagery_unknown_geometry")));assertNull(a.coverage.selected);assertFalse(details.getButton(AlertDialog.BUTTON_POSITIVE).getVisibility()==View.VISIBLE);click(a,"imagery_load_selection");assertEquals(a.t("imagery_choose_first"),a.status.getText().toString());}finally{ctrl.pause().stop().destroy();}
    }
    @Test public void fragmentOutsideAndOversizedDiscoverySendNoRequests()throws Exception {
        ActivityController<ImageryTraceActivity> ctrl=activity();ImageryTraceActivity a=ctrl.get();List<String> urls=new ArrayList<>();a.client=new OamImagery((u,l,c)->{urls.add(u);throw new AssertionError("No request allowed");});
        try{a.coverage.selected=new OamCoverage(OamImageryTest.item());a.coverage.showPlace(20.0099,10.0099);if(a.coverage.viewportMotion!=null)a.coverage.viewportMotion.end();click(a,"imagery_load_selection");assertEquals(a.t("imagery_fragment_outside"),a.status.getText().toString());a.coverage.zoom=2;click(a,"imagery_search");assertEquals(a.t("imagery_coverage_extent"),a.status.getText().toString());assertTrue(urls.isEmpty());assertThrows(IllegalArgumentException.class,()->new OamImagery.Bounds(10,20,10.01,20.01).fragment());}finally{ctrl.pause().stop().destroy();}
    }
    @Test public void cancelledOutOfOrderCoverageCannotReplaceNewSearch()throws Exception {
        ActivityController<ImageryTraceActivity> ctrl=activity();ImageryTraceActivity a=ctrl.get();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);byte[] old=OamImageryTest.search(OamImageryTest.item()).toString().getBytes(StandardCharsets.UTF_8);java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
        a.client=new OamImagery((u,l,c)->{if(requests.getAndIncrement()==0){entered.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}return old;}return "{\"features\":[]}".getBytes(StandardCharsets.UTF_8);});
        try{click(a,"imagery_search");assertTrue(entered.await(5,TimeUnit.SECONDS));pan(a.coverage,30,0);assertFalse(a.busy);click(a,"imagery_search");release.countDown();OamImageryTest.await(a);assertEquals(2,requests.get());assertEquals(0,a.coverageResult.returned);assertTrue(a.coverage.items.isEmpty());assertTrue(a.status.getText().toString().contains(a.t("imagery_more_no")));}finally{release.countDown();ctrl.pause().stop().destroy();}
    }
    @Test public void coverageUsesActualConnectedLocalVectorBasemap()throws Exception {
        WorldMapTest fixture=new WorldMapTest();fixture.setup();java.io.File file=fixture.database(fixture.fixture(),14,9903,5122,false);android.content.Context context=RuntimeEnvironment.getApplication();context.getSharedPreferences("world-map",0).edit().putString("uri",android.net.Uri.fromFile(file).toString()).commit();
        ActivityController<ImageryTraceActivity> ctrl=Robolectric.buildActivity(ImageryTraceActivity.class,new Intent().putExtra("latitude",55.748).putExtra("longitude",37.612).putExtra("zoom",17)).setup();ImageryTraceActivity a=ctrl.get();
        try{PerformanceTest.barrier(a.coverage,"mapWorker");shadowOf(Looper.getMainLooper()).idle();assertTrue(a.coverage.hasOffline());java.lang.reflect.Field field=MapView.class.getDeclaredField("world");field.setAccessible(true);WorldMap local=(WorldMap)field.get(a.coverage);assertNotNull(local.tile(14,9903,5122,true));a.coverage.layout(0,0,768,768);Bitmap image=Bitmap.createBitmap(768,768,Bitmap.Config.ARGB_8888);a.coverage.draw(new Canvas(image));Set<Integer> colors=new HashSet<>();for(int y=100;y<700;y+=5)for(int x=20;x<700;x+=5)colors.add(image.getPixel(x,y));assertTrue("Actual local streets/buildings should render: "+colors.size(),colors.size()>20);java.io.File output=new java.io.File("build/oam-live-evidence/coverage-local-basemap.png");output.getParentFile().mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(output)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}assertFalse(a.coverage.online);}finally{ctrl.pause().stop().destroy();context.getSharedPreferences("world-map",0).edit().clear().commit();fixture.cleanup();}
    }
    @Test public void networkPauseAndCancelledChooserAreGuarded()throws Exception {
        ActivityController<ImageryTraceActivity> ctrl=activity();ImageryTraceActivity a=ctrl.get();
        try{a.inspectCoverage(new OamCoverage(OamImageryTest.item()));AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();click(a,"imagery_cancel");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertNull(a.coverage.selected);MapNetwork.setEnabled(a,true);click(a,"imagery_network");assertFalse(MapNetwork.enabled(a));click(a,"imagery_search");OamImageryTest.await(a);assertTrue(a.status.getText().toString().startsWith(a.t("imagery_paused")));assertNull(a.coverageResult);click(a,"imagery_network");assertTrue(MapNetwork.enabled(a));}finally{MapNetwork.setEnabled(a,true);ctrl.pause().stop().destroy();}
    }
}
