package ru.main.explorer;
import android.content.Context;
/** Immutable app message and arguments, retained separately from rendered text. */
final class StatusText {
 final String key;final Object[] args;
 StatusText(String key,Object...args){this.key=key;this.args=args.clone();}
 String render(Context context){return String.format(Language.locale(context),Language.text(context,key),args);}
}
