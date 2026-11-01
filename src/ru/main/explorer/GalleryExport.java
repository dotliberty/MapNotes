package ru.main.explorer;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;
import java.util.UUID;

/** Explicit, byte-for-byte export. No changes to the private photo or walks.db. */
public final class GalleryExport {
 private GalleryExport(){}
 public static boolean needsPermission(Context c){return Build.VERSION.SDK_INT<29&&c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED;}
 public static final class Result {public final Uri uri;public final boolean alreadySaved;Result(Uri uri,boolean saved){this.uri=uri;alreadySaved=saved;}}
 private static boolean published(ContentResolver resolver,Uri uri)throws IOException{
  if(Build.VERSION.SDK_INT<29)return true;
  try(Cursor row=resolver.query(MediaStore.setIncludePending(uri),new String[]{MediaStore.Images.Media.IS_PENDING},null,null,null)){
   if(row==null)throw new IOException(Messages.text("could_not_check_gallery_publication"));
   return row.moveToFirst()&&row.getInt(0)==0;
  }
 }
 // Serializes viewers as well as double taps. Stable names recover an insert interrupted before journalling.
 public static synchronized Result save(Context context,Store.Photo photo)throws IOException{
  Context c=context.getApplicationContext();Language.initialize(c);
  if(needsPermission(c))throw new IOException(Messages.text("permission_to_save_to_gallery_is_missing"));
  File source=new File(photo.path);if(!source.isFile()||source.length()==0)throw new IOException(Messages.text("original_photo_unavailable"));
  SharedPreferences prefs=c.getSharedPreferences("gallery-export",0);
  String installation=prefs.getString("installation",null);
  if(installation==null){installation=UUID.randomUUID().toString();if(!prefs.edit().putString("installation",installation).commit())throw new IOException(Messages.text("could_not_save_export_state"));}
  String name="MapNotes-"+installation+"-"+photo.id+".jpg",key="photo-"+photo.id;
  ContentResolver resolver=c.getContentResolver();Uri collection=MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
  Uri repairTarget=null;
  String saved=prefs.getString(key,"");
  if(!saved.isEmpty()){
   // A user may rename/move a completed copy. Its URI, not its old filename, identifies it.
   Uri previous=Uri.parse(saved);
   try(android.os.ParcelFileDescriptor fd=resolver.openFileDescriptor(previous,"r")){
    if(fd!=null)repairTarget=previous;
    if(fd!=null&&fd.getStatSize()==source.length()&&published(resolver,previous))return new Result(previous,true);
   }catch(FileNotFoundException missing){}
   // A repair must not leave an old completed journal across a crash while the row is pending.
   if(!prefs.edit().remove(key).commit())throw new IOException(Messages.text("could_not_prepare_to_save_again"));
  }
  boolean modern=Build.VERSION.SDK_INT>=29;String relative=Environment.DIRECTORY_PICTURES+"/MapNotes/";
  File legacy=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"MapNotes/"+name);
  String selection=modern?MediaStore.Images.Media.DISPLAY_NAME+"=? AND "+MediaStore.Images.Media.RELATIVE_PATH+"=?":MediaStore.Images.Media.DATA+"=?";
  String[] args=modern?new String[]{name,relative}:new String[]{legacy.getAbsolutePath()};
  Uri target=repairTarget;
  if(target==null)try(Cursor row=resolver.query(modern?MediaStore.setIncludePending(collection):collection,new String[]{"_id"},selection,args,null)){
   if(row==null)throw new IOException(Messages.text("gallery_unavailable"));
   if(row.moveToFirst())target=ContentUris.withAppendedId(collection,row.getLong(0));
  }

  if(!modern&&!legacy.getParentFile().isDirectory()&&!legacy.getParentFile().mkdirs())throw new IOException(Messages.text("could_not_create_pictures_mapnotes"));
  if(target==null){
   ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,name);values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");values.put(MediaStore.Images.Media.DATE_TAKEN,photo.created);
   if(modern){values.put(MediaStore.Images.Media.RELATIVE_PATH,relative);values.put(MediaStore.Images.Media.IS_PENDING,1);}else values.put(MediaStore.Images.Media.DATA,legacy.getAbsolutePath());
   target=resolver.insert(collection,values);if(target==null)throw new IOException(Messages.text("gallery_did_not_create_an_entry"));
  }else if(modern){ContentValues pending=new ContentValues();pending.put(MediaStore.Images.Media.IS_PENDING,1);if(resolver.update(target,pending,null,null)!=1)throw new IOException(Messages.text("could_not_prepare_export"));}
  try{
   try(InputStream in=new FileInputStream(source);OutputStream out=resolver.openOutputStream(target,"wt")){
    if(out==null)throw new IOException(Messages.text("gallery_did_not_open_the_file"));byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);out.flush();
   }
   try(android.os.ParcelFileDescriptor fd=resolver.openFileDescriptor(target,"r")){
    if(fd==null||fd.getStatSize()!=source.length())throw new IOException(Messages.text("gallery_copy_size_does_not_match_the_original"));
   }
   if(modern){ContentValues ready=new ContentValues();ready.put(MediaStore.Images.Media.IS_PENDING,0);if(resolver.update(target,ready,null,null)!=1||!published(resolver,target))throw new IOException(Messages.text("gallery_did_not_publish_the_photo"));}
   else android.media.MediaScannerConnection.scanFile(c,new String[]{legacy.getAbsolutePath()},new String[]{"image/jpeg"},null);
   if(!prefs.edit().putString(key,target.toString()).commit())throw new IOException(Messages.text("could_not_save_export_state_try_saving_again"));
   return new Result(target,false);
  }catch(IOException|RuntimeException error){
   // Keep an undeletable row discoverable by stable name; retry never intentionally inserts a duplicate.
   try{resolver.delete(target,null,null);}catch(RuntimeException ignored){}
   throw error;
  }
 }
}
