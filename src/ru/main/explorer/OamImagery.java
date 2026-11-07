package ru.main.explorer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;

/** Restricted OAM pilot. Never follows provider asset URLs or downloads original TIFFs. */
final class OamImagery {
    static final String ROOT="https://api.imagery.hotosm.org";
    static final int SIDE=512, JSON_LIMIT=1024*1024, PNG_LIMIT=2*1024*1024;
    interface Transport { byte[] get(String url,int limit,BooleanSupplier cancelled) throws Exception; }
    final Transport transport;
    OamImagery(Transport transport){this.transport=transport;}
    static OamImagery network(Context context,Set<HttpURLConnection> connections){
        Context c=context.getApplicationContext();
        return new OamImagery((url,limit,cancel)->{
            check(cancel); if(!MapNetwork.enabled(c))throw new MapNetwork.Paused();
            HttpURLConnection h=(HttpURLConnection)new URL(url).openConnection();
            h.setConnectTimeout(12000);h.setReadTimeout(20000);h.setInstanceFollowRedirects(false);MapNetwork.configure(h);
            synchronized(connections){connections.add(h);}
            try {
                MapNetwork.register(c,h);check(cancel);
                if(h.getResponseCode()!=200)throw new IOException("HTTP "+h.getResponseCode());
                if(h.getContentLengthLong()>limit)throw new IOException("Response exceeds budget");
                try(InputStream in=h.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] b=new byte[8192];int n;
                    while((n=in.read(b))!=-1){check(cancel);if(!MapNetwork.enabled(c))throw new MapNetwork.Paused();if(out.size()+n>limit)throw new IOException("Response exceeds budget");out.write(b,0,n);}
                    return out.toByteArray();
                }
            }finally{synchronized(connections){connections.remove(h);}MapNetwork.finish(h);}
        });
    }
    static void check(BooleanSupplier c){if(Thread.currentThread().isInterrupted()||c.getAsBoolean())throw new CancellationException();}
    static final class Bounds {
        final double w,s,e,n;
        Bounds(double w,double s,double e,double n){new ImageryTrace.GeoReference(w,s,e,n,false);if(s< -85||n>85||e-w>1||n-s>1)throw new IllegalArgumentException("Unsupported extent");this.w=w;this.s=s;this.e=e;this.n=n;}
        String csv(){return w+","+s+","+e+","+n;}
        static Bounds parse(String s){String[] a=s.trim().split(",");if(a.length!=4)throw new IllegalArgumentException("bbox");return new Bounds(Double.parseDouble(a[0].trim()),Double.parseDouble(a[1].trim()),Double.parseDouble(a[2].trim()),Double.parseDouble(a[3].trim()));}
        boolean contains(Bounds b){return b.w>=w&&b.e<=e&&b.s>=s&&b.n<=n;}
        void fragment(){double x=(e-w)*111320*Math.cos(Math.toRadians((s+n)/2)),y=(n-s)*111320;if(x>256||y>256||x<1||y<1)throw new IllegalArgumentException("Fragment must be 1–256 m per side");}
        ImageryTrace.GeoReference reference(){return new ImageryTrace.GeoReference(w,s,e,n,false);}
    }
    static final class Source {
        final String id,title,producer,captured,asset;final double gsd;final Bounds footprint,coverage;final JSONObject item;
        Source(JSONObject item)throws JSONException{
            if(!"openaerialmap".equals(item.getString("collection")))throw new IllegalArgumentException("collection");
            this.item=new JSONObject(item.toString());id=item.getString("id");if(!id.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("id");
            JSONObject p=item.getJSONObject("properties");if(!"CC-BY-4.0".equals(p.optString("license")))throw new IllegalArgumentException("license");
            gsd=p.getDouble("gsd");if(!Double.isFinite(gsd)||gsd<=0||gsd>.5)throw new IllegalArgumentException("gsd");
            title=required(p.optString("title"));producer=required(p.optString("oam:producer_name"));
            captured=required(p.optString("datetime",p.optString("start_datetime")));if(!captured.matches("\\d{4}-\\d{2}-\\d{2}T.*"))throw new IllegalArgumentException("capture date");try{java.time.OffsetDateTime.parse(captured);}catch(java.time.DateTimeException invalid){throw new IllegalArgumentException("capture date",invalid);}
            JSONObject a=item.getJSONObject("assets").getJSONObject("visual");asset=required(a.getString("href"));if(!asset.startsWith("https://"))throw new IllegalArgumentException("asset");
            if(!"EPSG:4326".equals(a.optString("proj:code")))throw new IllegalArgumentException("CRS");
            JSONArray box=a.getJSONArray("proj:bbox"),t=a.getJSONArray("proj:transform"),shape=a.getJSONArray("proj:shape");
            footprint=new Bounds(box.getDouble(0),box.getDouble(1),box.getDouble(2),box.getDouble(3));
            if(t.length()!=9)throw new IllegalArgumentException("transform");for(int i=0;i<9;i++)if(!Double.isFinite(t.getDouble(i)))throw new IllegalArgumentException("Nonfinite transform");
            if(t.length()!=9||shape.length()!=2||t.getDouble(1)!=0||t.getDouble(3)!=0||t.getDouble(0)<=0||t.getDouble(4)>=0||t.getDouble(6)!=0||t.getDouble(7)!=0||t.getDouble(8)!=1)throw new IllegalArgumentException("transform");
            int h=shape.getInt(0),w=shape.getInt(1);if(w<1||h<1||w>1000000||h>1000000)throw new IllegalArgumentException("shape");
            if(Math.abs(t.getDouble(2)-footprint.w)>1e-8||Math.abs(t.getDouble(5)-footprint.n)>1e-8||Math.abs(t.getDouble(2)+w*t.getDouble(0)-footprint.e)>1e-8||Math.abs(t.getDouble(5)+h*t.getDouble(4)-footprint.s)>1e-8)throw new IllegalArgumentException("inconsistent transform");
            Bounds geometry=rectangle(item.getJSONObject("geometry"));
            if(Math.abs(geometry.w-footprint.w)>1e-6||Math.abs(geometry.s-footprint.s)>1e-6||Math.abs(geometry.e-footprint.e)>1e-6||Math.abs(geometry.n-footprint.n)>1e-6)throw new IllegalArgumentException("Footprint differs from raster");
            coverage=new Bounds(Math.max(geometry.w,footprint.w),Math.max(geometry.s,footprint.s),Math.min(geometry.e,footprint.e),Math.min(geometry.n,footprint.n));
        }
        static Bounds rectangle(JSONObject geometry)throws JSONException{
            JSONArray rings=geometry.getJSONArray("coordinates");String type=geometry.getString("type");
            if("MultiPolygon".equals(type)){if(rings.length()!=1)throw new IllegalArgumentException("Multiple footprints");rings=rings.getJSONArray(0);}else if(!"Polygon".equals(type))throw new IllegalArgumentException("Footprint type");
            if(rings.length()!=1)throw new IllegalArgumentException("Footprint holes");JSONArray ring=rings.getJSONArray(0);if(ring.length()!=5)throw new IllegalArgumentException("Only rectangular footprint supported");
            double w=180,s=90,e=-180,n=-90;for(int i=0;i<4;i++){JSONArray p=ring.getJSONArray(i);if(p.length()!=2)throw new IllegalArgumentException("Footprint dimensions");double x=p.getDouble(0),y=p.getDouble(1);w=Math.min(w,x);e=Math.max(e,x);s=Math.min(s,y);n=Math.max(n,y);}
            Bounds bounds=new Bounds(w,s,e,n);int seen=0;
            for(int i=0;i<4;i++){JSONArray p=ring.getJSONArray(i);double x=p.getDouble(0),y=p.getDouble(1);if(x!=w&&x!=e||y!=s&&y!=n)throw new IllegalArgumentException("Nonrectangular footprint");int corner=(x==e?1:0)+(y==n?2:0);if((seen&(1<<corner))!=0)throw new IllegalArgumentException("Duplicate corner");seen|=1<<corner;JSONArray next=ring.getJSONArray(i+1);if(p.getDouble(0)!=next.getDouble(0)&&p.getDouble(1)!=next.getDouble(1))throw new IllegalArgumentException("Crossed footprint");}
            if(ring.getJSONArray(0).getDouble(0)!=ring.getJSONArray(4).getDouble(0)||ring.getJSONArray(0).getDouble(1)!=ring.getJSONArray(4).getDouble(1))throw new IllegalArgumentException("Open footprint");return bounds;
        }
        ImageryTrace.Source provenance(){return new ImageryTrace.Source(id,producer+" / OpenAerialMap", "CC-BY-4.0",captured);}
        String label(){return title+"\n"+producer+" · "+captured+" · "+gsd+" m · CC-BY-4.0";}
    }
    static String required(String s){if(s==null||s.trim().isEmpty()||s.length()>4096)throw new IllegalArgumentException("metadata");return s;}
    static final class Search {
        final List<Source> sources;final List<OamCoverage> items;final int returned,rejected; final boolean more;
        Search(List<Source>s,List<OamCoverage> items,int total,boolean more){sources=Collections.unmodifiableList(s);this.items=Collections.unmodifiableList(items);returned=total;rejected=total-s.size();this.more=more;}
    }
    Search search(Bounds b,BooleanSupplier cancel)throws Exception{
        byte[] bytes=transport.get(ROOT+"/stac/search?collections=openaerialmap&limit=30&bbox="+b.csv(),JSON_LIMIT,cancel);
        if(bytes.length>JSON_LIMIT)throw new IOException("STAC too large");check(cancel);
        return parseSearch(new JSONObject(new String(bytes,StandardCharsets.UTF_8)),b);
    }
    static Search parseSearch(JSONObject j,Bounds b)throws JSONException{
        JSONArray features=j.getJSONArray("features");if(features.length()>30)throw new JSONException("Too many features");List<Source> list=new ArrayList<>();List<OamCoverage> items=new ArrayList<>();
        for(int i=0;i<features.length();i++){JSONObject raw=features.optJSONObject(i);OamCoverage entry=new OamCoverage(raw==null?new JSONObject():raw);items.add(entry);if(entry.source!=null)list.add(entry.source);}
        boolean more=false;JSONArray links=j.optJSONArray("links");if(links!=null)for(int i=0;i<Math.min(links.length(),100);i++)if("next".equals(links.getJSONObject(i).optString("rel")))more=true;
        return new Search(list,items,features.length(),more);
    }
    static String rasterUrl(Source s,Bounds b){
        b.fragment();if(!s.coverage.contains(b))throw new IllegalArgumentException("Outside footprint");
        return ROOT+"/raster/collections/openaerialmap/items/"+s.id+"/bbox/"+b.csv()+"/512x512.png?assets=visual&coord_crs=EPSG%3A4326&dst_crs=EPSG%3A4326&return_mask=true";
    }
    static final class Raster {
        final Source source;final Bounds bounds;final Bitmap bitmap;final byte[] png;final String request;
        Raster(Source s,Bounds b,Bitmap image,byte[] png,String url){source=s;bounds=b;bitmap=image;this.png=png;request=url;}
    }
    Raster load(Source s,Bounds b,BooleanSupplier cancel)throws Exception{
        String url=rasterUrl(s,b);byte[] png=transport.get(url,PNG_LIMIT,cancel);check(cancel);Bitmap bitmap=decode(png);
        return new Raster(s,b,bitmap,png,url);
    }
    static Bitmap decode(byte[] png)throws IOException{
        if(png.length>PNG_LIMIT||png.length<8||png[0]!=(byte)137||png[1]!=80||png[2]!=78||png[3]!=71)throw new IOException("Expected bounded PNG");
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(png,0,png.length,o);
        if(o.outWidth!=SIDE||o.outHeight!=SIDE)throw new IOException("Unexpected raster dimensions");o.inJustDecodeBounds=false;o.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap b=BitmapFactory.decodeByteArray(png,0,png.length,o);if(b==null)throw new IOException("Invalid PNG");return b;
    }
}
