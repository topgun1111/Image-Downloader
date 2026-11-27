package app.imagedownloader;
import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class SearchLanguagesTest {
    @Test public void automaticPlan()throws Exception{
        assertEquals(Collections.singletonList("tr-TR"),SearchLanguages.plan(new JSONObject().put("q","Fenerbahçe")));
        assertTrue(SearchLanguages.plan(new JSONObject().put("q","Toca Boca")).contains("ja-JP"));
        assertFalse(SearchLanguages.restricted(new JSONObject().put("q","Toca Boca")));
    }
    @Test public void selectedAndCountry()throws Exception{
        JSONObject req=new JSONObject().put("languageMode","selected").put("languages",new JSONArray(Arrays.asList("ja","es","ja","invalid")));
        assertEquals(Arrays.asList("ja-JP","es-ES"),SearchLanguages.plan(req));
        assertEquals(SearchLanguages.CODES.length,SearchLanguages.plan(new JSONObject().put("languageMode","all")).size());
        assertEquals(SearchLanguages.CODES.length,SearchLanguages.COUNTRIES.length);
        assertTrue(SearchLanguages.countrySource(new JSONObject().put("pageUrl","https://www.fenerbahce.org/news"),"TR"));
        assertFalse(SearchLanguages.countrySource(new JSONObject().put("pageUrl","https://fenerbahce.org.example.com/news"),"TR"));
    }
    @Test public void translatedMetadata()throws Exception{
        JSONObject row=new JSONObject().put("title","gato bonito").put("localizedQuery","gato bonito");
        assertTrue(Relevance.admit("cute cat",row,"strict",null));
    }
}
