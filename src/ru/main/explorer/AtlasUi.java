package ru.main.explorer;
import android.graphics.Typeface;import android.graphics.Color;import android.graphics.drawable.GradientDrawable;import android.content.res.ColorStateList;import android.view.*;import android.widget.*;
/** Shared atlas palette; native accessible controls retain their semantics. */
final class AtlasUi {
 static final int INK=0xff193a3b,GOLD=0xffc9a869,CREAM=0xfff3e8ce;
 static int dp(View v,int n){return Math.round(n*v.getResources().getDisplayMetrics().density);}
 static GradientDrawable panel(int color,int stroke,float radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(radius);g.setStroke(2,stroke);return g;}
 static void apply(View v){
  if(v instanceof Button){Button b=(Button)v;b.setAllCaps(false);b.setTextColor(CREAM);b.setTextSize(16);b.setTypeface(Typeface.create("sans-serif-medium",0));b.setMinHeight(dp(b,48));b.setBackgroundTintList(null);b.setBackground(panel(INK,GOLD,dp(b,18)));b.setPadding(dp(b,16),dp(b,10),dp(b,16),dp(b,10));if(b.getLayoutParams() instanceof LinearLayout.LayoutParams){LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)b.getLayoutParams();p.setMargins(dp(b,6),dp(b,5),dp(b,6),dp(b,5));b.setLayoutParams(p);}}
  else if(v instanceof EditText){EditText e=(EditText)v;e.setTextColor(INK);e.setHintTextColor(0xff687d72);e.setBackground(panel(CREAM,GOLD,dp(e,12)));e.setPadding(dp(e,14),dp(e,10),dp(e,14),dp(e,10));}
  else if(v instanceof TextView){TextView t=(TextView)v;t.setTextColor(CREAM);t.setTypeface(Typeface.create("sans-serif",0));}
  if(v instanceof Button){TextView text=(TextView)v;text.setMaxLines(2);if(android.os.Build.VERSION.SDK_INT>=26)text.setAutoSizeTextTypeUniformWithConfiguration(11,16,1,android.util.TypedValue.COMPLEX_UNIT_SP);}
  if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)apply(g.getChildAt(i));}
 }
}
