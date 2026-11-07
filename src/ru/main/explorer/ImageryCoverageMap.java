package ru.main.explorer;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import java.util.*;

/** Real MapView projection and local basemap; no GPS, tile or discovery fetching. */
final class ImageryCoverageMap extends MapView {
    interface Listener {void inspect(List<OamCoverage> items);void moved();}
    Listener listener;
    List<OamCoverage> items=Collections.emptyList();
    OamCoverage selected;
    OamImagery.Bounds searched;
    final Paint overlay=new Paint(3);
    ImageryCoverageMap(Context c){super(c,true);follow=false;online=false;illustrated=true;setTag("imagery_coverage_map");}
    double latitude(float py){return Math.toDegrees(Math.atan(Math.sinh(Math.PI*(1-(cy+(py-getHeight()/2.0)/(1<<zoom))/128))));}
    double longitude(float px){return (cx+(px-getWidth()/2.0)/(1<<zoom))/256*360-180;}
    OamImagery.Bounds viewport(){if(getWidth()<1||getHeight()<1)throw new IllegalArgumentException("Map not laid out");return new OamImagery.Bounds(longitude(0),latitude(getHeight()),longitude(getWidth()),latitude(0));}
    OamImagery.Bounds fragment(){double lat=centerLatitude(),lon=centerLongitude(),dy=64/111320.0,dx=dy/Math.cos(Math.toRadians(lat));OamImagery.Bounds b=new OamImagery.Bounds(lon-dx,lat-dy,lon+dx,lat+dy);b.fragment();return b;}
    float sy(double lat){return (float)((y(lat)-cy)*(1<<zoom)+getHeight()/2.0);}
    Path path(List<double[]> rings){Path path=new Path();path.setFillType(Path.FillType.EVEN_ODD);for(double[] ring:rings){for(int i=0;i<ring.length;i+=2){float x=screenX(ring[i]),y=sy(ring[i+1]);if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();}return path;}
    @Override protected void onDraw(Canvas c){drawMap(c,true);overlay.setStrokeWidth(dp(2));
        for(OamCoverage item:items)for(List<double[]> polygon:item.polygons){Path p=path(polygon);int color=item.source!=null?0xff3bd981:0xfff38e72;overlay.setStyle(Paint.Style.FILL);overlay.setColor((color&0xffffff)|0x30000000);c.drawPath(p,overlay);overlay.setStyle(Paint.Style.STROKE);overlay.setColor(item==selected?Color.YELLOW:color);c.drawPath(p,overlay);}
        if(searched!=null){overlay.setStyle(Paint.Style.STROKE);overlay.setColor(0xff64ccff);c.drawRect(screenX(searched.w),sy(searched.n),screenX(searched.e),sy(searched.s),overlay);}
        try{OamImagery.Bounds f=fragment();overlay.setStyle(Paint.Style.STROKE);overlay.setColor(Color.WHITE);c.drawRect(screenX(f.w),sy(f.n),screenX(f.e),sy(f.s),overlay);}catch(IllegalArgumentException ignored){}
        overlay.setColor(Color.WHITE);c.drawLine(getWidth()/2f-dp(8),getHeight()/2f,getWidth()/2f+dp(8),getHeight()/2f,overlay);c.drawLine(getWidth()/2f,getHeight()/2f-dp(8),getWidth()/2f,getHeight()/2f+dp(8),overlay);
        drawScale(c);drawCompass(c);overlay.setStyle(Paint.Style.FILL);overlay.setColor(0xee12201f);c.drawRect(0,getHeight()-dp(38),getWidth(),getHeight(),overlay);overlay.setColor(Color.WHITE);overlay.setTextSize(dp(10));c.drawText(Language.text(getContext(),"imagery_local_basemap"),dp(8),getHeight()-dp(23),overlay);c.drawText(Language.text(getContext(),"imagery_footprint_attribution"),dp(8),getHeight()-dp(8),overlay);
    }
    @Override boolean tapPhotos(float x,float y){List<OamCoverage> hits=new ArrayList<>();Region clip=new Region(0,0,getWidth(),getHeight());for(OamCoverage item:items)for(List<double[]> polygon:item.polygons){Region r=new Region();r.setPath(path(polygon),clip);if(r.contains((int)x,(int)y)){hits.add(item);break;}}if(!hits.isEmpty()&&listener!=null)listener.inspect(hits);return true;}
    @Override public boolean onTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN&&listener!=null)listener.moved();return super.onTouchEvent(e);}
}
