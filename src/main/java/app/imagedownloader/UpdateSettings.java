package app.imagedownloader;

import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.*;
import java.security.KeyStore;
import java.util.concurrent.*;

public final class UpdateSettings extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status;
    private Button check,install;
    private ReleaseClient.Release latest;
    private File pending;
    private long pendingCode;
    private String pendingName;
    private boolean waitingPermission;
    private int dp(int x){return (int)(getResources().getDisplayMetrics().density*x+0.5f);}
    private void post(Runnable r){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())r.run();});}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        ScrollView scroll=new ScrollView(this);
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20),dp(20),dp(20),dp(20));scroll.addView(panel);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        Button back=new Button(this);back.setText("Galeriye dön");back.setOnClickListener(v->finish());panel.addView(back);
        TextView title=new TextView(this);title.setText("Ayarlar");title.setTextSize(26);panel.addView(title);
        TextView version=new TextView(this);
        try{version.setText("Yüklü sürüm  "+getPackageManager().getPackageInfo(getPackageName(),0).versionName);}catch(Exception e){version.setText("Sürüm okunamadı.");}
        panel.addView(version);
        TextView label=new TextView(this);label.setPadding(0,dp(20),0,dp(12));
        label.setText("Güncellemeler GitHub Releases üzerinden indirilir. Erişim anahtarı gerekmez.");panel.addView(label);
        check=new Button(this);check.setText("Güncellemeleri kontrol et");panel.addView(check);
        install=new Button(this);install.setText("İndir ve kur");panel.addView(install);install.setVisibility(View.GONE);
        status=new TextView(this);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);panel.addView(status);
        check.setOnClickListener(v->check());install.setOnClickListener(v->download());
        clearLegacyCredential();
        if(state!=null && state.getBoolean("waiting")){
            pendingCode=state.getLong("code");pendingName=state.getString("version","");
            pending=new File(new File(getCacheDir(),"updates"),"update-"+pendingCode+".apk");waitingPermission=true;
        }
    }
    private void clearLegacyCredential(){
        if(!getPreferences(0).contains("credential"))return;
        getPreferences(0).edit().remove("credential").apply();
        try{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);ks.deleteEntry("release-read-token");}
        catch(Exception ignored){}
    }
    private void busy(boolean value){check.setEnabled(!value);install.setEnabled(!value);}
    private void fail(Exception e){post(()->{busy(false);status.setText(e.getLocalizedMessage()==null?"İşlem tamamlanamadı.":e.getLocalizedMessage());});}
    private void check(){
        latest=null;pending=null;install.setVisibility(View.GONE);
        busy(true);status.setText("GitHub Releases kontrol ediliyor…");
        worker.execute(()->{try{
            ReleaseClient.Release found=new ReleaseClient().latest();
            long installed=getPackageManager().getPackageInfo(getPackageName(),0).getLongVersionCode();
            post(()->{busy(false);latest=found;
                if(found.code>installed){status.setText("Yeni sürüm  "+found.name);install.setVisibility(View.VISIBLE);}
                else status.setText("Uygulama güncel.");
            });
        }catch(Exception e){fail(e);}});
    }
    private void download(){
        if(latest==null)return;
        ReleaseClient.Release release=latest;
        busy(true);status.setText("APK indiriliyor…");
        worker.execute(()->{try{
            File file=new ReleaseClient().download(this,release,pc->post(()->status.setText("APK indiriliyor  %"+pc)));
            post(()->{pending=file;pendingCode=release.code;pendingName=release.name;busy(false);launchInstaller();});
        }catch(Exception e){fail(e);}});
    }
    private void launchInstaller(){
        try{
            ReleaseClient.validate(this,pending,pendingCode,pendingName);
            if(!getPackageManager().canRequestPackageInstalls()){
                waitingPermission=true;status.setText("Bu kaynaktan kuruluma izin verip uygulamaya dönün.");
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())),7);
                return;
            }
            Uri uri=FileProvider.getUriForFile(this,getPackageName()+".updates",pending);
            Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("APK",uri));
            startActivity(intent);status.setText("Android kurulum ekranında Güncelle’yi onaylayın.");
        }catch(Exception e){waitingPermission=false;fail(e);}
    }
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putBoolean("waiting",waitingPermission);b.putLong("code",pendingCode);b.putString("version",pendingName);}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==7 && waitingPermission){waitingPermission=false;
            if(getPackageManager().canRequestPackageInstalls())launchInstaller();
            else status.setText("Kurulum izni verilmedi.");
        }
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
