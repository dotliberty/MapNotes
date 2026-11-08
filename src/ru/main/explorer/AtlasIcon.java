package ru.main.explorer;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.widget.Button;
/** Original vector geometry, never font glyphs. */
final class AtlasIcon extends Drawable {
 static final int BACK=0,FLIP=1,LENS=2,LIGHT=3,SIZE=4,PHOTO=5;
 final int kind;int fixedSize;final Paint p=new Paint(3);
 AtlasIcon(int kind){this.kind=kind;}
 public void draw(Canvas c){Rect b=getBounds();float w=fixedSize>0?fixedSize:b.width(),h=fixedSize>0?fixedSize:b.height();c.save();c.translate(b.exactCenterX()-w/2,b.exactCenterY()-h/2);c.scale(w/24f,h/24f);p.setColor(isEnabled()?AtlasUi.CREAM:0xff888b90);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
 if(kind==BACK){line(c,19,12,5,12);line(c,5,12,11,6);line(c,5,12,11,18);}
 else if(kind==LIGHT){Path q=new Path();q.moveTo(14,2);q.lineTo(5,14);q.lineTo(11,14);q.lineTo(10,22);q.lineTo(19,10);q.lineTo(13,10);q.close();c.drawPath(q,p);if(!isSelected())line(c,3,3,21,21);}
 else if(kind==LENS){c.drawCircle(12,12,9,p);c.drawCircle(12,12,5,p);for(int i=0;i<6;i++){c.save();c.rotate(i*60,12,12);line(c,12,3,16,9);c.restore();}}
 else if(kind==SIZE){c.drawRoundRect(3,5,21,19,2,2,p);line(c,6,9,6,15);line(c,18,9,18,15);line(c,9,8,15,8);line(c,9,16,15,16);}
 else {c.drawRoundRect(3,6,21,20,2,2,p);line(c,7,6,9,3);line(c,9,3,15,3);line(c,15,3,17,6);if(kind==FLIP){c.drawArc(7,9,17,18,35,270,false,p);line(c,17,12,17,9);line(c,17,9,14,10);}else c.drawCircle(12,13,4,p);}
 c.restore();}
 void line(Canvas c,float a,float b,float d,float e){c.drawLine(a,b,d,e,p);}
 boolean isEnabled(){for(int s:getState())if(s==android.R.attr.state_enabled)return true;return false;}
 boolean isSelected(){for(int s:getState())if(s==android.R.attr.state_selected)return true;return false;}
 public boolean isStateful(){return true;}protected boolean onStateChange(int[] s){invalidateSelf();return true;}
 public void setAlpha(int a){p.setAlpha(a);}public void setColorFilter(ColorFilter f){p.setColorFilter(f);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
 static void bind(Button b,int kind,CharSequence description,boolean text){AtlasIcon icon=new AtlasIcon(kind);int n=AtlasUi.dp(b,24);icon.setBounds(0,0,n,n);b.setCompoundDrawables(text?icon:null,null,null,null);icon.fixedSize=n;b.setForeground(text?null:icon);b.setCompoundDrawablePadding(AtlasUi.dp(b,6));if(!text)b.setText("");b.setContentDescription(description);b.setTooltipText(description);b.setMinimumHeight(AtlasUi.dp(b,48));b.setMinHeight(AtlasUi.dp(b,48));}
}
