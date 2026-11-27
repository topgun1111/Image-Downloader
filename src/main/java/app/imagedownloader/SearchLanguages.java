package app.imagedownloader;
import org.json.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.nl.languageid.*;
import com.google.mlkit.nl.translate.*;
import com.google.mlkit.common.model.DownloadConditions;

final class SearchLanguages {
    static final String[] CODES="en tr es pt ru de fr it ja zh ko ar hi nl pl id vi th uk fa sv da fi no el cs ro hu he ms bn ta te ur bg hr sk sl sr lt lv et ca af sq be cy eo ga gl ht is ka mk mt sw tl".split(" ");
    static final String[] COUNTRIES="US TR ES BR RU DE FR IT JP CN KR SA IN NL PL ID VN TH UA IR SE DK FI NO GR CZ RO HU IL MY BD IN IN PK BG HR SK SI RS LT LV EE ES ZA AL BY GB US IE ES HT IS GE MK MT KE PH".split(" ");
    private static final ThreadLocal<String> ACTIVE=new ThreadLocal<>();
    private static final Map<String,String> cache=new LinkedHashMap<String,String>(32,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,String> e){return size()>256;}
    };
    static String active(){String s=ACTIVE.get();return s==null?"en-US":s;}
    static void enter(String locale){ACTIVE.set(locale);}
    static void leave(){ACTIVE.remove();}
    static String language(){return active().split("-")[0];}
    static String country(){return active().split("-")[1];}
    static String locale(String code){int i=Arrays.asList(CODES).indexOf(code);return i<0?"":code+"-"+COUNTRIES[i];}
    static boolean turkey(String query){
        return Relevance.norm(query).matches(".*\\b(fenerbahce|galatasaray|besiktas|trabzonspor|bursaspor|konyaspor|sivasspor|goztepe|samsunspor|super lig|tff|turkiye milli takimi|istanbul tarihi|mebbis)\\b.*");
    }
    static List<String> plan(JSONObject req){
        String q=req.optString("q"),mode=req.optString("languageMode","auto");
        LinkedHashSet<String> out=new LinkedHashSet<>();
        if(mode.equals("local")){
            String code=req.optString("localLanguage","tr"),country=req.optString("localCountry","TR");
            if(!locale(code).isEmpty()&&country.matches("[A-Z]{2}"))out.add(code+"-"+country);
        }else if(mode.equals("selected")){
            JSONArray chosen=req.optJSONArray("languages");
            if(chosen!=null)for(int i=0;i<chosen.length();i++){String s=locale(chosen.optString(i));if(!s.isEmpty())out.add(s);}
        }else if(mode.equals("all")){for(String code:CODES)out.add(locale(code));}
        else if(turkey(q))out.add("tr-TR");
        else{
            String[] codes=DeepDiscovery.toca(q)?new String[]{"en","es","pt","ru","tr","de","fr","ja","zh","ko","ar","id","pl","it"}:
                Relevance.norm(q).contains("anime")?new String[]{"ja","en","zh","ko","es","ru","tr","fr"}:
                new String[]{"en","tr","es","pt","de","fr","ru","ja","zh","ko","ar","hi"};
            for(String code:codes)out.add(locale(code));
        }
        if(out.isEmpty())out.add("en-US");
        return new ArrayList<>(out);
    }
    static boolean restricted(JSONObject req){return req.optString("languageMode","auto").equals("local")||
        req.optString("languageMode","auto").equals("auto")&&turkey(req.optString("q"));}
    static boolean countrySource(JSONObject row,String country){
        String cc=country.toLowerCase(Locale.ROOT);
        for(String key:new String[]{"pageUrl","url","fallback"}){
            String host=Search.host(row.optString(key)).toLowerCase(Locale.ROOT);
            if(host.endsWith("."+cc)||country.equals("TR")&&Arrays.asList("fenerbahce.org","www.fenerbahce.org","galatasaray.org","www.galatasaray.org","tff.org","www.tff.org").contains(host))return true;
        }
        return false;
    }
    static String translate(String query,String target,boolean wifi){
        String key=query+"\n"+target;
        synchronized(cache){if(cache.containsKey(key))return cache.get(key);}
        String prefix="",body=query;
        java.util.regex.Matcher brand=java.util.regex.Pattern.compile("(?i)^(toca\\s+(?:boca|life)(?:\\s+world)?|tocaboca|disney|minecraft|roblox)\\b\\s*").matcher(query);
        if(brand.find()){prefix=brand.group(1);body=query.substring(brand.end()).trim();}
        if(body.isEmpty()||Relevance.strictName(query)||turkey(query))return query;
        LanguageIdentifier identifier=null;Translator translator=null;
        try{
            identifier=LanguageIdentification.getClient();
            String source=Tasks.await(identifier.identifyLanguage(body),2,TimeUnit.SECONDS);
            String from=TranslateLanguage.fromLanguageTag(source),to=TranslateLanguage.fromLanguageTag(target);
            if(from==null||to==null||from.equals(to))return query;
            translator=Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build());
            DownloadConditions.Builder conditions=new DownloadConditions.Builder();
            if(wifi)conditions.requireWifi();
            Tasks.await(translator.downloadModelIfNeeded(conditions.build()),5,TimeUnit.SECONDS);
            String translated=Tasks.await(translator.translate(body),3,TimeUnit.SECONDS);
            if(translated==null||translated.trim().isEmpty())return query;
            String result=prefix.isEmpty()?translated:prefix+" "+translated;
            if(result.length()>256)return query;
            synchronized(cache){cache.put(key,result);}
            return result;
        }catch(InterruptedException e){Thread.currentThread().interrupt();return query;}
        catch(Exception|LinkageError e){return query;}
        finally{if(identifier!=null)identifier.close();if(translator!=null)translator.close();}
    }
    static String googleParams(){return "&hl="+language()+"&gl="+country().toLowerCase(Locale.ROOT);}
    static String bingParams(){return "&mkt="+active()+"&setlang="+language()+"&cc="+country();}
    static String yandexParams(){return "&lang="+language();}
}
