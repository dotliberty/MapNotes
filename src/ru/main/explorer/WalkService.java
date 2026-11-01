package ru.main.explorer;
import android.app.*;import android.content.*;import android.content.pm.ServiceInfo;import android.location.*;import android.os.*;
public class WalkService extends Service implements LocationListener {
 public static volatile boolean running=false;public static volatile String status="";
 private static volatile StatusText statusText=new StatusText("start_hint");
 private LocationManager manager;private Store store;private GpsFilter filter;private long session;private boolean foreground;
 private final Handler ui=new Handler(Looper.getMainLooper());
 private final SharedPreferences.OnSharedPreferenceChangeListener languageChanged=(prefs,key)->ui.post(()->{if(foreground){status=currentStatus(this);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1,notification());}});
 protected void attachBaseContext(Context base){super.attachBaseContext(base);Language.initialize(base);}
 public void onCreate(){super.onCreate();Language.initialize(this);getSharedPreferences(Language.PREFS,0).registerOnSharedPreferenceChangeListener(languageChanged);store=new Store(this);filter=new GpsFilter();session=System.currentTimeMillis();manager=(LocationManager)getSystemService(LOCATION_SERVICE);}
 static String currentStatus(Context context){return statusText.render(context);}
 void setStatus(String key,Object...args){statusText=new StatusText(key,args);status=currentStatus(this);}
 Notification notification(){
  NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);n.createNotificationChannel(new NotificationChannel("walk",Language.text(this,R.string.walk_recording),NotificationManager.IMPORTANCE_LOW));
  PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
  PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,WalkService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE);
  return new Notification.Builder(this,"walk").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle(Language.text(this,R.string.mapnotes_walk_in_progress)).setContentText(Language.text(this,R.string.gps_reveals_the_map_tap_stop_to_finish)).setContentIntent(open).addAction(new Notification.Action.Builder(null,Language.text(this,R.string.stop),stop).build()).setOngoing(true).build();
 }
 public int onStartCommand(Intent i,int flags,int id){
  if(i!=null&&"STOP".equals(i.getAction())){running=false;foreground=false;store.finishWalk(System.currentTimeMillis());stopSelf();return START_NOT_STICKY;}
  if(running)return START_NOT_STICKY;
  Notification notification=notification();foreground=true;
  if(Build.VERSION.SDK_INT>=29)startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);else startForeground(1,notification);
  try{manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this);session=store.newWalkSession(store.startWalk(System.currentTimeMillis()));running=true;setStatus("finding_precise_gps");}catch(Exception e){setStatus("gps_unavailable_enable_location");stopSelf();}
  return START_NOT_STICKY;
 }
 public void onLocationChanged(Location l){
  if(!running)return;
  if(!l.hasAccuracy()){setStatus("gps_accuracy_unavailable");return;}
  if(l.hasSpeed()&&l.getSpeed()>4.5f){setStatus("speed_too_high_for_a_walk");return;}
  String why=filter.accept(new GpsFilter.Fix(l.getLatitude(),l.getLongitude(),l.getAccuracy(),l.getElapsedRealtimeNanos(),l.isFromMockProvider()),SystemClock.elapsedRealtimeNanos());
  if(why==null){try{store.add(l,session);setStatus("walk_accuracy",Math.round(l.getAccuracy()));}catch(Exception e){setStatus("could_not_save_location");stopSelf();}}else setStatus(why);
 }
 public void onProviderDisabled(String p){setStatus("enable_gps_in_device_settings");}
 public void onProviderEnabled(String p){setStatus("finding_precise_gps");}
 public void onStatusChanged(String p,int s,Bundle b){}
 public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);if(foreground){status=currentStatus(this);((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1,notification());}}
 public IBinder onBind(Intent i){return null;}
 public void onDestroy(){foreground=false;getSharedPreferences(Language.PREFS,0).unregisterOnSharedPreferenceChangeListener(languageChanged);ui.removeCallbacksAndMessages(null);manager.removeUpdates(this);store.close();running=false;super.onDestroy();}
}
