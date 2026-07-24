package android.content;
import java.io.File;
public class Context {
 public static final int MODE_PRIVATE=0; public static final String NOTIFICATION_SERVICE="notification"; public static final String CLIPBOARD_SERVICE="clipboard";
 public Context getApplicationContext(){return this;} public File getFilesDir(){return new File(".");} public File getCacheDir(){return new File(".");}
 public SharedPreferences getSharedPreferences(String n,int m){return null;} public Object getSystemService(String n){return null;} public <T> T getSystemService(Class<T> c){return null;}
 public ComponentName startService(Intent i){return null;} public ComponentName startForegroundService(Intent i){return null;}
 public ContentResolver getContentResolver(){return null;} public String getPackageName(){return "";}
 public android.content.res.Resources getResources(){return null;} public android.content.res.AssetManager getAssets(){return null;}
 public int checkSelfPermission(String p){return 0;} public android.content.pm.ApplicationInfo getApplicationInfo(){return null;}
}
