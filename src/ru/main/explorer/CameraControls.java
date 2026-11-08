package ru.main.explorer;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.*;
import android.os.*;
import android.util.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Camera2 metadata only: no model-name assumptions or zoom-triggered lens selection. */
final class CameraControls {
 final CameraActivity owner;
 final ArrayList<Option> options=new ArrayList<>();
 Option selected;
 CameraCharacteristics logical,info;
 List<CaptureRequest.Key<?>> keys=Collections.emptyList(),physicalKeys=Collections.emptyList();
 Button facing,lens,zoom;
 float ratio=1,min=1,max=1;
 boolean ratioApi,physicalCrop;
 Rect activeArray;
 CameraControls(CameraActivity owner){this.owner=owner;}
 void attachPreview(TextureView preview){
  ScaleGestureDetector detector=new ScaleGestureDetector(owner,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
   int token;
   public boolean onScaleBegin(ScaleGestureDetector d){token=owner.cameraGeneration();return zoomEditable();}
   public boolean onScale(ScaleGestureDetector d){return scaleZoom(d.getScaleFactor(),token);}
  });
  preview.setOnTouchListener((v,event)->{detector.onTouchEvent(event);if(event.getActionMasked()==MotionEvent.ACTION_UP)v.performClick();return true;});
 }
 boolean zoomEditable(){return editable()&&zoom!=null&&zoom.isEnabled()&&max>min;}
 static float gestureRatio(float current,float factor,float min,float max){if(!Float.isFinite(factor)||factor<=0)return current;double next=(double)current*factor;return (float)Math.max(min,Math.min(max,next));}
 boolean scaleZoom(float factor,int token){if(!zoomEditable()||token!=owner.cameraGeneration())return false;float next=gestureRatio(ratio,factor,min,max);if(next!=ratio){ratio=next;owner.repeatPreview();owner.refreshControls();}return true;}
 static final class Option {
  final String device,physical;final int facing;final float focal;
  Option(String device,String physical,int facing,float focal){this.device=device;this.physical=physical;this.facing=facing;this.focal=focal;}
  String key(){return device+"/"+(physical==null?"device":physical);}
 }
 void attach(LinearLayout header){
  LinearLayout row=new LinearLayout(owner);header.addView(row);
  facing=button(row,R.string.camera_facing);lens=button(row,R.string.camera_lens);zoom=button(row,R.string.camera_zoom);
  facing.setOnClickListener(v->chooseFacing());lens.setOnClickListener(v->chooseLens());zoom.setOnClickListener(v->chooseZoom());owner.attachPreviewZoom();
 }
 Button button(LinearLayout row,int label){Button b=new Button(owner);b.setText(Language.text(owner,label));b.setContentDescription(Language.text(owner,label));row.addView(b,new LinearLayout.LayoutParams(0,-2,1));return b;}
 static boolean usable(CameraCharacteristics c){StreamConfigurationMap m=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);return m!=null&&m.getOutputSizes(ImageFormat.JPEG)!=null&&m.getOutputSizes(ImageFormat.JPEG).length>0&&m.getOutputSizes(SurfaceTexture.class)!=null&&m.getOutputSizes(SurfaceTexture.class).length>0;}
 static float focal(CameraCharacteristics c){float[] f=c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);return f==null||f.length==0?0:f[0];}
 void discover(CameraManager manager)throws CameraAccessException {
  options.clear();
  String[] ids=manager.getCameraIdList();Arrays.sort(ids);
  for(String id:ids){CameraCharacteristics c=manager.getCameraCharacteristics(id);Integer f=c.get(CameraCharacteristics.LENS_FACING);if(f==null||!usable(c))continue;options.add(new Option(id,null,f,focal(c)));
   if(Build.VERSION.SDK_INT>=28){ArrayList<String> physical=new ArrayList<>(c.getPhysicalCameraIds());Collections.sort(physical);for(String p:physical){try{CameraCharacteristics pc=manager.getCameraCharacteristics(p);Integer pf=pc.get(CameraCharacteristics.LENS_FACING);if(pf!=null&&usable(pc))options.add(new Option(id,p,pf,focal(pc)));}catch(CameraAccessException|IllegalArgumentException ignored){/* OEM does not expose usable metadata. */}}}
  }
 }
 CameraCharacteristics prepare(CameraManager manager)throws CameraAccessException {
  discover(manager);
  if(selected==null){for(Option o:options)if(o.facing==CameraCharacteristics.LENS_FACING_BACK){selected=o;break;}if(selected==null&&!options.isEmpty())selected=options.get(0);}
  if(selected==null)throw new IllegalArgumentException(Language.text(owner,R.string.no_camera_on_this_device));
  boolean found=false;for(Option o:options)if(o.key().equals(selected.key())){selected=o;found=true;break;}
  if(!found)throw new IllegalArgumentException(Language.text(owner,R.string.camera_selection_unavailable));
  logical=manager.getCameraCharacteristics(selected.device);info=selected.physical==null?logical:manager.getCameraCharacteristics(selected.physical);
  keys=logical.getAvailableCaptureRequestKeys();if(keys==null)keys=Collections.emptyList();
  physicalKeys=Build.VERSION.SDK_INT>=28?logical.getAvailablePhysicalCameraRequestKeys():null;if(physicalKeys==null)physicalKeys=Collections.emptyList();
  ratio=1;min=1;max=1;ratioApi=false;physicalCrop=false;activeArray=info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
  if(selected.physical==null&&Build.VERSION.SDK_INT>=30&&keys.contains(CaptureRequest.CONTROL_ZOOM_RATIO)){
   Range<Float> range=info.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);if(range!=null&&validRange(range.getLower(),range.getUpper())){min=range.getLower();max=range.getUpper();ratioApi=true;}
  }
  if(!ratioApi&&activeArray!=null&&keys.contains(CaptureRequest.SCALER_CROP_REGION)){
   Float limit=info.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
   physicalCrop=selected.physical!=null&&physicalKeys.contains(CaptureRequest.SCALER_CROP_REGION);
   if((selected.physical==null||physicalCrop)&&limit!=null&&validRange(1,limit))max=limit;
  }
  ratio=clamp(1,min,max);return info;
 }
 static boolean validRange(float min,float max){return Float.isFinite(min)&&Float.isFinite(max)&&min>0&&min<=1&&max>=1&&max>=min;}
 static float clamp(float value,float min,float max){return Float.isFinite(value)?Math.max(min,Math.min(max,value)):min;}
 static Rect crop(Rect sensor,float ratio){int w=Math.max(1,Math.min(sensor.width(),Math.round(sensor.width()/ratio))),h=Math.max(1,Math.min(sensor.height(),Math.round(sensor.height()/ratio)));int x=sensor.left+(sensor.width()-w)/2,y=sensor.top+(sensor.height()-h)/2;return new Rect(x,y,x+w,y+h);}
 CaptureRequest.Builder request(CameraDevice camera,int template)throws CameraAccessException {
  if(Build.VERSION.SDK_INT>=28&&selected!=null&&selected.physical!=null)return camera.createCaptureRequest(template,Collections.singleton(selected.physical));
  return camera.createCaptureRequest(template);
 }
 void applyZoom(CaptureRequest.Builder request){
  if(ratioApi&&Build.VERSION.SDK_INT>=30)request.set(CaptureRequest.CONTROL_ZOOM_RATIO,clamp(ratio,min,max));
  else if(max>1&&activeArray!=null){Rect crop=crop(activeArray,clamp(ratio,1,max));if(physicalCrop&&Build.VERSION.SDK_INT>=28)request.setPhysicalCameraKey(CaptureRequest.SCALER_CROP_REGION,crop,selected.physical);else request.set(CaptureRequest.SCALER_CROP_REGION,crop);}
 }
 void session(CameraDevice camera,List<Surface> surfaces,CameraCaptureSession.StateCallback callback,Handler handler)throws CameraAccessException {
  if(selected!=null&&selected.physical!=null&&Build.VERSION.SDK_INT>=28){ArrayList<OutputConfiguration> outputs=new ArrayList<>();for(Surface s:surfaces){OutputConfiguration o=new OutputConfiguration(s);o.setPhysicalCameraId(selected.physical);outputs.add(o);}camera.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,outputs,command->handler.post(command),callback));}
  else camera.createCaptureSession(surfaces,callback,handler);
 }
 String label(Option o){String side=Language.text(owner,o.facing==CameraCharacteristics.LENS_FACING_FRONT?R.string.camera_front:R.string.camera_rear);return side+" · "+o.device+(o.physical==null?" · "+Language.text(owner,R.string.camera_device):" / "+o.physical)+String.format(Language.locale(owner)," · %.1f mm",o.focal);}
 boolean editable(){return owner.cameraControlsEditable();}
 void update(boolean ready,boolean busy){if(facing==null)return;boolean enabled=editable();facing.setEnabled(enabled&&!options.isEmpty());lens.setEnabled(enabled&&!options.isEmpty());zoom.setEnabled(ready&&!busy&&max>min);facing.setText(Language.text(owner,selected!=null&&selected.facing==CameraCharacteristics.LENS_FACING_FRONT?R.string.camera_front:R.string.camera_rear));lens.setText(selected==null?Language.text(owner,R.string.camera_lens):label(selected));zoom.setText(max>min?String.format(Language.locale(owner),"%.2f×",ratio):Language.text(owner,R.string.camera_zoom_unavailable));zoom.setContentDescription(Language.text(owner,R.string.camera_zoom)+": "+zoom.getText());lens.setContentDescription(Language.text(owner,R.string.camera_lens)+": "+lens.getText());}
 void chooseFacing(){if(!editable())return;ArrayList<Option> choices=new ArrayList<>();for(Option o:options)if(o.physical==null)choices.add(o);showChoices(choices,R.string.camera_facing);}
 void chooseLens(){if(!editable())return;ArrayList<Option> choices=new ArrayList<>();for(Option o:options)if(selected==null||o.facing==selected.facing)choices.add(o);showChoices(choices,R.string.camera_lens);}
 void showChoices(ArrayList<Option> choices,int title){final int token=owner.cameraGeneration();String[] labels=new String[choices.size()];int current=-1;for(int i=0;i<labels.length;i++){labels[i]=label(choices.get(i));if(selected!=null&&selected.key().equals(choices.get(i).key()))current=i;}new AtlasUi.DialogBuilder(owner).setTitle(Language.text(owner,title)).setSingleChoiceItems(labels,current,(dialog,index)->{if(editable()&&token==owner.cameraGeneration()){Option next=choices.get(index);if(selected==null||!next.key().equals(selected.key())){selected=next;owner.restartCamera();}}dialog.dismiss();}).setNeutralButton(Language.text(owner,R.string.camera_availability),(d,w)->new AtlasUi.DialogBuilder(owner).setMessage(Language.text(owner,R.string.camera_availability_explanation)).setPositiveButton(Language.text(owner,R.string.ok),null).show()).setNegativeButton(Language.text(owner,R.string.cancel),null).show();}
 void chooseZoom(){if(!editable()||max<=min)return;LinearLayout box=new LinearLayout(owner);box.setOrientation(1);box.setPadding(24,16,24,16);TextView value=new TextView(owner);SeekBar slider=new SeekBar(owner);slider.setMax(100);slider.setProgress(Math.round((ratio-min)/(max-min)*100));value.setText(String.format(Language.locale(owner),"%.2f× (%.2f–%.2f)",ratio,min,max));slider.setContentDescription(Language.text(owner,R.string.camera_zoom));box.addView(value);box.addView(slider);final int token=owner.cameraGeneration();slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int p,boolean user){if(user&&editable()&&token==owner.cameraGeneration()){ratio=clamp(min+(max-min)*p/100f,min,max);value.setText(String.format(Language.locale(owner),"%.2f×",ratio));owner.repeatPreview();owner.refreshControls();}}});new AtlasUi.DialogBuilder(owner).setTitle(Language.text(owner,R.string.camera_zoom)).setView(box).setPositiveButton(android.R.string.ok,null).show();}
}
