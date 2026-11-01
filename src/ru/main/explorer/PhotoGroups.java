package ru.main.explorer;
import java.util.*;
/** Screen-space connected components: even a chain of overlapping markers stays selectable. */
final class PhotoGroups {
 static final class Group {float x,y,anchorX,anchorY;final ArrayList<Store.Photo> photos=new ArrayList<>();Group(float x,float y){this.x=x;this.y=y;anchorX=x;anchorY=y;}}
 /** One callout per photo. Exact GPS anchor is never moved by screen layout. */
 static ArrayList<Group> spread(ArrayList<Store.Photo> photos,float[] xs,float[] ys,float size,int width,int height){
  ArrayList<Group> result=new ArrayList<>();float gap=size+8;HashSet<Long> occupied=new HashSet<>();
  for(int i=0;i<photos.size();i++){
   if(xs[i]<0||xs[i]>width||ys[i]<0||ys[i]>height)continue;
   Group g=new Group(xs[i],ys[i]);g.photos.add(photos.get(i));boolean placed=false;
   if(result.size()>=48)break;
   for(int ring=1;ring<=8&&!placed;ring++)for(int side=0;side<8&&!placed;side++){
    double angle=-Math.PI/2+side*Math.PI/4;float x=xs[i]+(float)Math.cos(angle)*ring*gap,y=ys[i]+(float)Math.sin(angle)*ring*gap;
    if(x<size/2||x>width-size/2||y<size/2||y>height-size/2)continue;
    int cellX=(int)Math.floor(x/gap),cellY=(int)Math.floor(y/gap);boolean free=true;for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)if(occupied.contains(((long)(cellX+dx)<<32)^((cellY+dy)&0xffffffffL)))free=false;
    if(free){g.x=x;g.y=y;placed=true;occupied.add(((long)cellX<<32)^(cellY&0xffffffffL));}
   }
   // Dense views can exhaust space. Keep every photo individually selectable, never collapse it.
   if(!placed)continue; // No overlapping fallback; remaining photos stay in the gallery.
   result.add(g);
  }return result;
 }
 static ArrayList<Group> build(ArrayList<Store.Photo> photos,float[] xs,float[] ys,float size){
  int n=photos.size();int[] parent=new int[n];HashMap<String,ArrayList<Integer>> cells=new HashMap<>();
  for(int i=0;i<n;i++){parent[i]=i;int gx=(int)Math.floor(xs[i]/size),gy=(int)Math.floor(ys[i]/size);
   for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){ArrayList<Integer> near=cells.get((gx+dx)+":"+(gy+dy));if(near!=null)for(int j:near)if(Math.abs(xs[i]-xs[j])<=size&&Math.abs(ys[i]-ys[j])<=size){int a=root(parent,i),b=root(parent,j);parent[Math.max(a,b)]=Math.min(a,b);}}
   String key=gx+":"+gy;ArrayList<Integer> bucket=cells.get(key);if(bucket==null){bucket=new ArrayList<>();cells.put(key,bucket);}bucket.add(i);
  }
  LinkedHashMap<Integer,Group> result=new LinkedHashMap<>();
  for(int i=0;i<n;i++){int r=root(parent,i);Group g=result.get(r);if(g==null){g=new Group(xs[r],ys[r]);result.put(r,g);}g.photos.add(photos.get(i));}
  return new ArrayList<>(result.values());
 }
 private static int root(int[] p,int i){while(i!=p[i]){p[i]=p[p[i]];i=p[i];}return i;}
}