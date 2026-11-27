package app.imagedownloader;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import org.json.*;
import org.jsoup.Jsoup;
public class DeepDiscoveryTest {
 @Test public void tocaAliasesAndVariants()throws Exception{
  assertTrue(DeepDiscovery.toca("Toca Boca"));assertTrue(DeepDiscovery.toca("tocaboca"));
  assertTrue(Relevance.score("Toca Boca",new JSONObject().put("title","Toca Life World gallery"))>=.9);
  assertFalse(Relevance.strictName("Toca Boca"));
  assertTrue(Arrays.asList(DeepDiscovery.variants("Toca Boca")).contains("Toca Boca coloring pages"));
 }
 @Test public void extractionUsesLargestSourceAndRelevantPage()throws Exception{
  String html="<main><h1>Toca Life World Gallery</h1><img alt='Character artwork' src='https://example.org/tiny.jpg' srcset='https://example.org/m.jpg 300w, https://example.org/full.jpg 1200w'><img src='https://example.org/favicon.ico'></main>";
  Map<String,JSONObject> rows=new LinkedHashMap<>();
  DeepDiscovery.extract("deepweb","Toca Boca",Jsoup.parse(html,"https://example.org/toca-gallery"),rows,true);
  assertTrue(rows.containsKey("https://example.org/full.jpg"));assertEquals(1,rows.size());
  rows.clear();DeepDiscovery.extract("deepweb","Ronaldo",Jsoup.parse(html,"https://example.org/toca-gallery"),rows,true);assertTrue(rows.isEmpty());
 }
 @Test public void robotRules(){
  assertFalse(DeepDiscovery.robotsAllows("User-agent: *\nDisallow: /private","/private/pic"));
  assertTrue(DeepDiscovery.robotsAllows("User-agent: *\nDisallow: /private\nAllow: /private/public","/private/public/page"));
  assertFalse(DeepDiscovery.robotsAllows("User-agent: *\nDisallow: /*.jpg$","/test.jpg"));
 }
 @Test public void perProviderCursorsRetryAndStop()throws Exception{
  JSONObject old=new JSONObject();JSONArray next=new JSONArray();JSONObject state=new JSONObject();
  MultiSearch.advance("bing",null,old,state,next,0,"cat");
  assertEquals(0,state.getJSONObject("bing").getInt("page"));
  assertEquals(1,state.getJSONObject("bing").getInt("fails"));
  old=state;state=new JSONObject();next=new JSONArray();
  MultiSearch.advance("bing",Collections.emptyMap(),old,state,next,1,"cat");
  assertEquals(1,state.getJSONObject("bing").getInt("page"));
  old=state;state=new JSONObject();next=new JSONArray();
  MultiSearch.advance("bing",Collections.emptyMap(),old,state,next,2,"cat");assertEquals(0,next.length());
 }
}
