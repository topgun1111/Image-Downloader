package app.imagedownloader;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class SearchTest {
    private static final String PIN="https://i.pinimg.com/originals/aa/bb/cc/0123456789abcdef0123456789abcdef.jpg";
    @Test public void originChecks() {
        assertTrue(Search.pinterestHost("//i.pinimg.com/originals/test.jpg"));
        assertTrue(Search.pinterestHost("https://www.pinterest.de/pin/123"));
        assertFalse(Search.pinterestHost("https://pinterest.com.evil.example/pin/123"));
        assertFalse(Search.pinterestHost("https://pinterest.com@evil.example/pin/123"));
    }
    @Test public void googleSourceLink() throws Exception {
        Map<String,JSONObject> rows=new LinkedHashMap<>();
        Search.parse("google","<a href='/imgres?imgurl=https%3A%2F%2Fexample.org%2Fimage.jpg&amp;imgrefurl=https%3A%2F%2Fwww.pinterest.de%2Fpin%2F123'><img src='https://example.org/thumb.jpg'></a>",rows,true);
        assertTrue(rows.containsKey("https://example.org/image.jpg"));
    }
    @Test public void googleEmbeddedPin() throws Exception {
        Map<String,JSONObject> rows=new LinkedHashMap<>();
        Search.parse("google","<script>var image=\""+PIN+"\"; var other=\"https://example.org/image.jpg\";</script>",rows,true);
        assertEquals(Collections.singleton(PIN),rows.keySet());
    }
    @Test public void yandexViewer() throws Exception {
        Map<String,JSONObject> rows=new LinkedHashMap<>();
        JSONObject result=new JSONObject().put("viewerData",new JSONObject().put("img_href",PIN).put("thumb",new JSONObject().put("url","//avatars.mds.yandex.net/i?id=1")));
        Search.yandex(result,rows,true);
        assertEquals("https://avatars.mds.yandex.net/i?id=1",rows.get(PIN).getString("thumb"));
    }
    @Test public void yandexLegacyAndRejection() throws Exception {
        Map<String,JSONObject> rows=new LinkedHashMap<>();
        JSONArray a=new JSONArray().put(new JSONObject().put("img_href","https://example.org/yes.jpg").put("snippet",new JSONObject().put("domain","www.pinterest.fr")))
            .put(new JSONObject().put("origUrl","https://example.org/no.jpg").put("snippet",new JSONObject().put("url","https://pinterest.com.evil.example/")));
        Search.yandex(a,rows,true);
        assertEquals(Collections.singleton("https://example.org/yes.jpg"),rows.keySet());
    }
    @Test public void bingStillWorks() throws Exception {
        Map<String,JSONObject> rows=new LinkedHashMap<>();
        Search.parse("bing","<a m='{&quot;murl&quot;:&quot;"+PIN+"&quot;,&quot;purl&quot;:&quot;https://pinterest.com/pin/123&quot;}'></a>",rows,true);
        assertTrue(rows.containsKey(PIN));
    }
}
