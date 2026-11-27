package app.imagedownloader;

import android.app.Activity;
import android.app.DownloadManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.*;
import android.content.res.Configuration;
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
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable tick=()->refreshDownload();
    private TextView status,title,version,label;
    private LinearLayout panel;
    private Button check,install,cancel;
    private UpdateDownload downloads;
    private ReleaseClient.Release latest;
    private ReleaseClient.Patch patch;
    private File pending;
    private long verifiedId=-1;
    private boolean resumed,checking,verifying,waitingPermission,autoInstall;
    private int dp(int x){return (int)(getResources().getDisplayMetrics().density*x+0.5f);}
    private void post(Runnable r){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())r.run();});}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        downloads=new UpdateDownload(this);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(panel);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});
        Button back=new Button(this);back.setText("Galeriye dön");back.setOnClickListener(v->finish());panel.addView(back);
        title=new TextView(this);title.setText("Ayarlar");panel.addView(title);
        version=new TextView(this);
        try{version.setText("Yüklü sürüm  "+getPackageManager().getPackageInfo(getPackageName(),0).versionName);}catch(Exception e){version.setText("Sürüm okunamadı.");}
        panel.addView(version);
        label=new TextView(this);
        label.setText("Güncellemeler GitHub Releases üzerinden indirilir. İndirme arka planda devam eder.");panel.addView(label);
        check=new Button(this);check.setText("Güncellemeleri kontrol et");panel.addView(check);
        install=new Button(this);install.setText("İndir ve kur");panel.addView(install);install.setVisibility(View.GONE);
        cancel=new Button(this);cancel.setText("Güncelleme indirmesini iptal et");panel.addView(cancel);cancel.setVisibility(View.GONE);
        status=new TextView(this);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);panel.addView(status);
        check.setOnClickListener(v->check());
        install.setOnClickListener(v->{if(pending!=null)launchInstaller();else download();});
        cancel.setOnClickListener(v->cancelUpdate());
        applyDimensions();
        clearLegacyCredential();
        if(state!=null)waitingPermission=state.getBoolean("waiting");
        if(downloads.current()!=null)status.setText("Güncelleme durumu alınıyor…");
        else if(state!=null&&state.getBoolean("checking"))check();
    }
    private void applyDimensions(){
        panel.setPadding(dp(20),dp(20),dp(20),dp(20));title.setTextSize(26);
        label.setPadding(0,dp(20),0,dp(12));
    }
    @Override public void onConfigurationChanged(Configuration config){
        super.onConfigurationChanged(config);applyDimensions();panel.requestApplyInsets();
    }
    private void clearLegacyCredential(){
        if(!getPreferences(0).contains("credential"))return;
        getPreferences(0).edit().remove("credential").apply();
        try{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);ks.deleteEntry("release-read-token");}
        catch(Exception ignored){}
    }
    private long installedCode()throws Exception{return getPackageManager().getPackageInfo(getPackageName(),0).getLongVersionCode();}
    private void fail(Exception e){
        post(()->{checking=false;verifying=false;check.setEnabled(true);install.setEnabled(true);
            status.setText(e.getLocalizedMessage()==null?"İşlem tamamlanamadı.":e.getLocalizedMessage());});
    }
    private void check(){
        if(checking||downloads.current()!=null)return;
        latest=null;patch=null;pending=null;install.setVisibility(View.GONE);checking=true;
        check.setEnabled(false);status.setText("GitHub Releases kontrol ediliyor…");
        worker.execute(()->{try{
            ReleaseClient.Release found=new ReleaseClient().latest();
            long installed=installedCode();
            ReleaseClient.Patch candidate=null;
            if(found.code>installed)try{candidate=found.patchFor(ApkDelta.sha256(UpdateDownload.installedApk(this)));}catch(Exception ignored){}
            final ReleaseClient.Patch match=candidate;
            post(()->{checking=false;check.setEnabled(true);latest=found;patch=match;
                if(found.code>installed){describeRelease();install.setText("İndir ve kur");install.setEnabled(true);install.setVisibility(View.VISIBLE);}
                else status.setText("Uygulama güncel.");
            });
        }catch(Exception e){fail(e);}});
    }
    private static String mb(long bytes){return String.format(java.util.Locale.ROOT,"%.2f MB",bytes/1000000.0);}
    private void describeRelease(){
        if(latest==null)return;
        status.setText("Yeni sürüm  "+latest.name+"\nTam APK ("+(latest.filename.contains("arm64")?"ARM64":"Evrensel")+"): "+mb(latest.size)
            +(patch==null?"\nUyumlu kısmi güncelleme yok.":"\nKısmi güncelleme: "+mb(patch.size))
            +"\n"+(UpdateDownload.unmeteredWifi(this)?"Wi-Fi / kotasız bağlantı: tam APK":"Mobil / kotalı bağlantı: "+(patch==null?"Wi-Fi beklenir":"kısmi güncelleme")));
    }
    private void download(){
        if(latest==null||downloads.current()!=null)return;
        if(UpdateDownload.unmeteredWifi(this)){startDownload(null,false);return;}
        if(patch!=null){startDownload(patch,true);return;}
        new android.app.AlertDialog.Builder(this).setTitle("Uyumlu kısmi güncelleme yok")
            .setMessage("Tam APK "+mb(latest.size)+". Wi-Fi bekleyin veya mobil veri kullanımını onaylayın.")
            .setPositiveButton("Wi-Fi bekle",(dialog,which)->startDownload(null,false))
            .setNeutralButton("Mobil veri kullan",(dialog,which)->startDownload(null,true))
            .setNegativeButton("Vazgeç",null).show();
    }
    private void startDownload(ReleaseClient.Patch selected,boolean metered){
        if(latest==null||downloads.current()!=null)return;
        try{
            downloads.start(latest,selected,metered);
            pending=null;verifiedId=-1;autoInstall=true;
            status.setText("Güncelleme indiriliyor…");refreshDownload();
        }catch(Exception e){fail(e);}
    }
    private void transferFailed(UpdateDownload.Job job,String message){
        if(job.patch){
            try{
                downloads.fallback(job);autoInstall=false;
                status.setText("Kısmi güncelleme başarısız. Tam APK Wi-Fi bağlantısı bekliyor.");schedule();return;
            }catch(Exception e){message+=" "+e.getLocalizedMessage();}
        }
        downloads.clear(job);pending=null;verifiedId=-1;autoInstall=false;
        cancel.setVisibility(View.GONE);check.setEnabled(true);install.setVisibility(View.GONE);
        status.setText(message);
    }
    private void schedule(){
        handler.removeCallbacks(tick);
        if(resumed)handler.postDelayed(tick,1000);
    }
    private void refreshDownload(){
        handler.removeCallbacks(tick);
        UpdateDownload.Job job=downloads.current();
        if(job==null)return;
        try{
            if(job.code<=installedCode()){
                downloads.clear(job);pending=null;verifiedId=-1;latest=null;
                check.setEnabled(true);install.setVisibility(View.GONE);cancel.setVisibility(View.GONE);
                status.setText("Uygulama güncel.");return;
            }
            UpdateDownload.Snapshot state=downloads.snapshot(job);
            check.setEnabled(false);cancel.setVisibility(View.VISIBLE);cancel.setEnabled(!verifying);
            if(state.status==DownloadManager.STATUS_SUCCESSFUL){
                if(verifiedId==job.id&&pending!=null&&pending.isFile()){
                    ready();return;
                }
                install.setVisibility(View.GONE);
                if(verifying)return;
                verifying=true;cancel.setEnabled(false);status.setText(job.patch?"Kısmi güncellemeden APK oluşturuluyor ve doğrulanıyor…":"APK boyutu ve imzası doğrulanıyor…");
                worker.execute(()->{try{
                    File file=downloads.verify(job);
                    post(()->{
                        verifying=false;
                        UpdateDownload.Job active=downloads.current();
                        if(active==null||active.id!=job.id)return;
                        pending=file;verifiedId=job.id;cancel.setEnabled(true);ready();
                    });
                }catch(Exception e){post(()->{
                    verifying=false;autoInstall=false;
                    UpdateDownload.Job active=downloads.current();
                    if(active!=null&&active.id==job.id){
                        transferFailed(job,"APK doğrulanamadı: "+e.getLocalizedMessage());
                    }
                });}});
                return;
            }
            install.setVisibility(View.GONE);
            if(state.status==DownloadManager.STATUS_FAILED){
                transferFailed(job,"İndirme tamamlanamadı ("+state.reason+"). Güncellemeyi tekrar kontrol edin.");return;
            }
            String kind=job.patch?"Kısmi güncelleme":"Tam APK";
            if(!job.metered&&!UpdateDownload.unmeteredWifi(this))
                status.setText(kind+" · "+mb(job.transferSize)+" · Wi-Fi bekleniyor · %"+state.percent);
            else if(state.status==DownloadManager.STATUS_PAUSED)
                status.setText(kind+" · "+mb(job.transferSize)+" · beklemede · %"+state.percent);
            else status.setText(kind+" · "+mb(job.transferSize)+" · %"+state.percent+" · arka planda devam eder.");
            schedule();
        }catch(Exception e){fail(e);schedule();}
    }
    private void ready(){
        status.setText("Güncelleme hazır. Android kurulum ekranında Güncelle’yi onaylayın.");
        install.setText("Güncellemeyi kur");install.setVisibility(View.VISIBLE);install.setEnabled(true);cancel.setEnabled(true);
        if(resumed&&autoInstall&&!waitingPermission){autoInstall=false;launchInstaller();}
    }
    private void cancelUpdate(){
        if(verifying)return;
        UpdateDownload.Job job=downloads.current();
        if(job!=null)downloads.clear(job);
        handler.removeCallbacks(tick);pending=null;verifiedId=-1;autoInstall=false;waitingPermission=false;
        latest=null;check.setEnabled(true);install.setVisibility(View.GONE);cancel.setVisibility(View.GONE);
        status.setText("Güncelleme iptal edildi.");
    }
    private void launchInstaller(){
        if(!resumed||pending==null)return;
        try{
            UpdateDownload.Job job=downloads.current();
            if(job==null||job.id!=verifiedId)throw new IOException("Güncellemeyi tekrar doğrulayın.");
            ReleaseClient.validate(this,pending,job.code,job.name);
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
    @Override protected void onResume(){
        super.onResume();resumed=true;refreshDownload();if(downloads.current()==null&&latest!=null&&install.getVisibility()==View.VISIBLE)describeRelease();
    }
    @Override protected void onPause(){
        resumed=false;autoInstall=false;handler.removeCallbacks(tick);super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle b){
        super.onSaveInstanceState(b);b.putBoolean("waiting",waitingPermission);b.putBoolean("checking",checking);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==7&&waitingPermission){
            waitingPermission=false;
            if(getPackageManager().canRequestPackageInstalls()){
                autoInstall=true;
                if(resumed)refreshDownload();
            }else status.setText("Kurulum izni verilmedi. Güncellemeyi kur düğmesiyle tekrar deneyebilirsiniz.");
        }
    }
    @Override protected void onDestroy(){
        handler.removeCallbacks(tick);worker.shutdownNow();super.onDestroy();
    }
}
