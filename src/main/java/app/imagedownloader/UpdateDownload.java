package app.imagedownloader;

import android.app.DownloadManager;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
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
        final long id,size,code;
        final String name,digest,file;
        Job(long id,long size,long code,String name,String digest,String file){
            this.id=id;this.size=size;this.code=code;this.name=name;this.digest=digest;this.file=file;
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
        return new Job(id,prefs.getLong("size",0),prefs.getLong("code",0),prefs.getString("name",""),
            prefs.getString("digest",""),file);
    }
    File file(Job job)throws IOException{
        File dir=ctx.getExternalFilesDir(TYPE);
        if(dir==null)throw new IOException("İndirme depolaması kullanılamıyor.");
        return new File(dir,job.file);
    }
    synchronized Job start(ReleaseClient.Release release)throws Exception{
        Job old=current();
        if(old!=null)throw new IOException("Önce mevcut güncellemeyi tamamlayın veya iptal edin.");
        if(manager==null)throw new IOException("Android indirme hizmeti kullanılamıyor.");
        File root=ctx.getExternalFilesDir(TYPE);
        if(root==null)throw new IOException("İndirme depolaması kullanılamıyor.");
        File dir=new File(root,"updates");
        if(!dir.isDirectory()&&!dir.mkdirs()&&!dir.isDirectory())throw new IOException("İndirme klasörü açılamadı.");
        String path="updates/update-"+release.code+"-"+UUID.randomUUID()+".part";
        DownloadManager.Request req=new DownloadManager.Request(Uri.parse(release.downloadUrl));
        req.setTitle("Image Downloader · Güncelleme");
        req.setDescription(release.name+" · Uygulamaya dönerek kurulumu tamamlayın.");
        req.setMimeType("application/octet-stream");
        req.addRequestHeader("User-Agent","Image-Downloader-Android");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setAllowedOverMetered(true);
        req.setAllowedOverRoaming(false);
        req.setDestinationInExternalFilesDir(ctx,TYPE,path);
        long id=manager.enqueue(req);
        if(id<0)throw new IOException("İndirme başlatılamadı.");
        if(!prefs.edit().putLong("id",id).putLong("size",release.size).putLong("code",release.code)
                .putString("name",release.name).putString("digest",release.digest).putString("file",path).commit()){
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
            int percent=job.size<=0?0:(int)Math.max(0,Math.min(100,bytes*100/job.size));
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
            try(InputStream in=new FileInputStream(src);OutputStream out=new FileOutputStream(temp)){
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
