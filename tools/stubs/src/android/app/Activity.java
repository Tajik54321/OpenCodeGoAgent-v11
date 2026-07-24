package android.app; import android.content.*; import android.os.*; import android.view.*; public class Activity extends Context {
 public static final int RESULT_OK=-1; protected void onCreate(Bundle b){} protected void onDestroy(){} protected void onActivityResult(int r,int c,Intent d){} protected void onResume(){} protected void onPause(){} public void onBackPressed(){}
 public void setContentView(View v){} public android.view.Window getWindow(){return null;} public Intent getIntent(){return null;} public void finish(){} public void runOnUiThread(Runnable r){} public void startActivity(Intent i){} public void startActivityForResult(Intent i,int r){} public void requestPermissions(String[] p,int r){}
}
