package app.imagedownloader;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import org.json.*;
public class MultiSearchTest {
    private static final String PIN="https://i.pinimg.com/236x/aa/bb/cc/0123456789abcdef0123456789abcdef.jpg";
    @Test public void filtersJunkAndRejectsTinyDimensions() throws Exception {
        assertTrue(MultiSearch.junk("https://example.com/assets/placeholder.png"));
        assertTrue(MultiSearch.junk("https://example.com/favicon.ico"));
        assertTrue(MultiSearch.junk("data:image/png;base64,abc"));
        assertFalse(MultiSearch.junk("https://example.com/pictures/landscape.jpg"));
        Map<String,JSONObject> rows=new LinkedHashMap<>();
        MultiSearch.add(rows,"google","https://example.com/picture.jpg","","tiny","",32,32,true);
        assertTrue(rows.isEmpty());
        MultiSearch.add(rows,"google","https://example.com/picture.jpg","","tiny","",32,32,false);
        assertEquals(1,rows.size());
    }
    @Test public void originalPinAndFallback() throws Exception {
        Map<String,JSONObject> rows=new LinkedHashMap<>();
        MultiSearch.add(rows,"pinterest",PIN,PIN,"Pin","",0,0,true);
        String original=PIN.replace("/236x/","/originals/");
        assertTrue(rows.containsKey(original));
        assertEquals(PIN,rows.get(original).getString("fallback"));
        MultiSearch.add(rows,"pinterest",original,original,"Pin","",0,0,true);
        assertEquals(1,rows.size());
    }
    @Test public void publicPageUsesLargestSrcsetAndRejectsForeignHosts() throws Exception {
        Map<String,JSONObject> rows=new LinkedHashMap<>();
        String html="<main><img src='https://images.pexels.com/a.jpg' srcset='https://images.pexels.com/b.jpg 300w, https://images.pexels.com/c.jpg 1200w' alt='photo'><img src='https://bad.example/logo.jpg'></main>";
        MultiSearch.webRows("pexels",html,"https://www.pexels.com/search/cat/",rows,true);
        assertEquals(Collections.singleton("https://images.pexels.com/c.jpg"),rows.keySet());
        assertFalse(MultiSearch.onDomain("https://pexels.com.evil.example/","pexels.com"));
    }
    @Test public void openversePreservesAttributionAndDimensions() throws Exception {
        JSONObject image=new JSONObject().put("url","https://example.org/full.jpg").put("thumbnail","https://example.org/thumb.jpg").put("width",1200).put("height",800).put("license","cc-by").put("creator","Author");
        Map<String,JSONObject> rows=new LinkedHashMap<>();
        MultiSearch.apiRows("openverse",new JSONObject().put("results",new JSONArray().put(image)),rows,true);
        assertEquals(1200,rows.values().iterator().next().getInt("width"));
        assertEquals("cc-by",rows.values().iterator().next().getString("license"));
    }
    @Test public void excludesWikimediaAndCachesIndependentCopies() throws Exception {
        assertFalse(Arrays.asList(MultiSearch.IDS).contains("commons"));
        assertFalse(Arrays.asList(MultiSearch.IDS).contains("wikimedia"));
        assertTrue(MultiSearch.excludedSource(new JSONObject().put("url","https://upload.wikimedia.org/a.jpg")));
        assertTrue(MultiSearch.excludedSource(new JSONObject().put("pageUrl","https://en.wikipedia.org/wiki/Cat")));
        assertFalse(MultiSearch.excludedSource(new JSONObject().put("url","https://wikimedia.org.example.com/a.jpg")));
        Map<String,JSONObject> rows=new LinkedHashMap<>();
        rows.put("https://example.org/a.jpg",new JSONObject().put("url","https://example.org/a.jpg").put("title","Cat"));
        MultiSearch.remember("test-cache",rows);
        MultiSearch.cached("test-cache").values().iterator().next().put("title","Changed");
        assertEquals("Cat",MultiSearch.cached("test-cache").values().iterator().next().getString("title"));
    }
    @Test public void noProvidersIsInvalid() throws Exception {
        try { MultiSearch.run(new JSONObject().put("q","cat").put("providers",new JSONArray())); fail("Empty providers accepted"); }
        catch(java.io.IOException expected){}
    }
    @Test public void commonsOnlyRealImageFiles() throws Exception {
        String root="https://upload.wikimedia.org/wikipedia/commons/";
        JSONObject info=new JSONObject().put("url",root+"a/ab/Test.jpg").put("thumburl",root+"thumb/a/ab/Test.jpg/330px-Test.jpg").put("mime","image/jpeg").put("width",1000).put("height",800);
        JSONObject pages=new JSONObject().put("1",new JSONObject().put("ns",6).put("title","File:Test.jpg").put("imageinfo",new JSONArray().put(info)))
            .put("2",new JSONObject().put("ns",14).put("title","Category:Test").put("imageinfo",new JSONArray().put(info)));
        Map<String,JSONObject> rows=new LinkedHashMap<>();
        MultiSearch.commonsRows(new JSONObject().put("query",new JSONObject().put("pages",pages)),rows,true);
        assertEquals(1,rows.size());
        assertEquals("Test.jpg",rows.values().iterator().next().getString("title"));
        assertFalse(MultiSearch.commonsIndexedFile(new JSONObject().put("url",root+"a/ab/Test.jpg").put("title","Category:Test")));
        assertFalse(MultiSearch.wikiMedia("https://upload.wikimedia.org.evil.example/a.jpg"));
    }
}
