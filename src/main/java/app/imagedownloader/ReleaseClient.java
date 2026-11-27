package app.imagedownloader;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.regex.*;

final class ReleaseClient {
    private static final String API="https://api.github.com/repos/topgun1111/Image-Downloader/releases";
    private static final Pattern TAG=Pattern.compile("v[0-9]+\\.[0-9]+\\.([0-9]+)-[0-9a-f]{8,40}");
    private static final int MAX_APK=ApkDelta.MAX;
    static final String DOWNLOAD="https://github.com/topgun1111/Image-Downloader/releases/download/";
    ReleaseClient(){}

    static final class Patch {
        final long size;
        final String digest,url,base;
        Patch(long size,String digest,String url,String base){this.size=size;this.digest=digest;this.url=url;this.base=base;}
    }
    static final class Release {
        final int code;
        final long size;
        final String name,digest,downloadUrl,filename;
        final JSONArray assets;
        Release(JSONObject json) throws Exception {this(json,false);}
        Release(JSONObject json,boolean arm64) throws Exception {
            String tag=json.getString("tag_name");
            Matcher m=TAG.matcher(tag);
            if(!m.matches()||json.optBoolean("draft")||json.optBoolean("prerelease"))
                throw new IOException("Güncellenebilir bir kararlı sürüm bulunamadı.");
            name=tag.substring(1); code=Integer.parseInt(m.group(1));
            assets=json.getJSONArray("assets");
            JSONObject apk=null,optimized=null;
            for(int i=0;i<assets.length();i++){
                JSONObject a=assets.getJSONObject(i);
                if("Image-Downloader.apk".equals(a.optString("name")) && "uploaded".equals(a.optString("state"))) apk=a;
                if(arm64&&"Image-Downloader-arm64-v8a.apk".equals(a.optString("name"))&&"uploaded".equals(a.optString("state")))optimized=a;
            }
            if(apk==null)throw new IOException("Sürümün APK dosyası henüz hazır değil.");
            if(optimized!=null&&validAsset(optimized,tag,"Image-Downloader-arm64-v8a.apk")&&optimized.optLong("size")<apk.optLong("size"))apk=optimized;
            filename=apk.getString("name");
            size=apk.getLong("size");digest=apk.optString("digest");
            downloadUrl=DOWNLOAD+tag+"/"+filename;
            if(!downloadUrl.equals(apk.optString("browser_download_url"))||size<=0||size>MAX_APK||!digest.matches("sha256:[a-fA-F0-9]{64}"))
                throw new IOException("Yayınlanan APK bilgileri doğrulanamadı.");
        }
        private static boolean validAsset(JSONObject a,String tag,String filename){
            long bytes=a.optLong("size",0);
            return (DOWNLOAD+tag+"/"+filename).equals(a.optString("browser_download_url"))&&bytes>0&&bytes<=MAX_APK&&a.optString("digest").matches("sha256:[a-fA-F0-9]{64}");
        }
        Patch patchFor(String baseDigest){
            if(baseDigest==null||!baseDigest.matches("sha256:[a-f0-9]{64}"))return null;
            String suffix=baseDigest.substring(7)+".idpatch";
            String[] names=filename.equals("Image-Downloader-arm64-v8a.apk")?
                new String[]{"delta2-arm64-"+suffix}:new String[]{"delta2-"+suffix,"delta-"+suffix};
            Patch best=null;
            for(String candidate:names)for(int i=0;i<assets.length();i++){
                JSONObject a=assets.optJSONObject(i);if(a==null)continue;
                long bytes=a.optLong("size",0);String hash=a.optString("digest"),url=DOWNLOAD+"v"+name+"/"+candidate;
                if(candidate.equals(a.optString("name"))&&"uploaded".equals(a.optString("state"))
                    &&url.equals(a.optString("browser_download_url"))&&bytes>0&&bytes<size*0.8
                    &&hash.matches("sha256:[a-fA-F0-9]{64}")&&(best==null||bytes<best.size))best=new Patch(bytes,hash,url,baseDigest);
            }
            return best;
        }
    }
    private HttpURLConnection connect(String address,boolean binary) throws IOException {
        URL url=new URL(address);
        for(int i=0;i<5;i++){
            if(!"https".equals(url.getProtocol()))throw new IOException("Güvensiz yönlendirme.");
            HttpURLConnection c=(HttpURLConnection)url.openConnection();
            c.setInstanceFollowRedirects(false);c.setUseCaches(false);
            c.setConnectTimeout(15000);c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent","Image-Downloader-Android");
            c.setRequestProperty("Accept",binary?"application/octet-stream":"application/vnd.github+json");
            try {
                int status=c.getResponseCode();
                if(status==200)return c;
                if(status>=300 && status<400 && c.getHeaderField("Location")!=null){
                    url=new URL(url,c.getHeaderField("Location"));c.disconnect();continue;
                }
                if(status==404)throw new IOException("Sürüm bulunamadı. GitHub deposunun herkese açık olduğundan emin olun.");
                if(status==403||status==429)throw new IOException("GitHub erişim sınırına ulaşıldı. Bir süre sonra tekrar deneyin.");
                if(status==401)throw new IOException("GitHub anonim erişime izin vermedi.");
                throw new IOException("GitHub HTTP "+status+" yanıtını verdi.");
            }catch(IOException e){c.disconnect();throw e;}
        }
        throw new IOException("GitHub yönlendirmesi tamamlanamadı.");
    }
    Release latest() throws Exception {
        HttpURLConnection c=connect(API+"/latest",false);
        try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1){
                if(out.size()+n>1024*1024)throw new IOException("Sürüm bilgisi çok büyük.");
                out.write(b,0,n);
            }
            return new Release(new JSONObject(out.toString(StandardCharsets.UTF_8.name())),android.os.Build.SUPPORTED_ABIS.length>0&&"arm64-v8a".equals(android.os.Build.SUPPORTED_ABIS[0]));
        }finally{c.disconnect();}
    }
    interface Progress { void percent(int percent); }
    static File directory(Context context) throws IOException {
        File dir=new File(context.getCacheDir(),"updates");
        if(!dir.isDirectory()&&!dir.mkdirs()&&!dir.isDirectory())throw new IOException("Önbellek açılamadı.");
        return dir;
    }
    File download(Context context,Release release,Progress progress) throws Exception {
        File dir=directory(context),temp=File.createTempFile("download-",".part",dir);
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            HttpURLConnection c=connect(release.downloadUrl,true);
            try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(temp)){
                byte[] b=new byte[65536];int n,last=-1;long size=0;
                while((n=in.read(b))!=-1){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                    size+=n;if(size>release.size)throw new IOException("APK boyutu uyuşmuyor.");
                    out.write(b,0,n);md.update(b,0,n);
                    int pc=(int)(100L*size/release.size);if(pc!=last){progress.percent(pc);last=pc;}
                }
                if(size!=release.size)throw new IOException("APK eksik indirildi.");
            }finally{c.disconnect();}
            StringBuilder hex=new StringBuilder("sha256:");
            for(byte b:md.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            if(!release.digest.equalsIgnoreCase(hex.toString()))throw new IOException("Dosya özeti uyuşmuyor.");
            validate(context,temp,release.code,release.name);
            File target=new File(dir,"update-"+release.code+".apk");
            java.nio.file.Files.move(temp.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return target;
        }finally{temp.delete();}
    }
    static void verifyDownloaded(Context ctx,File file,long size,String digest,long code,String name)throws Exception{
        if(size<=0||size>MAX_APK||file.length()!=size||!digest.matches("sha256:[a-fA-F0-9]{64}"))
            throw new IOException("APK boyutu veya dosya özeti bilgisi uyuşmuyor.");
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){
            byte[] b=new byte[65536];int n;
            while((n=in.read(b))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                md.update(b,0,n);
            }
        }
        StringBuilder hex=new StringBuilder("sha256:");
        for(byte b:md.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        if(!digest.equalsIgnoreCase(hex.toString()))throw new IOException("Dosya özeti uyuşmuyor.");
        validate(ctx,file,code,name);
    }
    static void validate(Context ctx,File file,long code,String name) throws Exception {
        PackageManager pm=ctx.getPackageManager();
        PackageInfo installed=pm.getPackageInfo(ctx.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo apk=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);
        if(apk==null || !installed.packageName.equals(apk.packageName) || apk.getLongVersionCode()!=code ||
                !name.equals(apk.versionName) || code<=installed.getLongVersionCode() || apk.signingInfo==null)
            throw new IOException("APK paket adı veya sürümü uyuşmuyor.");
        Signature[] a=installed.signingInfo.getApkContentsSigners(), b=apk.signingInfo.getApkContentsSigners();
        if(a.length!=1||!Arrays.equals(a,b))throw new IOException("APK imzası yüklü uygulamayla eşleşmiyor.");
    }
}
