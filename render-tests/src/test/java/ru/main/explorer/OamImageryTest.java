package ru.main.explorer;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class OamImageryTest {
    static JSONObject item()throws Exception{
        JSONObject asset=new JSONObject().put("href","https://example.invalid/source.tif").put("proj:code","EPSG:4326")
            .put("proj:bbox",new JSONArray("[10,20,10.01,20.01]")).put("proj:shape",new JSONArray("[1000,1000]"))
            .put("proj:transform",new JSONArray("[0.00001,0,10,0,-0.00001,20.01,0,0,1]"));
        JSONObject props=new JSONObject().put("title","Synthetic fixture").put("oam:producer_name","Test producer").put("license","CC-BY-4.0").put("gsd",.1).put("start_datetime","2022-11-20T12:30:00Z");
        return new JSONObject().put("id","fixture-1").put("collection","openaerialmap").put("geometry",new JSONObject().put("type","Polygon").put("coordinates",new JSONArray("[[[10,20],[10.01,20],[10.01,20.01],[10,20.01],[10,20]]]"))).put("properties",props).put("assets",new JSONObject().put("visual",asset));
    }
    static OamImagery.Bounds bounds(){return new OamImagery.Bounds(10.001,20.001,10.002,20.002);}
    static JSONObject search(JSONObject... items)throws Exception{JSONArray a=new JSONArray();for(JSONObject j:items)a.put(j);return new JSONObject().put("features",a);}
    static byte[] png(){Bitmap b=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);b.eraseColor(0xff888888);ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,out);return out.toByteArray();}
    @Test public void explicitLicenseOnlyNoCollectionFallback()throws Exception{
        for(String licence:new String[]{"","CC-BY-SA-4.0","other","cc-by-4.0"}){JSONObject j=item();j.getJSONObject("properties").put("license",licence);assertEquals(0,OamImagery.parseSearch(search(j),bounds()).sources.size());}
        assertEquals(1,OamImagery.parseSearch(search(item()),bounds()).sources.size());
    }
    @Test public void rejectsCoarseMissingOrInvalidGsdAndMetadata()throws Exception{
        for(double g:new double[]{0,-1,.51,10}){JSONObject j=item();j.getJSONObject("properties").put("gsd",g);assertEquals(0,OamImagery.parseSearch(search(j),bounds()).sources.size());}
        JSONObject j=item();j.getJSONObject("properties").remove("gsd");assertEquals(0,OamImagery.parseSearch(search(j),bounds()).sources.size());
        j=item();j.getJSONObject("properties").remove("oam:producer_name");assertEquals(0,OamImagery.parseSearch(search(j),bounds()).sources.size());
    }
    @Test public void rejectsUnsupportedCrsRotationAndInconsistentTransform()throws Exception{
        JSONObject j=item();j.getJSONObject("assets").getJSONObject("visual").put("proj:code","EPSG:3857");assertEquals(0,OamImagery.parseSearch(search(j),bounds()).sources.size());
        j=item();j.getJSONObject("assets").getJSONObject("visual").getJSONArray("proj:transform").put(1,.000001);assertEquals(0,OamImagery.parseSearch(search(j),bounds()).sources.size());
        j=item();j.getJSONObject("assets").getJSONObject("visual").getJSONArray("proj:shape").put(0,100);assertEquals(0,OamImagery.parseSearch(search(j),bounds()).sources.size());
    }
    @Test public void rectangularFootprintOnlyAndRoundedMultiPolygonSupported()throws Exception{
        JSONObject j=item();JSONObject geometry=j.getJSONObject("geometry");JSONArray rings=geometry.getJSONArray("coordinates");geometry.put("type","MultiPolygon").put("coordinates",new JSONArray().put(rings));assertEquals(1,OamImagery.parseSearch(search(j),bounds()).sources.size());
        j=item();j.getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0).getJSONArray(1).put(1,20.005);assertEquals(0,OamImagery.parseSearch(search(j),bounds()).sources.size());
        j=item();j.getJSONObject("assets").getJSONObject("visual").getJSONArray("proj:transform").put(0,"NaN");assertEquals(0,OamImagery.parseSearch(search(j),bounds()).sources.size());
        j=item();j.getJSONObject("properties").put("start_datetime","2022-99-99T00:00:00Z");assertEquals(0,OamImagery.parseSearch(search(j),bounds()).sources.size());
    }
    @Test public void noCoverageDistinctFromIneligibleAndPartial()throws Exception{
        OamImagery.Search empty=OamImagery.parseSearch(search(),bounds());assertEquals(0,empty.returned);
        OamImagery.Search partial=OamImagery.parseSearch(search(item()),new OamImagery.Bounds(10.009,20.009,10.011,20.011));assertEquals(1,partial.returned);assertEquals(0,partial.rejected);assertEquals(1,partial.sources.size());
    }
    @Test public void exactBoundedRasterEndpointAndPixelCentreTransform()throws Exception{
        OamImagery.Source source=new OamImagery.Source(item());String url=OamImagery.rasterUrl(source,bounds());
        assertTrue(url.contains("/raster/collections/openaerialmap/items/fixture-1/bbox/"));assertTrue(url.contains("/512x512.png?assets=visual"));assertTrue(url.contains("dst_crs=EPSG%3A4326"));assertFalse(url.contains("source.tif"));
        double[] p=bounds().reference().coordinate(0,0,512,512);assertEquals(20.002-.001/1024,p[0],1e-12);assertEquals(10.001+.001/1024,p[1],1e-12);
        assertThrows(IllegalArgumentException.class,()->OamImagery.rasterUrl(source,new OamImagery.Bounds(10,20,10.01,20.01)));
    }
    @Test public void boundedDecodeRejectsHugeAndWrongDimensions()throws Exception{
        assertNotNull(OamImagery.decode(png()));assertThrows(IOException.class,()->OamImagery.decode(new byte[OamImagery.PNG_LIMIT+1]));
        ByteArrayOutputStream out=new ByteArrayOutputStream();Bitmap.createBitmap(1024,1024,Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG,100,out);assertThrows(IOException.class,()->OamImagery.decode(out.toByteArray()));
    }
    @Test public void mockTransportUsesExplicitRequestsAndPropagatesFailures()throws Exception{
        List<String> urls=new ArrayList<>();byte[] json=search(item()).toString().getBytes(StandardCharsets.UTF_8),image=png();
        OamImagery c=new OamImagery((url,limit,cancel)->{urls.add(url);assertFalse(Looper.myLooper()==Looper.getMainLooper());return url.contains("/stac/")?json:image;});
        ExecutorService worker=Executors.newSingleThreadExecutor();try{worker.submit(()->{try{OamImagery.Search s=c.search(bounds(),()->false);c.load(s.sources.get(0),bounds(),()->false);}catch(Exception e){throw new RuntimeException(e);}}).get();}finally{worker.shutdownNow();}
        assertEquals(2,urls.size());assertTrue(urls.get(0).contains("collections=openaerialmap&limit=30&bbox="));
        assertThrows(IOException.class,()->new OamImagery((u,l,x)->{throw new IOException("offline");}).search(bounds(),()->false));
    }
    @Test public void networkPausePreventsConnection()throws Exception{
        android.content.Context c=RuntimeEnvironment.getApplication();MapNetwork.setEnabled(c,false);
        try{assertThrows(MapNetwork.Paused.class,()->OamImagery.network(c,Collections.synchronizedSet(new HashSet<>())).search(bounds(),()->false));}finally{MapNetwork.setEnabled(c,true);}
    }
    static ImageryTrace.Candidate candidate(OamImagery.Raster r){int[] pixels=new int[512*512];Arrays.fill(pixels,0xff888888);return ImageryTrace.trace(512,512,pixels,null,10,10,20,20,15,15,0,r.bounds.reference(),r.source.provenance(),()->false);}
    static OamImagery.Raster raster()throws Exception{OamImagery.Source s=new OamImagery.Source(item());byte[] p=png();return new OamImagery.Raster(s,bounds(),OamImagery.decode(p),p,OamImagery.rasterUrl(s,bounds()));}
    static void clean(ImageryDrafts d){File[] f=d.directory.listFiles();if(f!=null)for(File file:f)file.delete();}
    @Test public void atomicPersistenceReopensImageGeometryAndProvenance()throws Exception{
        ImageryDrafts d=new ImageryDrafts(RuntimeEnvironment.getApplication());clean(d);OamImagery.Raster r=raster();ImageryTrace.Candidate c=candidate(r);
        String id=d.save(r,c);assertEquals(Collections.singletonList(id),d.list());ImageryDrafts.Draft read=d.read(id);assertEquals(c.vertices.size(),read.candidate.vertices.size());assertEquals(r.source.captured,read.candidate.source.capturedAt);assertEquals("CC-BY-4.0",read.candidate.source.permission);assertArrayEquals(r.png,read.raster.png);
        JSONObject p=new JSONObject(read.geojson).getJSONObject("properties");assertFalse(p.getBoolean("verified_road"));assertEquals(r.request,p.getString("raster_request"));assertTrue(p.getString("derived_by").contains("candidate"));clean(d);
    }
    @Test public void corruptDraftAndTamperedGeometryRejected()throws Exception{
        OamImagery.Raster r=raster();JSONObject j=ImageryDrafts.record(r,candidate(r));j.getJSONObject("feature").getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0).put(0,99);
        assertThrows(IOException.class,()->ImageryDrafts.parse(j));assertThrows(IOException.class,()->new ImageryDrafts(RuntimeEnvironment.getApplication()).read("../../walks"));
    }
    static void await(ImageryTraceActivity a)throws Exception{for(int i=0;i<300;i++){shadowOf(Looper.getMainLooper()).idle();if(!a.busy)return;Thread.sleep(10);}fail("Worker did not settle");}
    static Button button(ImageryTraceActivity a,String tag){return a.findViewById(android.R.id.content).findViewWithTag(tag);}
    static void tap(ImageryTraceActivity a,int mode,float x,float y){a.select(mode);MotionEvent e=MotionEvent.obtain(0,0,MotionEvent.ACTION_UP,x,y,0);a.image.onTouchEvent(e);e.recycle();}
    @Test public void actualUiSearchSelectTouchTraceSaveReopenExportFlow()throws Exception{
        ActivityController<ImageryTraceActivity> ctrl=Robolectric.buildActivity(ImageryTraceActivity.class,new Intent().putExtra("bbox",bounds().csv())).setup();ImageryTraceActivity a=ctrl.get();clean(a.drafts);
        byte[] json=search(item()).toString().getBytes(StandardCharsets.UTF_8),png=png();a.client=new OamImagery((url,limit,c)->{assertNotEquals(Looper.getMainLooper(),Looper.myLooper());return url.contains("/stac/")?json:png;});
        button(a,"imagery_search").performClick();await(a);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();dialog.getListView().performItemClick(null,0,0);await(a);assertNotNull(a.raster);assertNull(a.candidate);assertFalse(a.trace.isEnabled());
        a.image.layout(0,0,512,512);tap(a,0,10,10);tap(a,1,20,20);tap(a,2,15,15);assertTrue(a.trace.isEnabled());button(a,"imagery_trace").performClick();await(a);assertNotNull(a.candidate);assertTrue(a.save.isEnabled());
        button(a,"imagery_save").performClick();await(a);assertNotNull(a.savedId);String id=a.savedId;assertFalse(a.save.isEnabled());a.clearImage();a.openDraft(id);await(a);assertNotNull(a.candidate);assertNotNull(a.raster);assertTrue(a.export.isEnabled());
        button(a,"imagery_export").performClick();Intent intent=shadowOf(a).getNextStartedActivityForResult().intent;assertEquals(Intent.ACTION_CREATE_DOCUMENT,intent.getAction());assertEquals("application/geo+json",intent.getType());
        File exported=new File(a.getFilesDir(),"candidate-export-test.geojson");a.onActivityResult(71,android.app.Activity.RESULT_OK,new Intent().setData(android.net.Uri.fromFile(exported)));await(a);assertEquals(a.t("imagery_exported"),a.status.getText().toString());assertEquals(a.exportJson,new String(java.nio.file.Files.readAllBytes(exported.toPath()),StandardCharsets.UTF_8));exported.delete();
        clean(a.drafts);ctrl.pause().stop().destroy();
    }
    @Test public void cancelledOldSearchCannotPublishAfterNewViewport()throws Exception{
        ActivityController<ImageryTraceActivity> ctrl=Robolectric.buildActivity(ImageryTraceActivity.class,new Intent().putExtra("bbox",bounds().csv())).setup();ImageryTraceActivity a=ctrl.get();a.bbox.setText(bounds().csv());CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        byte[] response=search(item()).toString().getBytes(StandardCharsets.UTF_8);a.client=new OamImagery((u,l,c)->{entered.countDown();while(true){try{release.await();break;}catch(InterruptedException ignored){}}return response;});
        a.search();assertTrue(entered.await(5,TimeUnit.SECONDS));a.bbox.setText("10.003,20.003,10.004,20.004");long token=a.generation;release.countDown();Thread.sleep(80);shadowOf(Looper.getMainLooper()).idle();assertEquals(token,a.generation);assertNull(a.raster);assertNull(a.candidate);assertEquals(a.t("imagery_ready"),a.status.getText().toString());ctrl.pause().stop().destroy();
    }
    @Test public void uiDistinguishesNoCoverageIneligibleAndNetworkFailure()throws Exception{
        ActivityController<ImageryTraceActivity> ctrl=Robolectric.buildActivity(ImageryTraceActivity.class,new Intent().putExtra("bbox",bounds().csv())).setup();ImageryTraceActivity a=ctrl.get();
        a.client=new OamImagery((u,l,c)->"{\"features\":[]}".getBytes(StandardCharsets.UTF_8));a.search();await(a);assertEquals(a.t("imagery_no_coverage"),a.status.getText().toString());
        JSONObject denied=item();denied.getJSONObject("properties").put("license","CC-BY-SA-4.0");byte[] json=search(denied).toString().getBytes(StandardCharsets.UTF_8);a.client=new OamImagery((u,l,c)->json);a.search();await(a);assertEquals(a.t("imagery_no_eligible"),a.status.getText().toString());
        a.client=new OamImagery((u,l,c)->{throw new IOException("fixture offline");});a.search();await(a);assertTrue(a.status.getText().toString().startsWith(a.t("imagery_failure")));ctrl.pause().stop().destroy();
    }
    @Test public void menuLaunchesFrozenViewportAndRussianLabels()throws Exception{
        Context context=RuntimeEnvironment.getApplication();Language.select(context,"ru");
        ActivityController<MainActivity> main=Robolectric.buildActivity(MainActivity.class).create();MainActivity m=main.get();m.menu();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();dialog.getListView().performItemClick(null,9,9);Intent launch=shadowOf(m).getNextStartedActivity();assertEquals(ImageryTraceActivity.class.getName(),launch.getComponent().getClassName());assertTrue(launch.hasExtra("latitude"));assertTrue(launch.hasExtra("longitude"));main.destroy();ActivityController<ImageryTraceActivity> ctrl=Robolectric.buildActivity(ImageryTraceActivity.class,launch).setup();assertEquals("Найти в OAM",button(ctrl.get(),"imagery_search").getText().toString());ctrl.pause().stop().destroy();Language.select(context,"system");
    }
    @Test public void lifecycleCancelsPendingWorkAndDemoIsExplicit()throws Exception{
        ActivityController<ImageryTraceActivity> ctrl=Robolectric.buildActivity(ImageryTraceActivity.class).setup();ImageryTraceActivity a=ctrl.get();String initial=a.bbox.getText().toString();button(a,"imagery_demo").performClick();assertNotEquals(initial,a.bbox.getText().toString());assertEquals(a.t("imagery_demo_notice"),a.status.getText().toString());assertNull(a.raster);
        long g=a.generation;ctrl.pause();assertTrue(a.inactive);assertTrue(a.generation>g);ctrl.stop().destroy();
    }
}
