package android.content; import android.net.Uri; public class Intent {
 public static final String ACTION_OPEN_DOCUMENT="", ACTION_CREATE_DOCUMENT="", ACTION_VIEW=""; public static final String CATEGORY_OPENABLE=""; public static final String EXTRA_TITLE="";
 public Intent(){} public Intent(String a){} public Intent(String a,Uri u){} public Intent(Context c,Class<?> k){}
 public Intent setAction(String a){return this;} public String getAction(){return null;} public Intent putExtra(String k,String v){return this;} public Intent putExtra(String k,int v){return this;} public Intent putExtra(String k,boolean v){return this;} public Intent putExtra(String k,long v){return this;}
 public String getStringExtra(String k){return null;} public int getIntExtra(String k,int d){return d;} public boolean getBooleanExtra(String k,boolean d){return d;} public long getLongExtra(String k,long d){return d;}
 public Intent setType(String t){return this;} public Intent addCategory(String c){return this;} public Intent setData(Uri u){return this;} public Uri getData(){return null;}
}
