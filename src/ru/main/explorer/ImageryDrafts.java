package ru.main.explorer;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Independent bounded local beta drafts; never touches walks, photos, OSM or atlas data. */
final class ImageryDrafts {
    static final int MAX_FILES=20, MAX_FILE=4*1024*1024, MAX_VERTICES=8192;
    final File directory;
    ImageryDrafts(Context c){directory=new File(c.getFilesDir(),"imagery-drafts-v1");}
    List<String> list(){String[] files=directory.list((d,n)->n.matches("[a-f0-9-]{36}\\.json"));List<String> ids=new ArrayList<>();if(files!=null)for(String f:files)ids.add(f.substring(0,36));Collections.sort(ids);return ids;}
    String save(OamImagery.Raster raster,ImageryTrace.Candidate candidate)throws Exception{
        if(candidate==null||candidate.vertices.size()<2||candidate.vertices.size()>MAX_VERTICES)throw new IOException("Candidate exceeds draft budget");
        if(list().size()>=MAX_FILES)throw new IOException("Draft limit: 20");
        JSONObject record=record(raster,candidate);byte[] bytes=record.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_FILE)throw new IOException("Draft too large");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create draft directory");
        String id=UUID.randomUUID().toString();AtomicFile file=new AtomicFile(new File(directory,id+".json"));FileOutputStream out=null;
        try{out=file.startWrite();out.write(bytes);file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}return id;
    }
    static JSONObject record(OamImagery.Raster r,ImageryTrace.Candidate c)throws JSONException{
        JSONArray path=new JSONArray(),coordinates=new JSONArray();for(ImageryTrace.Vertex v:c.vertices){path.put(new JSONArray().put(v.x).put(v.y));coordinates.put(new JSONArray().put(v.longitude).put(v.latitude));}
        JSONObject p=new JSONObject().put("beta_candidate",true).put("verified_road",false).put("source_id",r.source.id).put("collection","openaerialmap")
            .put("attribution",c.source.attribution).put("license",c.source.permission).put("license_url","https://creativecommons.org/licenses/by/4.0/").put("captured_at",c.source.capturedAt)
            .put("source_asset",r.source.asset).put("source_metadata",OamImagery.ROOT+"/stac/collections/openaerialmap/items/"+r.source.id)
            .put("raster_request",r.request).put("source_gsd_m",r.source.gsd).put("derived_by","MapNotes RGB path candidate; not a surveyed road")
            .put("output_crs","EPSG:4326").put("bbox",new JSONArray().put(r.bounds.w).put(r.bounds.s).put(r.bounds.e).put(r.bounds.n))
            .put("width",c.width).put("height",c.height).put("start",new JSONArray().put(c.startX).put(c.startY)).put("end",new JSONArray().put(c.endX).put(c.endY))
            .put("sample",new JSONArray().put(c.sampleX).put(c.sampleY)).put("sample_rgb",c.sampleRgb).put("sample_radius",c.sampleRadius).put("cost",c.cost);
        JSONObject feature=new JSONObject().put("type","Feature").put("properties",p).put("geometry",new JSONObject().put("type","LineString").put("coordinates",coordinates));
        return new JSONObject().put("schema",1).put("source",r.source.item).put("png",Base64.encodeToString(r.png,Base64.NO_WRAP)).put("pixels",path).put("feature",feature);
    }
    static final class Draft {final OamImagery.Raster raster;final ImageryTrace.Candidate candidate;final String geojson;Draft(OamImagery.Raster r,ImageryTrace.Candidate c,String json){raster=r;candidate=c;geojson=json;}}
    Draft read(String id)throws Exception{
        if(id==null||!id.matches("[a-f0-9-]{36}"))throw new IOException("Invalid draft ID");
        AtomicFile file=new AtomicFile(new File(directory,id+".json"));
        try(InputStream in=file.openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>MAX_FILE)throw new IOException("Draft too large");out.write(buffer,0,n);}
            return parse(new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8)));
        }
    }
    static Draft parse(JSONObject j)throws Exception{
        if(j.getInt("schema")!=1)throw new IOException("Unknown draft schema");OamImagery.Source s=new OamImagery.Source(j.getJSONObject("source"));
        JSONObject feature=j.getJSONObject("feature"),p=feature.getJSONObject("properties");JSONArray a=p.getJSONArray("bbox");
        OamImagery.Bounds b=new OamImagery.Bounds(a.getDouble(0),a.getDouble(1),a.getDouble(2),a.getDouble(3));String url=OamImagery.rasterUrl(s,b);
        if(!url.equals(p.getString("raster_request"))||!"CC-BY-4.0".equals(p.getString("license"))||!s.id.equals(p.getString("source_id"))||!p.getBoolean("beta_candidate")||p.getBoolean("verified_road"))throw new IOException("Invalid provenance");
        if(!"Feature".equals(feature.getString("type"))||!"LineString".equals(feature.getJSONObject("geometry").getString("type"))||!s.producer.concat(" / OpenAerialMap").equals(p.getString("attribution"))||!s.captured.equals(p.getString("captured_at"))||!s.asset.equals(p.getString("source_asset"))||s.gsd!=p.getDouble("source_gsd_m"))throw new IOException("Inconsistent provenance");
        if(p.getInt("width")!=512||p.getInt("height")!=512)throw new IOException("Invalid size");
        byte[] png=Base64.decode(j.getString("png"),Base64.DEFAULT);OamImagery.Raster raster=new OamImagery.Raster(s,b,OamImagery.decode(png),png,url);
        JSONArray pixels=j.getJSONArray("pixels"),coords=feature.getJSONObject("geometry").getJSONArray("coordinates");
        if(pixels.length()<2||pixels.length()>MAX_VERTICES||pixels.length()!=coords.length())throw new IOException("Invalid geometry");
        List<ImageryTrace.Vertex> vertices=new ArrayList<>();ImageryTrace.GeoReference ref=b.reference();int px=-1,py=-1;
        for(int i=0;i<pixels.length();i++){JSONArray q=pixels.getJSONArray(i);int x=q.getInt(0),y=q.getInt(1);double[] geo=ref.coordinate(x,y,512,512);
            if(i>0&&(Math.abs(x-px)>1||Math.abs(y-py)>1))throw new IOException("Disconnected pixels");px=x;py=y;
            if(Math.abs(coords.getJSONArray(i).getDouble(0)-geo[1])>1e-10||Math.abs(coords.getJSONArray(i).getDouble(1)-geo[0])>1e-10)throw new IOException("Invalid transform");vertices.add(new ImageryTrace.Vertex(x,y,geo));}
        JSONArray start=p.getJSONArray("start"),end=p.getJSONArray("end"),sample=p.getJSONArray("sample");ref.coordinate(sample.getInt(0),sample.getInt(1),512,512);
        if(start.getInt(0)!=vertices.get(0).x||start.getInt(1)!=vertices.get(0).y||end.getInt(0)!=px||end.getInt(1)!=py||p.getInt("sample_radius")!=0||!Double.isFinite(p.getDouble("cost")))throw new IOException("Invalid selection");
        ImageryTrace.Candidate c=new ImageryTrace.Candidate(vertices,s.provenance(),ref,512,512,start.getInt(0),start.getInt(1),px,py,sample.getInt(0),sample.getInt(1),p.getInt("sample_rgb"),0,p.getDouble("cost"));
        return new Draft(raster,c,feature.toString());
    }
}
