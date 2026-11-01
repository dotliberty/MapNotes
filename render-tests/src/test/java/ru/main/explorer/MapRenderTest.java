package ru.main.explorer;
import android.app.Application;import android.graphics.*;import android.location.Location;import android.os.SystemClock;
import java.io.*;import java.lang.reflect.*;import java.util.*;
import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="ru-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MapRenderTest {
 MapView map;Application app;File satelliteFixture,buildingFixture;
 @Before public void setUp()throws Exception{app=RuntimeEnvironment.getApplication();map=new MapView(app);map.online=false;map.illustrated=false;map.layout(0,0,512,512);}
 @After public void tearDown(){map.close();if(satelliteFixture!=null)satelliteFixture.delete();if(buildingFixture!=null)buildingFixture.delete();}
 Location location(float acc){Location l=new Location("gps");l.setLatitude(55.748);l.setLongitude(37.612);l.setAccuracy(acc);l.setTime(System.currentTimeMillis());l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());return l;}
 void satellite(int rgb)throws Exception{int z=14,xx=(int)Math.floor(RegionPlan.x(37.612)*(1<<z)),yy=(int)Math.floor(RegionPlan.y(55.748)*(1<<z));Bitmap tile=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);tile.eraseColor(rgb);Field f=MapView.class.getDeclaredField("tiles");f.setAccessible(true);((android.util.LruCache<String,Bitmap>)f.get(map)).put("sat/"+z+"/"+xx+"/"+yy,tile);}
 Bitmap render(String name)throws Exception{Bitmap out=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);map.draw(new Canvas(out));File dir=new File("build/rendered");dir.mkdirs();try(FileOutputStream s=new FileOutputStream(new File(dir,name+".png"))){out.compress(Bitmap.CompressFormat.PNG,100,s);}return out;}
 @Test public void acceptedPointMustShowExactSatellitePixelNotBlueTint()throws Exception{
  Location l=location(10);map.setCurrent(l);ArrayList<double[]> p=new ArrayList<>();p.add(new double[]{l.getLatitude(),l.getLongitude(),1,l.getTime()});map.update(p);satellite(0xffc77739);
  Bitmap output=render("accepted-point");assertEquals("The GPS marker must not tint the revealed satellite at its center",0xffc77739,output.getPixel(256,256));
 }
 @Test public void currentAcceptedFixRevealsBeforeDatabaseRefresh()throws Exception{
  map.setCurrent(location(10),true);satellite(0xff58a938);
  Bitmap output=render("live-point-without-database");assertEquals(0xff58a938,output.getPixel(256,256));
  assertTrue(map.satelliteStatus().contains("открыт"));
 }
 @Test public void approximateFixDoesNotPaintBlueOrUnlockProgress()throws Exception{
  map.setCurrent(location(100),false);satellite(0xffc77739);
  Bitmap output=render("approximate-point");int pixel=output.getPixel(256,256);
  assertNotEquals("An approximate fix must not unlock satellite",0xffc77739,pixel);
  assertEquals("The center must stay a gray schematic, not a blue location fill",Color.red(pixel),Color.blue(pixel),12);
  assertTrue(map.satelliteStatus().contains("точный GPS"));
 }
 @Test public void hundredMetreRadiusHasCorrectGroundScale()throws Exception{
  map.setCurrent(location(10),true);satellite(0xffc77739);
  Bitmap output=render("hundred-metre-mask");assertEquals(0xffc77739,output.getPixel(256,256));
  assertEquals("Ground about 86 m away is revealed, rather than just a tiny dot",0xffc77739,output.getPixel(256,320));
  assertNotEquals("A point outside 100 metres must remain schematic",0xffc77739,output.getPixel(256,340));
 }
 @Test public void missingSatelliteIsExplainedRatherThanSilentlyShownAsBlue()throws Exception{
  map.setCurrent(location(10),true);assertTrue(map.satelliteStatus().contains("скачай"));
 }
 @Test public void actualOfflineJpegAppearsAtCurrentFix()throws Exception{
  Location l=location(10);map.setCurrent(l,true);File dir=new File(app.getFilesDir(),"satellite");dir.mkdirs();satelliteFixture=new File(dir,RegionPlan.tileName(14,9903,5122));
  try(InputStream in=getClass().getResourceAsStream("/satellite-14-9903-5122.jpg");FileOutputStream out=new FileOutputStream(satelliteFixture)){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
  map.loadSatellite("sat/14/9903/5122",14,9903,5122);Bitmap output=render("real-offline-satellite");
  Bitmap tile=BitmapFactory.decodeFile(satelliteFixture.getPath());int sx=(int)((RegionPlan.x(l.getLongitude())*(1<<14)-9903)*256),sy=(int)((RegionPlan.y(l.getLatitude())*(1<<14)-5122)*256);
  int expected=tile.getPixel(sx,sy),actual=output.getPixel(256,256);
  assertEquals(Color.red(expected),Color.red(actual),30);assertEquals(Color.green(expected),Color.green(actual),30);assertEquals(Color.blue(expected),Color.blue(actual),30);
  assertTrue("The saved satellite must be recognized as loaded",map.satelliteStatus().contains("открыт"));
 }
 @Test public void gpsCallbackRevealsAndSavesThroughActualActivity()throws Exception{
  org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION);
  org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();
  try{MainActivity activity=controller.get();Field field=MainActivity.class.getDeclaredField("map");field.setAccessible(true);map.close();map=(MapView)field.get(activity);map.asyncRendering=false;map.reloadRegions();map.online=false;map.illustrated=false;map.layout(0,0,512,512);
   activity.onLocationChanged(location(10));satellite(0xffc77739);Bitmap output=render("activity-gps-to-satellite");assertEquals(0xffc77739,output.getPixel(256,256));
   try(Store db=new Store(activity)){assertFalse("Accepted GPS must persist in the actual database",db.points().isEmpty());}
  }finally{controller.pause().stop().destroy();}
 }
 @Test public void wallStillBlocksSatelliteWithinHundredMetres()throws Exception{
  Location l=location(10);double lat=l.getLatitude(),lon=l.getLongitude(),kx=111195.08*Math.cos(Math.toRadians(lat)),ky=111195.08;
  org.json.JSONArray geometry=new org.json.JSONArray();for(double[] xy:new double[][]{{2,-4},{3,-4},{3,4},{2,4},{2,-4}})geometry.put(new org.json.JSONObject().put("lat",lat+xy[1]/ky).put("lon",lon+xy[0]/kx));
  org.json.JSONObject building=new org.json.JSONObject().put("tags",new org.json.JSONObject().put("building","yes")).put("geometry",geometry);
  org.json.JSONObject root=new org.json.JSONObject().put("mapnotes_bounds",new org.json.JSONArray(new double[]{lat-.001,lon-.001,lat+.001,lon+.001})).put("elements",new org.json.JSONArray().put(building));File dir=new File(app.getFilesDir(),"regions");dir.mkdirs();buildingFixture=new File(dir,"wall-test.json");try(FileOutputStream out=new FileOutputStream(buildingFixture)){out.write(root.toString().getBytes("UTF-8"));}
  map.reloadRegions();map.setCurrent(l,true);map.zoom=20;satellite(0xffc77739);Bitmap output=render("five-metre-wall-occlusion");double mpp=156543.03392*Math.cos(Math.toRadians(lat))/(1<<map.zoom);
  assertEquals("Visible ground before the wall reveals satellite",0xffc77739,output.getPixel(256-(int)(3/mpp),256));
  assertNotEquals("Inside the building remains schematic",0xffc77739,output.getPixel(256+(int)(2.5/mpp),256));
  assertNotEquals("Behind the building remains schematic",0xffc77739,output.getPixel(256+(int)(4/mpp),256));
 }
 @Test public void noSatelliteDoesNotProduceFakeGreenReveal()throws Exception{
  map.setCurrent(location(10),false);Bitmap before=render("missing-satellite-before");map.setCurrent(location(10),true);Bitmap after=render("missing-satellite-after");assertEquals("Missing imagery keeps the schematic color",before.getPixel(256,256),after.getPixel(256,256));
 }
 @Test public void highLatitudePackUsesSavedParentTilesOffline()throws Exception{
  Location l=location(10);map.setCurrent(l,true);File directory=new File(app.getFilesDir(),"satellite");directory.mkdirs();satelliteFixture=new File(directory,RegionPlan.tileName(12,9903/4,5122/4));Bitmap tile=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);tile.eraseColor(0xffa1b66a);try(FileOutputStream out=new FileOutputStream(satelliteFixture)){tile.compress(Bitmap.CompressFormat.PNG,100,out);}map.loadSatellite("sat/14/9903/5122",14,9903,5122);Bitmap result=render("parent-tile-offline");assertEquals(0xffa1b66a,result.getPixel(256,256));
 }
}
