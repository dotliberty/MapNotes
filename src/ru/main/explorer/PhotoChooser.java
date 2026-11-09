package ru.main.explorer;
import android.app.*;import android.content.*;import android.graphics.*;import android.os.*;import android.view.*;import android.widget.*;import java.util.*;import java.util.concurrent.*;
/** Recycled thumbnail list; kept open behind the viewer so Back preserves position. */
final class PhotoChooser {
 static void show(MainActivity owner,ArrayList<Store.Photo> photos){show(owner,photos,null);}static void show(MainActivity owner,ArrayList<Store.Photo> photos,View anchor){
  LinearLayout body=new LinearLayout(owner);body.setOrientation(1);int pad=AtlasUi.dp(body,12);body.setPadding(pad,pad,pad,pad);
  TextView title=new TextView(owner);title.setText(Language.text(owner,R.string.photos)+photos.size());title.setTextSize(24);body.addView(title);
  ListView list=new ListView(owner);list.setDividerHeight(pad/2);body.addView(list,new LinearLayout.LayoutParams(-1,Math.min(AtlasUi.dp(body,440),owner.getResources().getDisplayMetrics().heightPixels/2)));
  Button close=new Button(owner);close.setText(Language.text(owner,R.string.return_to_map));body.addView(close);
  AlertDialog dialog=new AtlasUi.DialogBuilder(owner).setView(body).create();
  ExecutorService worker=Executors.newSingleThreadExecutor();Handler ui=new Handler(Looper.getMainLooper());android.util.LruCache<Long,Bitmap> cache=new android.util.LruCache<Long,Bitmap>(4*1024*1024){protected int sizeOf(Long key,Bitmap b){return b.getByteCount();}};Set<Long> pending=new HashSet<>();boolean[] closed={false};
  BaseAdapter adapter=new BaseAdapter(){
   public int getCount(){return photos.size();}public Object getItem(int i){return photos.get(i);}public long getItemId(int i){return photos.get(i).id;}public boolean hasStableIds(){return true;}
   public View getView(int i,View recycled,android.view.ViewGroup parent){Store.Photo photo=photos.get(i);LinearLayout row;
    if(recycled==null){row=new LinearLayout(owner);row.setGravity(Gravity.CENTER_VERTICAL);ImageView thumb=new ImageView(owner);thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);thumb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(thumb,new LinearLayout.LayoutParams(AtlasUi.dp(row,80),AtlasUi.dp(row,80)));Button label=new Button(owner);label.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);row.addView(label,new LinearLayout.LayoutParams(0,-2,1));AtlasUi.apply(row);}else row=(LinearLayout)recycled;
    ImageView thumb=(ImageView)row.getChildAt(0);Button label=(Button)row.getChildAt(1);label.setText(Language.text(owner,R.string.photo)+photo.id+" · "+Language.date(owner,photo.created)+(photo.note.isEmpty()?"":"\n"+photo.note));label.setMaxLines(3);label.setOnClickListener(v->owner.startActivity(new Intent(owner,PhotoActivity.class).putExtra("id",photo.id)));thumb.setOnClickListener(v->label.performClick());thumb.setImageDrawable(new AtlasIcon(AtlasIcon.PHOTO));Bitmap bitmap=cache.get(photo.id);if(bitmap!=null)thumb.setImageBitmap(bitmap);else if(pending.add(photo.id)&&!closed[0])worker.execute(()->{Bitmap loaded=PhotoFiles.load(photo.path,160);ui.post(()->{if(closed[0])return;if(loaded!=null){pending.remove(photo.id);cache.put(photo.id,loaded);notifyDataSetChanged();}});});return row;
   }
  };list.setAdapter(adapter);close.setOnClickListener(v->dialog.dismiss());dialog.setOnDismissListener(d->{closed[0]=true;worker.shutdownNow();cache.evictAll();});AtlasUi.show(dialog);if(anchor!=null)AtlasUi.anchorDialog(dialog,anchor);
 }
}
