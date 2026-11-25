package app.imagedownloader;

import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.provider.Settings;
import android.security.keystore.*;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import android.util.Base64;
import androidx.core.content.FileProvider;
import java.io.*;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class UpdateSettings extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private EditText access;
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
        TextView label=new TextView(this);label.setPadding(0,dp(20),0,0);
        label.setText("Özel GitHub deposu için erişim anahtarı\nImage-Downloader deposunda Contents: Read yetkisi yeterlidir.");panel.addView(label);
        access=new EditText(this);access.setSingleLine(true);access.setSaveEnabled(false);
        access.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        access.setHint("GitHub erişim anahtarı");panel.addView(access);
        check=new Button(this);check.setText("Güncellemeleri kontrol et");panel.addView(check);
        install=new Button(this);install.setText("İndir ve kur");panel.addView(install);install.setVisibility(View.GONE);
        status=new TextView(this);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);panel.addView(status);
        check.setOnClickListener(v->check());install.setOnClickListener(v->download());
        try{access.setText(readToken());}catch(Exception e){status.setText("Kayıtlı anahtar okunamadı. Yeniden girin.");}
        if(state!=null && state.getBoolean("waiting")){
            pendingCode=state.getLong("code");pendingName=state.getString("version","");
            pending=new File(new File(getCacheDir(),"updates"),"update-"+pendingCode+".apk");waitingPermission=true;
        }
    }
    private SecretKey storageKey() throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        String alias="release-read-token";
        if(!ks.containsAlias(alias)){
            KeyGenerator kg=KeyGenerator.getInstance("AES","AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return kg.generateKey();
        }
        return (SecretKey)ks.getKey(alias,null);
    }
    private String readToken() throws Exception{
        String value=getPreferences(0).getString("credential","");
        if(value.isEmpty())return "";
        String[] fields=value.split(":",2);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,storageKey(),new GCMParameterSpec(128,Base64.decode(fields[0],Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(fields[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    private void remember(String token) throws Exception{
        if(token.isEmpty()){getPreferences(0).edit().remove("credential").apply();return;}
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,storageKey());
        String encrypted=Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        getPreferences(0).edit().putString("credential",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+encrypted).apply();
    }
    private void busy(boolean value){check.setEnabled(!value);install.setEnabled(!value);access.setEnabled(!value);}
    private void fail(Exception e){post(()->{busy(false);status.setText(e.getLocalizedMessage()==null?"İşlem tamamlanamadı.":e.getLocalizedMessage());});}
    private void check(){
        String token=access.getText().toString().trim();latest=null;pending=null;install.setVisibility(View.GONE);
        busy(true);status.setText("GitHub Releases kontrol ediliyor…");
        worker.execute(()->{try{
            if(token.isEmpty()){remember("");throw new IOException("Özel depoya erişmek için GitHub anahtarını girin.");}
            ReleaseClient.Release found=new ReleaseClient(token).latest();
            remember(token);
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
        String token=access.getText().toString().trim();
        busy(true);status.setText("APK indiriliyor…");
        worker.execute(()->{try{
            File file=new ReleaseClient(token).download(this,release,pc->post(()->status.setText("APK indiriliyor  %"+pc)));
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
