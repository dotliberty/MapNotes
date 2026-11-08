package ru.main.explorer;
import android.graphics.Typeface;import android.graphics.Color;import android.graphics.drawable.GradientDrawable;import android.content.res.ColorStateList;import android.view.*;import android.widget.*;
/** Shared atlas palette; native accessible controls retain their semantics. */
final class AtlasUi {
 static final int INK=0xff30394b,GOLD=0xffc9b58a,CREAM=0xfff3eddf,SURFACE=0xff3b4558,MUTED=0xffc6c1b5;
 static void styleButton(Button b,boolean primary){b.setAllCaps(false);b.setTextColor(primary?INK:CREAM);b.setTextSize(14);b.setTypeface(Typeface.create("sans-serif-medium",0));b.setMinHeight(dp(b,48));b.setMinimumHeight(dp(b,48));b.setBackgroundTintList(null);b.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33ffffff),panel(primary?CREAM:SURFACE,primary?GOLD:0xff657084,dp(b,24)),null));b.setPadding(dp(b,14),dp(b,6),dp(b,14),dp(b,6));}
 static void enter(View v){if(android.animation.ValueAnimator.areAnimatorsEnabled()){v.setAlpha(0);v.setTranslationY(dp(v,8));v.animate().alpha(1).translationY(0).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}}

 static android.app.AlertDialog show(android.app.AlertDialog dialog){dialog.show();if(dialog.getWindow()!=null){dialog.getWindow().setBackgroundDrawable(panel(INK,GOLD,dp(dialog.getWindow().getDecorView(),20)));View decor=dialog.getWindow().getDecorView();apply(decor);int titleId=decor.getResources().getIdentifier("alertTitle","id","android");View heading=decor.findViewById(titleId);if(heading instanceof TextView){TextView title=(TextView)heading;title.setTypeface(Typeface.create("serif",0));title.setTextSize(22);title.setTextColor(GOLD);}ListView list=dialog.getListView();if(list!=null){list.setDivider(new android.graphics.drawable.ColorDrawable(0x30657084));list.setDividerHeight(dp(list,1));list.setSelector(new android.graphics.drawable.ColorDrawable(0x33657084));}enter(decor);}return dialog;}
 static final class DialogBuilder extends android.app.AlertDialog.Builder {
  DialogBuilder(android.content.Context context){super(context,android.R.style.Theme_Material_Dialog_Alert);}
  @Override public android.app.AlertDialog show(){return AtlasUi.show(create());}
 }
 static int dp(View v,int n){return Math.round(n*v.getResources().getDisplayMetrics().density);}
 static GradientDrawable panel(int color,int stroke,float radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(radius);g.setStroke(2,stroke);return g;}
 static void apply(View v){
  if(v instanceof Button){Button b=(Button)v;b.setAllCaps(false);b.setTextColor(CREAM);b.setTextSize(16);b.setTypeface(Typeface.create("sans-serif-medium",0));b.setMinHeight(dp(b,48));b.setBackgroundTintList(null);b.setBackground(panel(INK,GOLD,dp(b,18)));b.setPadding(dp(b,16),dp(b,10),dp(b,16),dp(b,10));if(b.getLayoutParams() instanceof LinearLayout.LayoutParams){LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)b.getLayoutParams();p.setMargins(dp(b,6),dp(b,5),dp(b,6),dp(b,5));b.setLayoutParams(p);}}
  else if(v instanceof EditText){EditText e=(EditText)v;e.setTextColor(INK);e.setHintTextColor(0xff687d72);e.setBackground(panel(CREAM,GOLD,dp(e,12)));e.setPadding(dp(e,14),dp(e,10),dp(e,14),dp(e,10));}
  else if(v instanceof TextView){TextView t=(TextView)v;t.setTextColor(CREAM);t.setTypeface(Typeface.create(t.getTextSize()/t.getResources().getDisplayMetrics().scaledDensity>=22?"serif":"sans-serif",0));t.setLineSpacing(dp(t,2),1);}
  if(v instanceof Button){TextView text=(TextView)v;text.setMaxLines(2);if(android.os.Build.VERSION.SDK_INT>=26)text.setAutoSizeTextTypeUniformWithConfiguration(11,16,1,android.util.TypedValue.COMPLEX_UNIT_SP);}
  if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)apply(g.getChildAt(i));}
 }
}
