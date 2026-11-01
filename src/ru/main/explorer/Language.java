package ru.main.explorer;
import android.app.*;import android.content.*;import android.content.res.Configuration;import android.os.LocaleList;import java.util.Locale;
/** Resource-based language selection. Stored user content is never translated. */
final class Language {
 static final String PREFS="mapnotes_language";
 static String selected(Context context){String value=context.getSharedPreferences(PREFS,0).getString("language","system");return "ru".equals(value)||"en".equals(value)?value:"system";}
 static void select(Context context,String language){if(!"system".equals(language)&&!"en".equals(language)&&!"ru".equals(language))throw new IllegalArgumentException("Unsupported app language");initialize(context);context.getSharedPreferences(PREFS,0).edit().putString("language",language).apply();}
 static void initialize(Context context){Context app=context.getApplicationContext();final Context source=app==null?context:app;Messages.install(new Messages.Resolver(){public String resolve(String key){return text(source,key);}public Locale locale(){return Language.locale(source);}});}
 private static Context resources(Context base){String choice=selected(base);if(choice.equals("system"))return base;Configuration config=new Configuration(base.getResources().getConfiguration());Locale locale=Locale.forLanguageTag(choice);config.setLocales(new LocaleList(locale));config.setLayoutDirection(locale);return base.createConfigurationContext(config);}
 static Context wrap(Context base){initialize(base);return resources(base);}
 static Locale locale(Context context){Locale locale=resources(context).getResources().getConfiguration().getLocales().get(0);return "ru".equals(locale.getLanguage())?Locale.forLanguageTag("ru"):Locale.ENGLISH;}
 static String text(Context context,int id){return resources(context).getString(id);}
 static String text(Context context,String key){int id=context.getResources().getIdentifier(key,"string",context.getPackageName());if(id==0)throw new IllegalArgumentException("Missing localized string: "+key);return text(context,id);}
 static String format(Context context,int id,Object...args){return String.format(locale(context),text(context,id),args);}
 static String date(Context context,long millis){return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT,locale(context)).format(new java.util.Date(millis));}
 static String version(Context context){try{return context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}catch(android.content.pm.PackageManager.NameNotFoundException unavailable){return "";}}
 // Compatibility for the original navigation API only; never use for user content.
 static final String[] KEYS={"atlas_title","walk_hint","take_photo","my_photos","network_on","network_off","start_walk","finish_walk","resume_walk","walk_history","start_hint","explore_hint","download_area","resume_download","downloading","offline_hint","map_menu","connect_map","download_link","disable_layer","enable_layer","satellite_layer","atlas_layer","about","crash_report","map_sources","disconnect_map"};
 static String label(Context context,String value){Configuration ru=new Configuration(context.getResources().getConfiguration());ru.setLocales(new LocaleList(Locale.forLanguageTag("ru")));Context source=context.createConfigurationContext(ru);for(String key:KEYS){int id=source.getResources().getIdentifier(key,"string",source.getPackageName());if(source.getString(id).equals(value))return text(context,id);}return value;}
 static void show(Activity activity){String[] codes={"system","ru","en"};String[] labels={text(activity,R.string.language_system),text(activity,R.string.language_ru),text(activity,R.string.language_en)};int chosen=java.util.Arrays.asList(codes).indexOf(selected(activity));new AlertDialog.Builder(activity).setTitle(text(activity,R.string.language)).setSingleChoiceItems(labels,chosen,(dialog,index)->{select(activity,codes[index]);dialog.dismiss();activity.recreate();}).setNegativeButton(text(activity,R.string.cancel),null).show();}
}
