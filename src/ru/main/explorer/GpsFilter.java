package ru.main.explorer;

/** Monotonic time only. Rejected fixes never move the trusted anchor. */
public final class GpsFilter {
 public static final class Fix {
  public final double lat,lon; public final float accuracy; public final long nanos; public final boolean mock;
  public Fix(double a,double o,float r,long t,boolean m){lat=a;lon=o;accuracy=r;nanos=t;mock=m;}
 }
 private Fix anchor;
 /** Null accepts the fix; otherwise returns a stable app message ID, never user text. */
 public String accept(Fix f,long now){
  if(f.mock)return "mock_gps_location";
  if(!Double.isFinite(f.lat)||!Double.isFinite(f.lon)||Math.abs(f.lat)>85.05112878||Math.abs(f.lon)>180)return "invalid_coordinates";
  if(!Float.isFinite(f.accuracy)||f.accuracy<=0||f.accuracy>30)return "poor_gps_accuracy";
  if(f.nanos<=0||now-f.nanos>15_000_000_000L||f.nanos>now+1_000_000_000L)return "stale_location";
  if(anchor!=null){
   double dt=(f.nanos-anchor.nanos)/1e9;
   if(dt<=0)return "invalid_gps_time";
   double d=distance(anchor.lat,anchor.lon,f.lat,f.lon);
   if(dt>120){anchor=null;return "gps_gap_waiting_for_the_next_location";}
   if(d/dt>4.5 || (d>anchor.accuracy+f.accuracy+8 && d/dt>3.2))return "too_fast_gps_jump";
  }
  anchor=f;return null;
 }
 public static double distance(double a,double o,double b,double p){double x=Math.toRadians(b-a),y=Math.toRadians(p-o);double h=Math.sin(x/2)*Math.sin(x/2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(b))*Math.sin(y/2)*Math.sin(y/2);return 6371008.8*2*Math.asin(Math.sqrt(Math.min(1,h)));}
}
