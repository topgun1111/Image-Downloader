package app.imagedownloader;

import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.util.*;
import java.util.concurrent.*;

final class SearchCancellation {
    private static final ThreadLocal<SearchCancellation> LOCAL=new ThreadLocal<>();
    private static final ExecutorService CLOSER=Executors.newCachedThreadPool(r->{
        Thread t=new Thread(r,"search-disconnect");t.setDaemon(true);return t;
    });
    private final Set<Thread> threads=new HashSet<>();
    private final Set<HttpURLConnection> connections=new HashSet<>();
    private volatile boolean cancelled;

    static SearchCancellation current(){return LOCAL.get();}
    boolean isCancelled(){return cancelled;}
    void enter()throws InterruptedIOException{
        synchronized(this){
            if(cancelled)throw new InterruptedIOException("Arama durduruldu.");
            LOCAL.set(this);threads.add(Thread.currentThread());
        }
    }
    void leave(){
        synchronized(this){
            threads.remove(Thread.currentThread());
            LOCAL.remove();
        }
    }
    static void check()throws InterruptedIOException{
        SearchCancellation s=current();
        if(Thread.currentThread().isInterrupted()||s!=null&&s.cancelled)
            throw new InterruptedIOException("Arama durduruldu.");
    }
    static void track(HttpURLConnection c)throws InterruptedIOException{
        SearchCancellation s=current();
        if(s==null){check();return;}
        synchronized(s){
            if(!s.cancelled&&!Thread.currentThread().isInterrupted()){s.connections.add(c);return;}
        }
        c.disconnect();throw new InterruptedIOException("Arama durduruldu.");
    }
    static void close(HttpURLConnection c){
        SearchCancellation s=current();
        if(s!=null)synchronized(s){s.connections.remove(c);}
        c.disconnect();
    }
    void cancel(){
        List<HttpURLConnection> active;
        synchronized(this){
            if(cancelled)return;
            cancelled=true;
            for(Thread t:threads)t.interrupt();
            active=new ArrayList<>(connections);
            connections.clear();
        }
        for(HttpURLConnection c:active)CLOSER.execute(()->{try{c.disconnect();}catch(RuntimeException ignored){}});
    }
}
