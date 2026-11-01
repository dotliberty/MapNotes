import ru.main.explorer.GpsFilter;
public class GpsFilterTest {
 static long base=1000000000000L;
 static GpsFilter.Fix f(double lat,double lon,float acc,int sec,boolean mock){return new GpsFilter.Fix(lat,lon,acc,base+sec*1000000000L,mock);}
 static void ok(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args){
  GpsFilter g=new GpsFilter();
  ok(g.accept(f(55,37,10,0,false),base)==null,"first accurate fix");
  ok(g.accept(f(55.00001,37,10,2,false),base+2000000000L)==null,"sub-five-metre updates accepted");
  ok(g.accept(f(56,38,10,4,false),base+4000000000L)!=null,"teleport rejected");
  ok(g.accept(f(55.0001,37,10,10,false),base+10000000000L)==null,"trusted anchor survives teleport");
  ok(g.accept(f(55.0002,37,60,20,false),base+20000000000L)!=null,"poor accuracy rejected");
  ok(g.accept(f(55.0002,37,10,20,true),base+20000000000L)!=null,"mock rejected");
  ok(g.accept(f(55.0002,37,10,20,false),base+50000000000L)!=null,"stale rejected");
  ok(g.accept(f(Double.NaN,37,10,20,false),base+20000000000L)!=null,"NaN rejected");
  ok(g.accept(f(55.0002,37,10,5,false),base+20000000000L)!=null,"out of order rejected");
  ok(g.accept(f(55.1,37,10,200,false),base+200000000000L)!=null,"long gap breaks anchor");
  ok(g.accept(f(55.1,37,10,202,false),base+202000000000L)==null,"GPS recovers after gap");
  ok(Math.abs(GpsFilter.distance(0,179.999,0,-179.999)-222.39)<1,"date line");
  GpsFilter fine=new GpsFilter();fine.accept(f(55,37,5,0,false),base);
  ok(fine.accept(f(55.000004,37,5,1,false),base+1000000000L)==null,"sub-metre walking updates");
  ok(fine.accept(f(55.000004,37,5,2,false),base+2000000000L)==null,"stationary real-time updates");
  System.out.println("PASS: 14 GPS filter checks");
 }
}
