package app.imagedownloader;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.util.*;
public class RelevanceTest {
    private JSONObject row(String title,String url)throws Exception{return new JSONObject().put("title",title).put("url",url);}
    @Test public void ronaldoFiltersUnrelatedImages()throws Exception{
        assertTrue(Relevance.admit("Ronaldo",row("Cristiano Ronaldo","https://example.org/player.jpg"),"strict",null));
        assertFalse(Relevance.admit("Ronaldo",row("River landscape","https://example.org/river.jpg"),"strict",.9));
        assertFalse(Relevance.admit("Ronaldo",row("Brazil flag","https://example.org/brazil.jpg"),"balanced",null));
        assertFalse(Relevance.admit("Ronaldo",row("Ronaldosaurus","https://example.org/a.jpg"),"strict",null));
    }
    @Test public void tokenCoverageAndPath()throws Exception{
        assertTrue(Relevance.admit("red apple",row("Fresh red apple","https://example.org/a.jpg"),"ultra",null));
        assertFalse(Relevance.admit("red apple",row("Red car","https://example.org/a.jpg"),"strict",null));
        assertTrue(Relevance.admit("Ronaldo",row("Photo","https://example.org/cristiano-ronaldo.jpg"),"strict",null));
        assertEquals("cilek",Relevance.norm("Çilek"));
    }
    @Test public void poorProviderDetection()throws Exception{
        Map<String,JSONObject> rows=new LinkedHashMap<>();
        rows.put("1",row("Ronaldo","https://example.org/1.jpg"));rows.put("2",row("river","https://example.org/2.jpg"));rows.put("3",row("car","https://example.org/3.jpg"));
        assertFalse(Relevance.useful("Ronaldo",rows));
    }
    @Test public void tokenizerPaddingAndBpe()throws Exception{
        JSONObject vocab=new JSONObject().put("<|startoftext|>",49406).put("<|endoftext|>",49407).put("!",0).put("a</w>",320);
        ClipTokenizer t=new ClipTokenizer(new JSONObject().put("model",new JSONObject().put("vocab",vocab).put("merges",new JSONArray())));
        long[] ids=t.encode("a",5);
        assertArrayEquals(new long[]{49406,320,49407,0,0},ids);
        assertArrayEquals(new long[]{1,1,1,0,0},t.mask(ids));
    }
}
