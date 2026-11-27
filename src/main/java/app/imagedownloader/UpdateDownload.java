package app.imagedownloader;

import android.app.DownloadManager;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Environment;
import java.io.*;
import java.util.UUID;

/** Android owns the transfer; the preferences contain enough information to reattach. */
final class UpdateDownload {
    private static final String PREF="persistent-apk-update";
    private static final String TYPE=Environment.DIRECTORY_DOWNLOADS;
    private final Context ctx;
    private final SharedPreferences prefs;
    private final DownloadManager manager;
    UpdateDownload(Context context){
        ctx=context.getApplicationContext();
        prefs=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        manager=(DownloadManager)ctx.getSystemService(Context.DOWNLOAD_SERVICE);
    }
    static final class Job {
        final long id,size,code,transferSize;
        final String name,digest,file,transferDigest,base,apkUrl;
        final boolean patch,metered;
        Job(long id,long size,long code,String name,String digest,String file,long transferSize,String transferDigest,String base,boolean metered,String apkUrl){
            this.id=id;this.size=size;this.code=code;this.name=name;this.digest=digest;this.file=file;
            this.transferSize=transferSize;this.transferDigest=transferDigest;this.base=base;this.patch=!base.isEmpty();this.metered=metered;this.apkUrl=apkUrl;
        }
    }
    static final class Snapshot {
        final int status,reason,percent;
        Snapshot(int status,int reason,int percent){this.status=status;this.reason=reason;this.percent=percent;}
    }
    Job current(){
        long id=prefs.getLong("id",-1);
        String file=prefs.getString("file","");
        if(id<0||!file.matches("updates/update-[0-9]+-[a-f0-9-]+[.]part"))return null;
        long size=prefs.getLong("size",0);String digest=prefs.getString("digest","");
        return new Job(id,size,prefs.getLong("code",0),prefs.getString("name",""),digest,file,
            prefs.getLong("transferSize",size),prefs.getString("transferDigest",digest),prefs.getString("base",""),prefs.getBoolean("metered",true),prefs.getString("apkUrl",ReleaseClient.DOWNLOAD+"v"+prefs.getString("name","")+"/Image-Downloader.apk"));
    }
    File file(Job job)throws IOException{
        File dir=ctx.getExternalFilesDir(TYPE);
        if(dir==null)throw new IOException("İndirme depolaması kullanılamıyor.");
        return new File(dir,job.file);
    }
    static boolean unmeteredWifi(Context ctx){
        ConnectivityManager cm=(ConnectivityManager)ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)return false;
        Network network=cm.getActiveNetwork();NetworkCapabilities caps=network==null?null:cm.getNetworkCapabilities(network);
        return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            &&!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
    }
    static File installedApk(Context ctx)throws IOException{
        if(ctx.getApplicationInfo().splitSourceDirs!=null&&ctx.getApplicationInfo().splitSourceDirs.length>0)
            throw new IOException("Bölünmüş APK için yama kullanılamaz.");
        return new File(ctx.getApplicationInfo().sourceDir);
    }
    synchronized Job start(ReleaseClient.Release release,ReleaseClient.Patch patch,boolean metered)throws Exception{
        return enqueue(release.code,release.name,release.size,release.digest,
            patch==null?release.downloadUrl:patch.url,patch==null?release.size:patch.size,
            patch==null?release.digest:patch.digest,patch==null?"":patch.base,metered,release.downloadUrl);
    }
    synchronized Job fallback(Job old)throws Exception{
        if(!old.patch)throw new IOException("Tam APK zaten seçili.");
        clear(old);
        return enqueue(old.code,old.name,old.size,old.digest,old.apkUrl,
            old.size,old.digest,"",false,old.apkUrl);
    }
    private Job enqueue(long code,String name,long size,String digest,String url,long transferSize,String transferDigest,String base,boolean metered,String apkUrl)throws Exception{
        if(!(apkUrl.equals(ReleaseClient.DOWNLOAD+"v"+name+"/Image-Downloader.apk")||apkUrl.equals(ReleaseClient.DOWNLOAD+"v"+name+"/Image-Downloader-arm64-v8a.apk")||apkUrl.equals(ReleaseClient.DOWNLOAD+"v"+name+"/Image-Downloader-arm64-lite.apk"))||!name.matches("[0-9]+[.][0-9]+[.][0-9]+-[0-9a-f]{8,40}")||size<=0||size>ApkDelta.MAX||
                transferSize<=0||transferSize>size||!digest.matches("sha256:[a-fA-F0-9]{64}")||
                !transferDigest.matches("sha256:[a-fA-F0-9]{64}"))throw new IOException("Geçersiz güncelleme bilgisi.");
        Job old=current();
        if(old!=null)throw new IOException("Önce mevcut güncellemeyi tamamlayın veya iptal edin.");
        if(manager==null)throw new IOException("Android indirme hizmeti kullanılamıyor.");
        File root=ctx.getExternalFilesDir(TYPE);
        if(root==null)throw new IOException("İndirme depolaması kullanılamıyor.");
        File dir=new File(root,"updates");
        if(!dir.isDirectory()&&!dir.mkdirs()&&!dir.isDirectory())throw new IOException("İndirme klasörü açılamadı.");
        String path="updates/update-"+code+"-"+UUID.randomUUID()+".part";
        DownloadManager.Request req=new DownloadManager.Request(Uri.parse(url));
        req.setTitle("Image Downloader · Güncelleme");
        req.setDescription(name+(base.isEmpty()?" · Tam APK":" · Kısmi güncelleme")+" · Uygulamaya dönerek kurulumu tamamlayın.");
        req.setMimeType("application/octet-stream");
        req.addRequestHeader("User-Agent","Image-Downloader-Android");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setAllowedOverMetered(metered);
        req.setAllowedOverRoaming(false);
        req.setDestinationInExternalFilesDir(ctx,TYPE,path);
        long id=manager.enqueue(req);
        if(id<0)throw new IOException("İndirme başlatılamadı.");
        if(!prefs.edit().putLong("id",id).putLong("size",size).putLong("code",code)
                .putString("name",name).putString("digest",digest).putString("file",path)
                .putLong("transferSize",transferSize).putString("transferDigest",transferDigest)
                .putString("base",base).putString("apkUrl",apkUrl).putBoolean("metered",metered).commit()){
            manager.remove(id);throw new IOException("Güncelleme durumu kaydedilemedi.");
        }
        return current();
    }
    Snapshot snapshot(Job job){
        if(manager==null)return new Snapshot(DownloadManager.STATUS_FAILED,0,0);
        try(Cursor cursor=manager.query(new DownloadManager.Query().setFilterById(job.id))){
            if(cursor==null||!cursor.moveToFirst())return new Snapshot(DownloadManager.STATUS_FAILED,0,0);
            long bytes=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            int status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            int reason=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            int percent=job.transferSize<=0?0:(int)Math.max(0,Math.min(100,bytes*100/job.transferSize));
            return new Snapshot(status,reason,percent);
        }
    }
    synchronized void clear(Job job){
        Job active=current();
        if(active==null||active.id!=job.id)return;
        prefs.edit().clear().commit();
        try{if(manager!=null)manager.remove(job.id);}catch(RuntimeException ignored){}
        try{file(job).delete();}catch(IOException ignored){}
        // The verified private APK may still be held by Android's installer. It is
        // removed on explicit cancel/replacement or after detecting the installed update.
        new File(ctx.getCacheDir(),"updates/update-"+job.code+".apk").delete();
    }
    File verify(Job job)throws Exception{
        File dir=ReleaseClient.directory(ctx);
        File dest=new File(dir,"update-"+job.code+".apk");
        File temp=File.createTempFile("verify-",".part",dir);
        try{
            File src=file(job);
            // Never expose the system-downloaded unverified file to the package installer.
            if(job.patch){
                if(src.length()!=job.transferSize||!job.transferDigest.equalsIgnoreCase(ApkDelta.sha256(src)))throw new IOException("Yama özeti uyuşmuyor.");
                File base=installedApk(ctx);
                if(!job.base.equals(ApkDelta.sha256(base)))throw new IOException("Yama kaynak APK ile eşleşmiyor.");
                ApkDelta.apply(base,src,temp,job.size);
            }else try(InputStream in=new FileInputStream(src);OutputStream out=new FileOutputStream(temp)){
                byte[] buf=new byte[65536];long bytes=0;int n;
                while((n=in.read(buf))!=-1){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                    bytes+=n;if(bytes>job.size)throw new IOException("APK boyutu uyuşmuyor.");
                    out.write(buf,0,n);
                }
            }
            ReleaseClient.verifyDownloaded(ctx,temp,job.size,job.digest,job.code,job.name);
            java.nio.file.Files.move(temp.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return dest;
        }finally{temp.delete();}
    }
}
