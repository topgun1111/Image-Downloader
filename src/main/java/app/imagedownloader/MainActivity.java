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
    private final ExecutorService downloads=Executors.newSingleThreadExecutor();
    private final Map<String,String[]> known=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer,SearchCancellation> searches=new ConcurrentHashMap<>();
    private WebView view;
    private VisualAI visual;
    private final ThumbnailRecovery thumbnails=new ThumbnailRecovery();
    private final Set<Long> visualHashes=new LinkedHashSet<>();
    private int lastSearchSession=-1;
    private volatile PreparedShare preparedShare;
    private static final class PreparedShare {
        final String token,folder,prompt;
        final ArrayList<Uri> uris;
        PreparedShare(String token,String folder,String prompt,ArrayList<Uri> uris){
            this.token=token;this.folder=folder;this.prompt=prompt;this.uris=new ArrayList<>(uris);
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        visual=new VisualAI(this);
        view=new WebView(this);setContentView(view);
        view.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        WebSettings settings=view.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true); settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
        settings.setUserAgentString(settings.getUserAgentString()+" "+Search.WIKI_UA);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        view.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest req){
                if(PAGE.equals(req.getUrl().toString())){
                    try {return new WebResourceResponse("text/html","UTF-8",getAssets().open("index.html"));}
                    catch(IOException e){return new WebResourceResponse("text/plain","UTF-8",null);}
                }
                Uri uri=req.getUrl();
                if("https".equals(uri.getScheme())&&"gallery.invalid".equals(uri.getHost())&&"/thumb".equals(uri.getPath())){
                    String id=uri.getQueryParameter("id");
                    return thumbnails.response(id==null?null:known.get(id));
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
        @JavascriptInterface public boolean translationRuntimeAvailable(){return TranslationEngine.available();}
        @JavascriptInterface public void showSettings(){
            runOnUiThread(()->{if(!isDestroyed() && PAGE.equals(view.getUrl()))
                startActivity(new Intent(MainActivity.this,UpdateSettings.class));});
        }
        @JavascriptInterface public void cancelSearch(int id){
            SearchCancellation token=searches.get(id);if(token!=null)token.cancel();
        }
        @JavascriptInterface public void sharePrepared(String token,boolean launch){
            runOnUiThread(()->{
                if(isDestroyed()||!PAGE.equals(view.getUrl()))return;
                PreparedShare batch=preparedShare;
                if(batch==null||!batch.token.equals(token))return;
                if(!batch.prompt.isEmpty()){
                    try{
                        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("ChatGPT",batch.prompt));
                    }catch(RuntimeException ignored){}
                }
                if(launch)openShare(batch);
                else android.widget.Toast.makeText(MainActivity.this,"Görseller hazır: "+batch.folder,android.widget.Toast.LENGTH_LONG).show();
            });
        }
        @JavascriptInterface public void request(int id,String action,String data){
            if(worker.isShutdown()) return;
            final SearchCancellation token=action.equals("search")?new SearchCancellation():null;
            if(token!=null)searches.put(id,token);
            try{((action.equals("save")||action.equals("prepareShare"))?downloads:worker).execute(()->{
                JSONObject answer;
                try {
                    if(token!=null)token.enter();
                    JSONObject req=new JSONObject(data);
                    if(action.equals("modelStatus")){
                        answer=new JSONObject().put("installed",visual.installed());
                    } else if(action.equals("installModel")){
                        visual.install(message->progress(id,new JSONObjectUnchecked(message).value));
                        answer=new JSONObject().put("installed",visual.installed());
                    } else if(action.equals("search")){
                        int session=req.optInt("session",-1);
                        if(session>=0?session!=lastSearchSession:req.optInt("page",0)==0){known.clear();thumbnails.clear();visualHashes.clear();lastSearchSession=session;}
                        SearchCancellation.check();
                        answer=req.has("providers")?MultiSearch.run(req,rows->{
                            SearchCancellation.check();
                            JSONArray preview=new JSONArray();
                            String q=req.getString("q"),mode=req.optString("precision","strict");
                            for(int i=0;i<rows.length();i++){
                                JSONObject row=rows.getJSONObject(i);
                                if(Relevance.admit(q,row,mode,null)){
                                    row.put("relevance",Relevance.rank(q,row,null)).put("preliminary",true);
                                    preview.put(row);
                                }
                            }
                            if(preview.length()>0){register(preview,req);progress(id,new JSONObject().put("rows",preview));}
                        }):Search.run(req);
                        SearchCancellation.check();
                        if(req.has("providers")){
                            String mode=req.optString("precision","strict");
                            boolean useAI=req.optBoolean("ai",true);
                            boolean duplicate=req.optBoolean("duplicates",true);
                            if(useAI&&!visual.installed()&&!mode.equals("fast"))answer.put("notice","AI modeli kurulmamış; metin filtresi kullanıldı.");
                            JSONArray raw=answer.getJSONArray("rows");
                            JSONArray ranked;
                            try{
                                ranked=visual.rank(raw,req.getString("q"),mode,useAI,duplicate,visualHashes,
                                    message->progress(id,new JSONObjectUnchecked(message).value),
                                    batch->{try{SearchCancellation.check();register(batch,req);progress(id,new JSONObject().put("rows",batch));}catch(Exception ignored){}});
                            }catch(Exception|LinkageError err){
                                SearchCancellation.check();
                                answer.put("notice","AI çalışmadı; metin filtresi kullanıldı.");
                                ranked=new JSONArray();
                                for(int i=0;i<raw.length();i++){JSONObject row=raw.getJSONObject(i);if(Relevance.admit(req.getString("q"),row,mode,null)){row.put("relevance",Relevance.score(req.getString("q"),row));ranked.put(row);}}
                            }
                            answer.put("rows",ranked);
                        }
                        SearchCancellation.check();
                        register(answer.getJSONArray("rows"),req);
                    } else if(action.equals("prepareShare")){
                        answer=prepareShare(id,new ShareSelection(req));
                    } else if(action.equals("save")){
                        String[] item=known.get(req.getString("id"));
                        if(item==null) throw new IOException("Aramayı yenileyin.");
                        boolean clean=Boolean.parseBoolean(item[3]);
                        if(!item[4].isEmpty())MultiSearch.trackUnsplash(item[4],item[5]);
                        boolean fallback=false;
                        try {save(item[0],item[1],clean);}
                        catch(Exception e){
                            if(item[2].isEmpty()||Thread.currentThread().isInterrupted())throw e;
                            save(item[2],item[1],clean);fallback=true;
                        }
                        answer=new JSONObject().put("fallback",fallback);
                    } else throw new IOException("Geçersiz işlem.");
                } catch(Exception|LinkageError e){
                    answer=new JSONObject();
                    try {if(token!=null&&token.isCancelled())answer.put("cancelled",true);
                        else answer.put("error",Search.safeError(e instanceof Exception?(Exception)e:new IOException("AI çalışma zamanı yüklenemedi.")));} catch(JSONException ignored){}
                } finally {
                    if(token!=null){token.leave();Thread.interrupted();}
                }
                if(token!=null){
                    searches.remove(id,token);
                    if(token.isCancelled()){answer=new JSONObject();try{answer.put("cancelled",true);}catch(JSONException ignored){}}
                }
                String callback="window.galleryReply("+id+","+JSONObject.quote(answer.toString())+")";
                runOnUiThread(()->{if(!isDestroyed())view.evaluateJavascript(callback,null);});
            });}catch(RejectedExecutionException e){if(token!=null)searches.remove(id,token);}
        }
    }
    private static final class JSONObjectUnchecked {
        final JSONObject value=new JSONObject();
        JSONObjectUnchecked(String message){try{value.put("message",message);}catch(JSONException ignored){}}
    }
    private void progress(int id,JSONObject data){
        SearchCancellation token=searches.get(id);if(token!=null&&token.isCancelled())return;
        String callback="window.galleryProgress("+id+","+JSONObject.quote(data.toString())+")";
        runOnUiThread(()->{if(!isDestroyed())view.evaluateJavascript(callback,null);});
    }
    private void register(JSONArray rows,JSONObject req)throws JSONException{
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String url=row.getString("url");
            known.put(url,new String[]{url,req.getString("q"),row.optString("fallback"),String.valueOf(req.optBoolean("clean",true)),row.optString("tracking"),req.optJSONObject("keys")==null?"":req.optJSONObject("keys").optString("unsplash"),row.optString("thumb"),row.optString("pageUrl")});row.put("id",url);
        }
    }
    private JSONObject prepareShare(int requestId,ShareSelection selection)throws Exception{
        String token=UUID.randomUUID().toString();
        String folder="Download/Google/ChatGPT/"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date())+"-"+token.substring(0,8);
        ArrayList<Uri> uris=new ArrayList<>();
        JSONArray errors=new JSONArray();
        int alternate=0,index=0;
        List<String[]> items=new ArrayList<>();
        for(String id:selection.ids)items.add(known.get(id));
        for(String[] item:items){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Paylaşım durduruldu.");
            progress(requestId,new JSONObject().put("message","Görseller hazırlanıyor · "+(++index)+" / "+items.size()));
            try{
                if(item==null)throw new IOException("Görsel artık mevcut değil.");
                if(!item[4].isEmpty())MultiSearch.trackUnsplash(item[4],item[5]);
                boolean clean=Boolean.parseBoolean(item[3]);
                Uri uri;
                String name=String.format(Locale.ROOT,"%02d_%s",index,item[1]);
                try{uri=save(item[0],name,clean,folder);}
                catch(Exception ex){
                    if(item[2].isEmpty()||Thread.currentThread().isInterrupted())throw ex;
                    uri=save(item[2],name,clean,folder);alternate++;
                }
                uris.add(uri);
            }catch(Exception ex){
                if(Thread.currentThread().isInterrupted())throw ex;
                errors.put(index+". görsel: "+Search.safeError(ex));
            }
        }
        if(uris.isEmpty())throw new IOException("Hiçbir görsel hazırlanamadı. "+(errors.length()>0?errors.optString(0):""));
        preparedShare=new PreparedShare(token,folder,selection.prompt,uris);
        return new JSONObject().put("token",token).put("folder",folder).put("count",uris.size())
            .put("requested",items.size()).put("alternate",alternate).put("errors",errors);
    }
    private void openShare(PreparedShare batch){
        Intent intent=new Intent(batch.uris.size()==1?Intent.ACTION_SEND:Intent.ACTION_SEND_MULTIPLE);
        String mime=getContentResolver().getType(batch.uris.get(0));
        for(Uri uri:batch.uris)if(!Objects.equals(mime,getContentResolver().getType(uri))){mime="image/*";break;}
        intent.setType(mime==null?"image/*":mime);
        if(batch.uris.size()==1)intent.putExtra(Intent.EXTRA_STREAM,batch.uris.get(0));
        else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM,new ArrayList<>(batch.uris));
        ClipData clips=ClipData.newUri(getContentResolver(),"ChatGPT images",batch.uris.get(0));
        for(int i=1;i<batch.uris.size();i++)clips.addItem(new ClipData.Item(batch.uris.get(i)));
        intent.setClipData(clips);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if(!batch.prompt.isEmpty())intent.putExtra(Intent.EXTRA_TEXT,batch.prompt);
        try{
            startActivity(new Intent(intent).setPackage("com.openai.chatgpt"));
        }catch(RuntimeException unavailable){
            try{startActivity(Intent.createChooser(intent,"ChatGPT ile paylaş"));}
            catch(RuntimeException noTarget){
                new android.app.AlertDialog.Builder(this).setTitle("Paylaşım açılamadı")
                    .setMessage("ChatGPT'de dosya ekle bölümünden bu klasörü seçin:\n"+batch.folder)
                    .setPositiveButton("Tamam",null).show();
            }
        }
    }
    private Uri save(String url,String query,boolean clean) throws Exception {
        return save(url,query,clean,"Download/Google");
    }
    private Uri save(String url,String query,boolean clean,String folder) throws Exception {
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
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,folder);
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
            if(clean){
                android.graphics.BitmapFactory.Options bounds=new android.graphics.BitmapFactory.Options();
                bounds.inJustDecodeBounds=true;
                try(InputStream check=resolver.openInputStream(dest)){
                    if(check!=null)android.graphics.BitmapFactory.decodeStream(check,null,bounds);
                }
                if((bounds.outWidth>0&&bounds.outWidth<96)||(bounds.outHeight>0&&bounds.outHeight<96))
                    throw new IOException("Çok küçük görsel / yer tutucu reddedildi.");
            }
            values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);
            if(resolver.update(dest,values,null,null)!=1)throw new IOException("Kayıt tamamlanamadı.");
            complete=true;
            return dest;
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
        view.evaluateJavascript("(function(){const d=document.querySelector('dialog[open]');if(d){if(d.id==='shareDialog'&&sharing)return true;d.close();return true}return false})()",v->{if(!"true".equals(v))finish();});
    }
    @Override protected void onDestroy(){
        for(SearchCancellation token:searches.values())token.cancel();
        try{worker.execute(()->visual.close());}catch(Exception ignored){}worker.shutdown();downloads.shutdownNow();view.removeJavascriptInterface("Gallery");view.destroy();super.onDestroy();
    }
}
