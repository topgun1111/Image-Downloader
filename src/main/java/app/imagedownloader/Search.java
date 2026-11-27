package app.imagedownloader;

import org.json.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

final class Search {
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    static final String WIKI_UA="ImageDownloaderAndroid/1.2 (https://github.com/topgun1111/Image-Downloader)";
    private static final Pattern GOOGLE_PAIR = Pattern.compile("\\[\\s*(\"https?[^\"]+\")\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*\\]\\s*,\\s*\\[\\s*(\"https?[^\"]+\")\\s*,\\s*\\d+\\s*,\\s*\\d+");
    // Only provider responses recorded here, not individual gallery/AI images.
    private static final Map<String,Long> hostLatency=new LinkedHashMap<String,Long>(32,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Long> e){return size()>64;}
    };
    static synchronized int searchTimeout(String host,boolean connect){
        Long ms=hostLatency.get(host);
        if(ms==null)return connect?5000:8000;
        return (int)(connect?Math.max(3500,Math.min(8000,ms*2+1500)):Math.max(5000,Math.min(12000,ms*3+2000)));
    }
    private static synchronized void recordLatency(String host,long elapsed){
        Long old=hostLatency.get(host);hostLatency.put(host,old==null?elapsed:(old*3+elapsed)/4);
    }
    static boolean web(String url) {
        try {
            URL u = new URL(url);
            return (u.getProtocol().equals("https") || u.getProtocol().equals("http")) && !u.getHost().isEmpty();
        } catch (Exception e) { return false; }
    }
    static String enc(String s) throws IOException {return URLEncoder.encode(s, "UTF-8");}
    static HttpURLConnection open(String url) throws IOException {
        SearchCancellation.check();
        if (!web(url)) throw new IOException("Geçersiz görsel adresi.");
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        String h=host(url);
        c.setRequestProperty("User-Agent",h.equals("wikimedia.org")||h.endsWith(".wikimedia.org")?WIKI_UA:UA);
        c.setRequestProperty("Accept-Language","tr-TR,tr;q=0.9");
        // Only search requests use the shorter initial network budget; downloads keep their existing timeout.
        boolean searching=SearchCancellation.current()!=null;
        c.setConnectTimeout(searching?searchTimeout(h,true):8000); c.setReadTimeout(searching?searchTimeout(h,false):12000);
        SearchCancellation.track(c);
        return c;
    }
    static String read(String url) throws IOException {
        long started=System.nanoTime();
        HttpURLConnection c = open(url);
        try (InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[16384]; int n;
            while ((n=in.read(b))!=-1) {
                SearchCancellation.check();
                if (out.size()+n>12000000) throw new IOException("Arama yanıtı çok büyük.");
                out.write(b,0,n);
            }
            if(SearchCancellation.current()!=null)recordLatency(host(url),Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)));
            return out.toString("UTF-8");
        } finally { SearchCancellation.close(c); }
    }
    static String normalize(String url) {
        if(url==null)return "";
        url=url.trim();
        return url.startsWith("//")?"https:"+url:url;
    }
    static String host(String url) {
        try{return new URL(normalize(url)).getHost().toLowerCase(Locale.ROOT);}
        catch(Exception e){return "";}
    }
    static String first(String... values) {
        for(String value:values)if(value!=null&&!value.trim().isEmpty())return value;
        return "";
    }
    static String field(JSONObject o,String key) {
        if(o==null)return "";
        Object value=o.opt(key);
        if(value instanceof String)return (String)value;
        if(value instanceof JSONObject)return ((JSONObject)value).optString("url");
        return "";
    }
    static String param(String href,String key) {
        int q=href.indexOf('?');if(q<0)return "";
        String query=href.substring(q+1).split("#",2)[0];
        for(String pair:query.split("&")){
            String[] parts=pair.split("=",2);
            try{if(parts.length==2&&URLDecoder.decode(parts[0],"UTF-8").equals(key))
                return URLDecoder.decode(parts[1],"UTF-8");}
            catch(IllegalArgumentException|UnsupportedEncodingException ignored){}
        }
        return "";
    }
    static void add(Map<String,JSONObject> rows,String url,String thumb,String title) throws JSONException {
        url=normalize(url);thumb=normalize(thumb);
        if (!web(url) || rows.containsKey(url)) return;
        rows.put(url,new JSONObject().put("url",url).put("thumb",web(thumb)?thumb:url)
                .put("title",title.isEmpty()?host(url):title));
    }
    private static final Set<String> PINTEREST_DOMAINS=new HashSet<>(Arrays.asList(
        "pinterest.com","pinimg.com","pinterest.co.uk","pinterest.de","pinterest.fr",
        "pinterest.ca","pinterest.com.au","pinterest.jp","pinterest.es","pinterest.it",
        "pinterest.pt","pinterest.ch","pinterest.at","pinterest.ie","pinterest.co.kr",
        "pinterest.com.mx","pinterest.com.br","pinterest.cl","pinterest.nz","pinterest.co.nz",
        "pinterest.ph","pinterest.se","pinterest.dk","pinterest.no","pinterest.fi","pinterest.nl",
        "pinterest.be","pinterest.com.tr","pinterest.ru","pinterest.co.in"));
    static boolean pinterestHost(String url) {
        String name=host(url);
        for(String domain:PINTEREST_DOMAINS)
            if(name.equals(domain)||name.endsWith("."+domain))return true;
        return false;
    }
    static boolean pinterestSource(String source) {
        return pinterestHost(source)||pinterestHost("https://"+source);
    }
    static void addResult(Map<String,JSONObject> rows, boolean pinterest, String pageUrl,
                          String original, String thumb, String title) throws JSONException {
        if (!pinterest || pinterestSource(pageUrl) || pinterestHost(original)) {
            add(rows,original,thumb,title);
            JSONObject row=rows.get(normalize(original));
            if(row!=null && web(pageUrl))row.put("pageUrl",pageUrl);
        }
    }
    static void yandex(Object node,Map<String,JSONObject> rows,boolean pinterest) throws JSONException {
        if (node instanceof JSONArray) {
            JSONArray a=(JSONArray)node;
            for (int i=0;i<a.length();i++) yandex(a.get(i),rows,pinterest);
        } else if (node instanceof JSONObject) {
            JSONObject o=(JSONObject)node;
            JSONObject viewer=o.optJSONObject("viewerData");
            String original=first(field(o,"origUrl"),field(o,"img_href"),field(viewer,"img_href"));
            if(!original.isEmpty()){
                JSONObject snippet=o.optJSONObject("snippet");
                if(snippet==null&&viewer!=null)snippet=viewer.optJSONObject("snippet");
                String page=first(field(snippet,"url"),field(snippet,"href"),field(o,"url"),
                    field(viewer,"url"),field(snippet,"domain"),field(o,"domain"));
                if(pinterest&&!pinterestSource(page)){
                    for(String candidate:new String[]{field(snippet,"domain"),field(o,"domain"),field(viewer,"domain")})
                        if(pinterestSource(candidate)){page=candidate;break;}
                }
                String thumb=first(field(o,"image"),field(o,"thumb"),field(viewer,"thumb"));
                String title=first(field(o,"alt"),field(o,"title"),field(snippet,"title"));
                addResult(rows,pinterest,page,original,thumb,title);
                JSONObject row=rows.get(normalize(original));
                if(row!=null)row.put("width",o.optInt("width",0)).put("height",o.optInt("height",0));
            } else for (Iterator<String> it=o.keys();it.hasNext();) yandex(o.get(it.next()),rows,pinterest);
        }
    }
    static String endpoint(String engine,String q,int page) throws IOException {
        String term=enc(q);
        switch(engine) {
            case "bing": return "https://www.bing.com/images/async?q="+term+"&first="+(page==0?0:page*35+1)+"&count=35";
            case "yandex": return "https://yandex.com/images/search?text="+term+(page==0?"":"&p="+page);
            case "google": return "https://www.google.com/search?udm=2&hl=tr&q="+term+"&start="+(page*20);
            default: throw new IOException("Geçersiz arama motoru.");
        }
    }
    static void parse(String engine,String html,Map<String,JSONObject> rows,boolean pinterest) throws JSONException {
        Document doc=Jsoup.parse(html);
        if (engine.equals("bing")) {
            for (Element e:doc.select("a[m]")) {
                try {
                    JSONObject o=new JSONObject(e.attr("m"));
                    addResult(rows,pinterest,o.optString("purl"),o.optString("murl"),o.optString("turl"),o.optString("t"));
                    JSONObject row=rows.get(normalize(o.optString("murl")));
                    if(row!=null)row.put("width",o.optInt("ow",o.optInt("w",0))).put("height",o.optInt("oh",o.optInt("h",0)));
                } catch(JSONException ignored) {}
            }
        } else if (engine.equals("yandex")) {
            for (String field:new String[]{"data-state","data-bem"})
                for (Element e:doc.select("["+field+"]"))
                    try { yandex(new JSONTokener(e.attr(field)).nextValue(),rows,pinterest); } catch(JSONException ignored) {}
        } else {
            for (Element a:doc.select("a[href*=imgurl],a[href*=mediaurl]")) {
                String href=a.attr("href");
                String orig=first(param(href,"imgurl"),param(href,"mediaurl"));
                Element img=a.selectFirst("img");
                if(!orig.isEmpty()) addResult(rows,pinterest,param(href,"imgrefurl"),orig,img==null?"":img.attr("src"),img==null?"":img.attr("alt"));
            }
            Matcher m=GOOGLE_PAIR.matcher(html);
            while(m.find()) try {
                String thumb=(String)new JSONTokener(m.group(1)).nextValue();
                if (host(thumb).endsWith(".gstatic.com")) addResult(rows,pinterest,"",(String)new JSONTokener(m.group(2)).nextValue(),thumb,"");
            } catch(JSONException ignored) {}
            // Google sometimes embeds original Pinterest URLs without a thumbnail pair.
            if(pinterest&&rows.isEmpty()){
                Pattern pins=Pattern.compile("https?://i\\.pinimg\\.com/(?:originals|[1-9][0-9]{2,3}x(?:[0-9]+)?)/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{16,64}\\.(?:jpe?g|png|webp|gif|avif)",Pattern.CASE_INSENSITIVE);
                for(Element script:doc.select("script")){
                    String data=script.data().replace("\\/","/").replace("\\u002f","/").replace("\\u002F","/").replace("\\x2f","/").replace("\\x2F","/");
                    Matcher pin=pins.matcher(data);
                    while(pin.find()&&rows.size()<150)add(rows,pin.group(),pin.group(),"Pinterest");
                }
            }
        }
    }
    static JSONObject run(JSONObject req) throws Exception {
        String engine=req.getString("engine"),q=req.getString("q").trim();
        int page=req.optInt("page",0);
        if(q.isEmpty()||q.length()>256||page<0||page>10000) throw new IOException("Geçersiz arama.");
        boolean pinterest=req.optBoolean("pinterest",false);
        String term=q;
        if (pinterest) {
            // Bing Images ignores site: for some queries. Verify the origin of each result below.
            term=engine.equals("bing")?q+" Pinterest":q+" site:pinterest.com";
        }
        String url=endpoint(engine,term,page), key=req.optString("key");
        Map<String,JSONObject> rows=new LinkedHashMap<>();
        String source=engine.equals("bing")?"Bing Görseller":engine.equals("yandex")?"Yandex Görsel":"Google Görseller";
        if(pinterest) source+=" · Pinterest";
        String notice="";
        boolean more=!engine.equals("google");
        try {
            if(engine.equals("google")&&!key.isEmpty()){
                JSONObject data=new JSONObject(read("https://serpapi.com/search.json?engine=google_images&ijn="+page+"&hl=tr&gl=tr&q="+enc(term)+"&api_key="+enc(key)));
                if(data.has("error")) throw new IOException("SerpApi anahtarını ve kotayı kontrol edin.");
                JSONArray a=data.optJSONArray("images_results");
                if(a!=null) for(int i=0;i<a.length();i++){
                    JSONObject o=a.getJSONObject(i);addResult(rows,pinterest,o.optString("link"),o.optString("original"),o.optString("thumbnail"),o.optString("title"));
                }
            } else {
                String html=read(url);
                parse(engine,html,rows,pinterest);
                if(rows.isEmpty() && blocked(html))
                    throw new IOException("Arama motoru doğrulama istedi. Daha sonra tekrar deneyin.");
                if(pinterest&&rows.isEmpty()&&!engine.equals("bing")){
                    // Stay with the selected engine; origin validation still applies.
                    String retry=read(endpoint(engine,q+" Pinterest",page));
                    parse(engine,retry,rows,true);
                    if(rows.isEmpty()&&blocked(retry))
                        throw new IOException("Arama motoru doğrulama istedi. Daha sonra tekrar deneyin.");
                }
            }
            if(page==0 && rows.isEmpty()) throw new IOException(pinterest?"Doğrulanmış Pinterest görseli bulunamadı.":"Görsel bağlantısı bulunamadı.");
        } catch(Exception err) {
            throw new IOException(source+" araması tamamlanamadı. "+safeError(err));

        }
        return new JSONObject().put("rows",new JSONArray(rows.values())).put("source",source)
                .put("notice",notice).put("more",more&&!rows.isEmpty());
    }
    static boolean blocked(String html) {
        Document doc=Jsoup.parse(html);
        return !doc.select("form[action*=captcha],form[action*='/sorry/'],iframe[src*=captcha],.captcha-container,.CheckboxCaptcha,.AdvancedCaptcha,#checkbox-captcha-form").isEmpty()
            || html.contains("showcaptcha")&&!html.contains("origUrl");
    }
    static String safeError(Exception e) {
        if(e instanceof SocketTimeoutException) return "Bağlantı zaman aşımına uğradı.";
        if(e instanceof UnknownHostException) return "İnternet bağlantısını kontrol edin.";
        String msg=e.getMessage();
        if(msg==null||msg.contains("://")||msg.length()>160) return "Kaynak yanıt vermedi.";
        return msg;
    }
}
