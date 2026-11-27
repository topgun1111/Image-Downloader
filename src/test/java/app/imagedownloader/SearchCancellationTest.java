package app.imagedownloader;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class SearchCancellationTest {
    @Test public void cancelsActiveWorkerAndDisconnects()throws Exception{
        SearchCancellation token=new SearchCancellation();
        CountDownLatch ready=new CountDownLatch(1),closed=new CountDownLatch(1);
        HttpURLConnection connection=new HttpURLConnection(new URL("https://example.org")){
            @Override public void disconnect(){closed.countDown();}
            @Override public boolean usingProxy(){return false;}
            @Override public void connect(){}
        };
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            Future<Boolean> f=worker.submit(()->{
                token.enter();
                try{
                    SearchCancellation.track(connection);
                    ready.countDown();
                    try{Thread.sleep(10000);return false;}catch(InterruptedException expected){}
                    try{SearchCancellation.check();return false;}catch(InterruptedIOException expected){return true;}
                }finally{token.leave();Thread.interrupted();}
            });
            assertTrue(ready.await(3,TimeUnit.SECONDS));
            token.cancel();
            assertTrue(f.get(3,TimeUnit.SECONDS));assertTrue(closed.await(3,TimeUnit.SECONDS));
            assertTrue(worker.submit(()->{SearchCancellation.check();return true;}).get(3,TimeUnit.SECONDS));
        }finally{worker.shutdownNow();}
    }
    @Test public void cancelledRequestCannotStart()throws Exception{
        SearchCancellation token=new SearchCancellation();token.cancel();
        try{token.enter();fail("Cancelled request started");}catch(InterruptedIOException expected){}
        assertNull(SearchCancellation.current());
    }
}
