package app.imagedownloader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.webkit.WebResourceResponse;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Native recovery uses only registered URLs, public hosts and bounded memory. */
final class ThumbnailRecovery {
    private final Semaphore slots=new Semaphore(3);
    private final LruCache<String,byte[]> cache=new LruCache<String,byte[]>(8*1024*1024){
        @Override protected int sizeOf(String key,byte[] bytes){return bytes.length;}
    };
    void clear(){cache.evictAll();}
    private static WebResourceResponse failed(){return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",Collections.singletonMap("Cache-Control","no-store"),new ByteArrayInputStream(new byte[0]));}
    WebResourceResponse response(String[] item){
        if(item==null||item.length<8)return failed();
        byte[] bytes=cache.get(item[0]);
        if(bytes!=null)return image(bytes);
        if(!slots.tryAcquire())return failed();
        try{
            Set<String> urls=new LinkedHashSet<>(Arrays.asList(item[6],item[0],item[2]));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            for(String url:urls){
                try{
                    if(url.isEmpty()||System.nanoTime()>deadline)continue;
                    bytes=fetch(url,item[7],deadline,Boolean.parseBoolean(item[3]));
                    if(bytes!=null){cache.put(item[0],bytes);return image(bytes);}
                }catch(Exception ignored){}
            }
            return failed();
        }finally{slots.release();}
    }
    private static WebResourceResponse image(byte[] bytes){return new WebResourceResponse("image/png",null,new ByteArrayInputStream(bytes));}
    private static boolean publicHost(URL url)throws IOException{
        if(!Arrays.asList("http","https").contains(url.getProtocol())||url.getUserInfo()!=null||url.getPort()!=-1&&url.getPort()!=80&&url.getPort()!=443)return false;
        for(InetAddress a:InetAddress.getAllByName(url.getHost())){
            if(a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress())return false;
            byte[] b=a.getAddress();if(b.length==16&&(b[0]&0xfe)==0xfc)return false;
            if(b.length==4&&((b[0]&255)==0||(b[0]&255)==100&&(b[1]&0xc0)==64||(b[0]&255)>=224))return false;
        }
        return true;
    }
    private static byte[] fetch(String address,String page,long deadline,boolean clean)throws Exception{
        URL url=new URL(address);
        for(int redirects=0;redirects<3;redirects++){
            if(System.nanoTime()>deadline||!publicHost(url))return null;
            HttpURLConnection c=(HttpURLConnection)url.openConnection();
            c.setConnectTimeout(3500);c.setReadTimeout(4500);c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent",Search.WIKI_UA);c.setRequestProperty("Accept","image/avif,image/webp,image/png,image/jpeg,image/*");
            // Send only the public source origin, never its path, tokens or query string.
            try{URL source=new URL(page);if(publicHost(source)&&"https".equals(source.getProtocol())&&"https".equals(url.getProtocol()))c.setRequestProperty("Referer",source.getProtocol()+"://"+source.getHost()+"/");}catch(Exception ignored){}
            try{
                int status=c.getResponseCode();
                if(status>=300&&status<400){String location=c.getHeaderField("Location");if(location==null)return null;url=new URL(url,location);continue;}
                if(status!=200||c.getContentLengthLong()>6*1024*1024)return null;
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                try(InputStream in=c.getInputStream()){
                    byte[] buffer=new byte[8192];int n;
                    while((n=in.read(buffer))!=-1){if(out.size()+n>6*1024*1024||System.nanoTime()>deadline)return null;out.write(buffer,0,n);}
                }
                byte[] raw=out.toByteArray();BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;
                BitmapFactory.decodeByteArray(raw,0,raw.length,opts);
                if(opts.outWidth<=0||opts.outHeight<=0||opts.outWidth>30000||opts.outHeight>30000||clean&&(opts.outWidth<96||opts.outHeight<96))return null;
                opts.inSampleSize=1;
                while(Math.max(opts.outWidth,opts.outHeight)/opts.inSampleSize>1000&&Math.min(opts.outWidth,opts.outHeight)/(opts.inSampleSize*2)>=96)opts.inSampleSize*=2;
                if((long)(opts.outWidth/opts.inSampleSize)*(opts.outHeight/opts.inSampleSize)>4000000)return null;
                opts.inJustDecodeBounds=false;Bitmap bitmap=BitmapFactory.decodeByteArray(raw,0,raw.length,opts);
                if(bitmap==null)return null;
                try{ByteArrayOutputStream png=new ByteArrayOutputStream();return bitmap.compress(Bitmap.CompressFormat.PNG,100,png)?png.toByteArray():null;}finally{bitmap.recycle();}
            }finally{c.disconnect();}
        }
        return null;
    }
}
