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
    private static final Pattern GOOGLE_PAIR = Pattern.compile("\\[\\s*(\"https?[^\"]+\")\\s*,\\s*\\d+\\s*,\\s*\\d+\\s*\\]\\s*,\\s*\\[\\s*(\"https?[^\"]+\")\\s*,\\s*\\d+\\s*,\\s*\\d+");
    static boolean web(String url) {
        try {
            URL u = new URL(url);
            return (u.getProtocol().equals("https") || u.getProtocol().equals("http")) && !u.getHost().isEmpty();
        } catch (Exception e) { return false; }
    }
    static String enc(String s) throws IOException {return URLEncoder.encode(s, "UTF-8");}
    static HttpURLConnection open(String url) throws IOException {
        if (!web(url)) throw new IOException("Geçersiz görsel adresi.");
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setRequestProperty("User-Agent",UA);
        c.setRequestProperty("Accept-Language","tr-TR,tr;q=0.9");
        c.setConnectTimeout(15000); c.setReadTimeout(20000);
        return c;
    }
    static String read(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[16384]; int n;
            while ((n=in.read(b))!=-1) {
                if (out.size()+n>12000000) throw new IOException("Arama yanıtı çok büyük.");
                out.write(b,0,n);
            }
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }
    static void add(Map<String,JSONObject> rows,String url,String thumb,String title) throws JSONException {
        if (!web(url) || rows.containsKey(url)) return;
        if (thumb.startsWith("//")) thumb="https:"+thumb;
        rows.put(url,new JSONObject().put("url",url).put("thumb",web(thumb)?thumb:url)
                .put("title",title.isEmpty()?android.net.Uri.parse(url).getHost():title));
    }
    static boolean pinterestHost(String url) {
        if (url==null || !web(url)) return false;
        try {
            String host=new URL(url).getHost().toLowerCase(Locale.ROOT);
            return host.equals("pinterest.com") || host.endsWith(".pinterest.com")
                    || host.equals("pinimg.com") || host.endsWith(".pinimg.com");
        } catch (MalformedURLException e) { return false; }
    }
    static void addResult(Map<String,JSONObject> rows, boolean pinterest, String pageUrl,
                          String original, String thumb, String title) throws JSONException {
        if (!pinterest || pinterestHost(pageUrl) || pinterestHost(original)) add(rows,original,thumb,title);
    }
    static void yandex(Object node,Map<String,JSONObject> rows,boolean pinterest) throws JSONException {
        if (node instanceof JSONArray) {
            JSONArray a=(JSONArray)node;
            for (int i=0;i<a.length();i++) yandex(a.get(i),rows,pinterest);
        } else if (node instanceof JSONObject) {
            JSONObject o=(JSONObject)node;
            if (o.has("origUrl")) {
                JSONObject snippet=o.optJSONObject("snippet");
                addResult(rows,pinterest,snippet==null?"":snippet.optString("url"),o.optString("origUrl"),o.optString("image"),o.optString("alt"));
            }
            else for (Iterator<String> it=o.keys();it.hasNext();) yandex(o.get(it.next()),rows,pinterest);
        }
    }
    static String endpoint(String engine,String q,int page) throws IOException {
        String term=enc(q);
        switch(engine) {
            case "bing": return "https://www.bing.com/images/async?q="+term+"&first="+(page==0?0:page*35+1)+"&count=35";
            case "yandex": return "https://yandex.com/images/search?text="+term+(page==0?"":"&p="+page);
            case "google": return "https://www.google.com/search?udm=2&hl=tr&q="+term;
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
                } catch(JSONException ignored) {}
            }
        } else if (engine.equals("yandex")) {
            for (String field:new String[]{"data-state","data-bem"})
                for (Element e:doc.select("["+field+"]"))
                    try { yandex(new JSONTokener(e.attr(field)).nextValue(),rows,pinterest); } catch(JSONException ignored) {}
        } else {
            for (Element a:doc.select("a[href*=imgurl],a[href*=mediaurl]")) {
                android.net.Uri u=android.net.Uri.parse(a.attr("href"));
                String orig=u.getQueryParameter("imgurl");
                if (orig==null) orig=u.getQueryParameter("mediaurl");
                Element img=a.selectFirst("img");
                if(orig!=null) addResult(rows,pinterest,u.getQueryParameter("imgrefurl"),orig,img==null?"":img.attr("src"),img==null?"":img.attr("alt"));
            }
            Matcher m=GOOGLE_PAIR.matcher(html);
            while(m.find()) try {
                String thumb=(String)new JSONTokener(m.group(1)).nextValue();
                if (thumb.contains(".gstatic.com/")) addResult(rows,pinterest,"",(String)new JSONTokener(m.group(2)).nextValue(),thumb,"");
            } catch(JSONException ignored) {}
        }
    }
    static JSONObject run(JSONObject req) throws Exception {
        String engine=req.getString("engine"),q=req.getString("q").trim();
        int page=req.optInt("page",0);
        if(q.isEmpty()||q.length()>256||page<0||page>10000) throw new IOException("Geçersiz arama.");
        boolean pinterest=req.optBoolean("pinterest",false);
        String term=pinterest?"site:pinterest.com "+q:q;
        String url=endpoint(engine,term,page), key=req.optString("key");
        Map<String,JSONObject> rows=new LinkedHashMap<>();
        String source=engine.equals("bing")?"Bing Görseller":engine.equals("yandex")?"Yandex Görsel":"Google Görseller";
        if(pinterest) source+=" · Pinterest";
        String notice="";
        boolean more=!engine.equals("google");
        try {
            if(engine.equals("google")&&!key.isEmpty()){
                JSONObject data=new JSONObject(read("https://serpapi.com/search.json?engine=google_images&hl=tr&gl=tr&q="+enc(term)+"&api_key="+enc(key)));
                if(data.has("error")) throw new IOException("SerpApi anahtarını ve kotayı kontrol edin.");
                JSONArray a=data.optJSONArray("images_results");
                if(a!=null) for(int i=0;i<a.length();i++){
                    JSONObject o=a.getJSONObject(i);addResult(rows,pinterest,o.optString("link"),o.optString("original"),o.optString("thumbnail"),o.optString("title"));
                }
            } else {
                String html=read(url);
                parse(engine,html,rows,pinterest);
                if(rows.isEmpty() && (html.contains("showcaptcha") || html.contains("captcha-container") || html.contains("SmartCaptcha")))
                    throw new IOException("Arama motoru doğrulama istedi. Daha sonra tekrar deneyin.");
            }
            if(page==0 && rows.isEmpty()) throw new IOException("Görsel bağlantısı bulunamadı.");
        } catch(Exception err) {
            if(pinterest || page!=0 || !req.optBoolean("commons") || !key.isEmpty()) throw new IOException(source+" araması tamamlanamadı. "+safeError(err));
            notice=source+" sonuç vermedi.";source="Wikimedia Commons";more=false;rows.clear();
            JSONObject data=new JSONObject(read("https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrnamespace=6&gsrlimit=20&prop=imageinfo&iiprop=url%7Cmime&iiurlwidth=320&format=json&gsrsearch="+enc(q+" filetype:bitmap")));
            if(data.has("error")) throw new IOException("Wikimedia araması tamamlanamadı.");
            JSONObject query=data.optJSONObject("query"), pages=query==null?null:query.optJSONObject("pages");
            if(pages!=null) for(Iterator<String> it=pages.keys();it.hasNext();){
                JSONObject p=pages.getJSONObject(it.next());JSONArray a=p.optJSONArray("imageinfo");
                if(a==null||a.length()==0) continue;
                JSONObject o=a.getJSONObject(0);if(o.optString("mime").startsWith("image/")) add(rows,o.optString("url"),o.optString("thumburl"),p.optString("title"));
            }
        }
        return new JSONObject().put("rows",new JSONArray(rows.values())).put("source",source)
                .put("notice",notice).put("more",more&&!rows.isEmpty());
    }
    static String safeError(Exception e) {
        if(e instanceof SocketTimeoutException) return "Bağlantı zaman aşımına uğradı.";
        if(e instanceof UnknownHostException) return "İnternet bağlantısını kontrol edin.";
        String msg=e.getMessage();
        if(msg==null||msg.contains("://")||msg.length()>160) return "Kaynak yanıt vermedi.";
        return msg;
    }
}
