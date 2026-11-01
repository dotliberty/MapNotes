package ru.main.explorer;
import android.content.*;import android.database.*;import android.database.sqlite.*;import java.nio.*;

/** Rebuildable, bounded disk cache; used only by the visibility worker. */
final class RevealCache implements AutoCloseable {
 private final Context context;private SQLiteDatabase db;private int writes;
 RevealCache(Context c){context=c.getApplicationContext();}
 private SQLiteDatabase database(){if(db==null){db=SQLiteDatabase.openOrCreateDatabase(new java.io.File(context.getCacheDir(),"reveal-v1.db"),null);db.execSQL("CREATE TABLE IF NOT EXISTS shapes(k TEXT PRIMARY KEY, v BLOB NOT NULL, used INTEGER NOT NULL)");}return db;}
 void begin(){try{database().beginTransaction();}catch(Exception failure){android.util.Log.w("MapNotes","Visibility cache unavailable");}}
 void end(){if(db!=null&&db.inTransaction()){db.setTransactionSuccessful();db.endTransaction();}}
 double[] read(String key){try(Cursor c=database().rawQuery("SELECT v FROM shapes WHERE k=?",new String[]{key})){if(!c.moveToFirst())return null;byte[] bytes=c.getBlob(0);if(bytes.length%8!=0)return null;double[] shape=new double[bytes.length/8];ByteBuffer.wrap(bytes).asDoubleBuffer().get(shape);return shape;}catch(Exception failure){return null;}}
 void write(String key,double[] shape){try{byte[] bytes=new byte[shape.length*8];ByteBuffer.wrap(bytes).asDoubleBuffer().put(shape);ContentValues v=new ContentValues();v.put("k",key);v.put("v",bytes);v.put("used",System.currentTimeMillis());database().insertWithOnConflict("shapes",null,v,SQLiteDatabase.CONFLICT_REPLACE);if(++writes%128==0)trim();}catch(Exception failure){android.util.Log.w("MapNotes","Visibility cache write failed");}}
 void trim(){if(db==null)return;try(Cursor c=db.rawQuery("SELECT COALESCE(SUM(length(v)),0) FROM shapes",null)){if(c.moveToFirst()&&c.getLong(0)>24L*1024*1024)db.execSQL("DELETE FROM shapes WHERE k IN (SELECT k FROM shapes ORDER BY used ASC LIMIT MAX(128,(SELECT COUNT(*) FROM shapes)/3))");}catch(Exception failure){android.util.Log.w("MapNotes","Visibility cache trim failed");}}
 public void close(){if(db!=null){db.close();db=null;}}
}
