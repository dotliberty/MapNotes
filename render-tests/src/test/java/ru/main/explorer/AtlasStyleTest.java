package ru.main.explorer;
import android.app.Application;import android.graphics.*;import android.view.View;import java.io.*;import java.util.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34,qualifiers="mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AtlasStyleTest {
 @Test public void routeFillsBetweenFixesButNeverBetweenSessions()throws Exception{
  Application app=RuntimeEnvironment.getApplication();MapView map=new MapView(app);map.online=false;map.follow=false;map.layout(0,0,512,512);map.zoom=17;double lat=55.748,lon=37.612,kx=111195.08*Math.cos(Math.toRadians(lat));map.cx=MapView.x(lon);map.cy=MapView.y(lat);
  ArrayList<double[]> points=new ArrayList<>();points.add(new double[]{lat,lon-70/kx,1,1000});points.add(new double[]{lat,lon+70/kx,1,20000});map.update(points);
  try{Path path=map.routeReveal();Region region=new Region();region.setPath(path,new Region(0,0,512,512));double mpp=156543.03392*Math.cos(Math.toRadians(lat))/(1<<17);int py=256-(int)(95/mpp);assertTrue("Continuous strip covers the scallop between endpoint circles",region.contains(256,py));points.get(1)[2]=2;map.update(points);region.setPath(map.routeReveal(),new Region(0,0,512,512));assertFalse("Separate sessions must not reveal the connecting strip",region.contains(256,py));assertFalse(MapView.connects(new double[]{lat,lon,1,1},new double[]{lat,lon,1,31002}));}finally{map.close();}
 }
 @Test public void fullActivityPreviewWithMapGeometry()throws Exception{
  Application app=RuntimeEnvironment.getApplication();app.getSharedPreferences("ru.main.explorer.MainActivity",0).edit().putBoolean("intro",true).apply();
  org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();
  try{MainActivity activity=controller.get();java.lang.reflect.Field f=MainActivity.class.getDeclaredField("map");f.setAccessible(true);MapView map=(MapView)f.get(activity);map.asyncRendering=false;map.reloadRegions();map.online=false;map.follow=false;map.zoom=16;double lat=55.748,lon=37.612;map.cx=MapView.x(lon);map.cy=MapView.y(lat);
   File dir=new File(app.getFilesDir(),"regions");dir.mkdirs();File file=new File(dir,"preview.json");org.json.JSONArray elements=new org.json.JSONArray();
   for(int i=-3;i<=3;i++){org.json.JSONArray geom=new org.json.JSONArray();geom.put(new org.json.JSONObject().put("lat",lat-.012).put("lon",lon+i*.003));geom.put(new org.json.JSONObject().put("lat",lat+.012).put("lon",lon+i*.003+.002));elements.put(new org.json.JSONObject().put("tags",new org.json.JSONObject().put("highway","residential").put("name",i==0?"Улица путешественников":"")).put("geometry",geom));}
   String json=new org.json.JSONObject().put("mapnotes_bounds",new org.json.JSONArray(new double[]{lat-.02,lon-.03,lat+.02,lon+.03})).put("elements",elements).toString();try(FileOutputStream out=new FileOutputStream(file)){out.write(json.getBytes("UTF-8"));}map.reloadRegions();ArrayList<double[]> points=new ArrayList<>();for(int i=-6;i<=6;i++)points.add(new double[]{lat+i*.00025,lon+i*.00008,1,100000+i*1000});map.update(points);
   View root=activity.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(432,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY));root.layout(0,0,432,960);Bitmap image=Bitmap.createBitmap(432,960,Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));File target=new File("build/rendered/atlas-ui.png");target.getParentFile().mkdirs();try(FileOutputStream out=new FileOutputStream(target)){image.compress(Bitmap.CompressFormat.PNG,100,out);}assertTrue(map.illustrated);file.delete();
  }finally{controller.pause().stop().destroy();}
 }
}
