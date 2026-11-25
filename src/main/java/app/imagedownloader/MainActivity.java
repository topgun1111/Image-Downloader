package app.imagedownloader;

import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.net.Uri;
import android.provider.MediaStore;
import android.webkit.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import java.net.*;

public final class MainActivity extends Activity {
    private static final String PAGE="https://gallery.invalid/index.html";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Map<String,String[]> known=new HashMap<>();
    private WebView view;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        view=new WebView(this);setContentView(view);
        view.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        WebSettings settings=view.getSettings();
        settings.setJavaScriptEnabled(true); settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        view.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest req){
                if(PAGE.equals(req.getUrl().toString())){
                    try {return new WebResourceResponse("text/html","UTF-8",getAssets().open("index.html"));}
                    catch(IOException e){return new WebResourceResponse("text/plain","UTF-8",null);}
                }
                return null;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest req){
                if(req.isForMainFrame() && req.hasGesture() && "https".equals(req.getUrl().getScheme())){
                    try {startActivity(new Intent(Intent.ACTION_VIEW,req.getUrl()));} catch(ActivityNotFoundException ignored){}
                }
                return true;
            }
        });
        view.addJavascriptInterface(new Bridge(),"Gallery");view.loadUrl(PAGE);
    }
    private final class Bridge {
        @JavascriptInterface public void showSettings(){
            runOnUiThread(()->{if(!isDestroyed() && PAGE.equals(view.getUrl()))
                startActivity(new Intent(MainActivity.this,UpdateSettings.class));});
        }
        @JavascriptInterface public void request(int id,String action,String data){
            if(worker.isShutdown()) return;
            worker.execute(()->{
                JSONObject answer;
                try {
                    JSONObject req=new JSONObject(data);
                    if(action.equals("search")){
                        answer=Search.run(req);
                        if(req.optInt("page",0)==0) known.clear();
                        JSONArray rows=answer.getJSONArray("rows");
                        for(int i=0;i<rows.length();i++){
                            JSONObject row=rows.getJSONObject(i);String url=row.getString("url");
                            known.put(url,new String[]{url,req.getString("q")});row.put("id",url);
                        }
                    } else if(action.equals("save")){
                        String[] item=known.get(req.getString("id"));
                        if(item==null) throw new IOException("Aramayı yenileyin.");
                        save(item[0],item[1]);answer=new JSONObject();
                    } else throw new IOException("Geçersiz işlem.");
                } catch(Exception e){
                    answer=new JSONObject();
                    try {answer.put("error",Search.safeError(e));} catch(JSONException ignored){}
                }
                String callback="window.galleryReply("+id+","+JSONObject.quote(answer.toString())+")";
                runOnUiThread(()->{if(!isDestroyed())view.evaluateJavascript(callback,null);});
            });
        }
    }
    private void save(String url,String query) throws Exception {
        HttpURLConnection c=Search.open(url);
        Uri dest=null;boolean complete=false;
        try(BufferedInputStream in=new BufferedInputStream(c.getInputStream())){
            in.mark(128);byte[] head=new byte[64];int len=0,n;
            while(len<head.length && (n=in.read(head,len,head.length-len))>0)len+=n;
            in.reset();
            String format=format(head,len);
            String stem=query.replaceAll("[^\\p{L}\\p{N}_-]+","_");
            if(stem.length()>48)stem=stem.substring(0,48);
            ContentValues values=new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME,stem+"_"+UUID.randomUUID().toString().substring(0,8)+"."+format);
            values.put(MediaStore.MediaColumns.MIME_TYPE,"image/"+(format.equals("jpg")?"jpeg":format));
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/Google");
            values.put(MediaStore.MediaColumns.IS_PENDING,1);
            ContentResolver resolver=getContentResolver();
            dest=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
            if(dest==null) throw new IOException("Dosya oluşturulamadı.");
            try(OutputStream out=resolver.openOutputStream(dest)){
                if(out==null) throw new IOException("Dosya açılamadı.");
                byte[] buf=new byte[65536];long total=0;
                while((n=in.read(buf))!=-1){
                    if(Thread.currentThread().isInterrupted())throw new IOException("İndirme durduruldu.");
                    total+=n;if(total>67108864)throw new IOException("64 MB dosya sınırı aşıldı.");
                    out.write(buf,0,n);
                }
                long expected=c.getContentLengthLong();
                if(c.getContentEncoding()==null && expected>=0 && total!=expected) throw new IOException("Eksik dosya.");
            }
            values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);
            if(resolver.update(dest,values,null,null)!=1)throw new IOException("Kayıt tamamlanamadı.");
            complete=true;
        } finally {
            c.disconnect();if(!complete && dest!=null)getContentResolver().delete(dest,null,null);
        }
    }
    static String format(byte[] h,int n) throws IOException {
        if(n>=3&&(h[0]&255)==255&&(h[1]&255)==216&&(h[2]&255)==255)return "jpg";
        String s=new String(h,0,n,java.nio.charset.StandardCharsets.ISO_8859_1);
        if(s.startsWith("\u0089PNG\r\n\u001a\n"))return "png";
        if(s.startsWith("GIF87a")||s.startsWith("GIF89a"))return "gif";
        if(s.startsWith("RIFF")&&s.startsWith("WEBP",8))return "webp";
        if(s.startsWith("BM"))return "bmp";
        if(s.startsWith("ftyp",4)&&(s.contains("avif")||s.contains("avis")))return "avif";
        if(s.startsWith("II*\0")||s.startsWith("MM\0*"))return "tiff";
        throw new IOException("Kaynak desteklenen bir görsel göndermedi.");
    }
    @Override public void onBackPressed(){
        view.evaluateJavascript("(function(){const d=document.querySelector('dialog[open]');if(d){d.close();return true}return false})()",v->{if(!"true".equals(v))finish();});
    }
    @Override protected void onDestroy(){
        worker.shutdownNow();view.removeJavascriptInterface("Gallery");view.destroy();super.onDestroy();
    }
}
