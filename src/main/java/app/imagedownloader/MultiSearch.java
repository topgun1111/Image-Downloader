package app.imagedownloader;

import org.json.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

final class MultiSearch {
    static final String[] IDS={"bing","google","yandex","pinterest","commons","openverse","flickr","pexels","pixabay","unsplash","deviantart","artstation","freepik","stocksnap","rawpixel","wikimedia","duckduckgo"};
    private static final int LIMIT=45;
    private static final Pattern PIN=Pattern.compile("https?://i\\.pinimg\\.com/(?:originals|[0-9]{2,4}x(?:[0-9]+)?)/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{16,64}\\.(?:jpe?g|png|webp|gif|avif)",Pattern.CASE_INSENSITIVE);
    static final Map<String,String> DOMAINS=new LinkedHashMap<>();
    static {
        DOMAINS.put("pinterest","pinterest.com"); DOMAINS.put("flickr","flickr.com");
        DOMAINS.put("pexels","pexels.com"); DOMAINS.put("pixabay","pixabay.com");
        DOMAINS.put("unsplash","unsplash.com"); DOMAINS.put("deviantart","deviantart.com");
        DOMAINS.put("artstation","artstation.com"); DOMAINS.put("freepik","freepik.com");
        DOMAINS.put("stocksnap","stocksnap.io"); DOMAINS.put("rawpixel","rawpixel.com");
        DOMAINS.put("wikimedia","wikipedia.org"); DOMAINS.put("commons","commons.wikimedia.org");
    }
    static boolean onDomain(String url,String domain) {
        String h=Search.host(url);return h.equals(domain)||h.endsWith("."+domain);
    }
    static boolean junk(String url) {
        if(!Search.web(url))return true;
        try {
            String p=new URL(url).getPath().toLowerCase(Locale.ROOT);
            return p.matches(".*\\.(?:svg|ico)(?:$|/).*") ||
                p.matches(".*(?:^|[/_.-])(?:favicon|sprite|placeholder|spacer|blank|loading|avatar|profile[_-]?pic|pixel|logo|icon)(?:[/_.-]|$).*") ||
                p.contains("/user/avatars/") || p.contains("/user_avatar/") ||
                p.contains("/75x75/") || p.contains("/30x30/") || p.contains("/60x60/");
        }catch(Exception e){return true;}
    }
    static String original(String url) {
        url=Search.normalize(url);
        if(onDomain(url,"pinimg.com")&&PIN.matcher(url).matches())
            return url.replaceFirst("(?i)(https?://i\\.pinimg\\.com/)[0-9]{2,4}x(?:[0-9]+)?/","$1originals/");
        return url;
    }
    static void add(Map<String,JSONObject> out,String provider,String url,String thumb,String title,String page,int w,int h,boolean clean) throws JSONException {
        url=Search.normalize(url);thumb=Search.normalize(thumb);
        if(!Search.web(url)||out.size()>=LIMIT||clean&&(junk(url)||(w>0&&w<96)||(h>0&&h<96)))return;
        String candidate=original(url);
        if(out.containsKey(candidate))return;
        JSONObject row=new JSONObject().put("url",candidate).put("thumb",Search.web(thumb)&&(!clean||!junk(thumb))?thumb:url)
            .put("fallback",candidate.equals(url)?"":url).put("title",Search.first(title,Search.host(url)))
            .put("provider",provider).put("width",Math.max(w,0)).put("height",Math.max(h,0));
        if(Search.web(page))row.put("pageUrl",page);
        out.put(candidate,row);
    }
    private static JSONObject json(String url,String auth) throws Exception {
        HttpURLConnection c=Search.open(url);
        c.setInstanceFollowRedirects(false);
        if(!auth.isEmpty()) {
            c.setRequestProperty("Authorization",auth);
        }
        c.setRequestProperty("Accept","application/json");
        try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();if(out.size()+n>12000000)throw new IOException("Yanıt çok büyük.");out.write(b,0,n);}
            return new JSONObject(out.toString("UTF-8"));
        }finally{c.disconnect();}
    }
    private static JSONArray arr(JSONObject o,String key){JSONArray a=o.optJSONArray(key);return a==null?new JSONArray():a;}
    static void apiRows(String id,JSONObject data,Map<String,JSONObject> rows,boolean clean) throws JSONException {
        JSONArray a=arr(data,id.equals("pixabay")?"hits":id.equals("pexels")?"photos":"results");
        for(int i=0;i<a.length();i++){
            JSONObject o=a.optJSONObject(i);if(o==null)continue;
            String url="",thumb="",title="",page="";
            int w=o.optInt("width"),h=o.optInt("height");
            switch(id){
                case "openverse":
                    url=o.optString("url");thumb=o.optString("thumbnail");title=o.optString("title");page=o.optString("foreign_landing_url");break;
                case "pixabay":
                    url=Search.first(o.optString("largeImageURL"),o.optString("webformatURL"));thumb=o.optString("previewURL");title=o.optString("tags");page=o.optString("pageURL");
                    w=o.optInt("imageWidth");h=o.optInt("imageHeight");break;
                case "unsplash":
                    JSONObject urls=o.optJSONObject("urls"), links=o.optJSONObject("links"),user=o.optJSONObject("user");
                    url=Search.field(urls,"full");thumb=Search.field(urls,"small");title=Search.first(o.optString("alt_description"),o.optString("description"));page=Search.field(links,"html");
                    String tracking=Search.field(links,"download_location");
                    if(!onDomain(tracking,"api.unsplash.com")||!tracking.startsWith("https://api.unsplash.com/"))continue;
                    add(rows,id,url,thumb,title,page,w,h,clean);
                    JSONObject unsplashRow=rows.get(original(url));
                    if(unsplashRow!=null)unsplashRow.put("creator",Search.field(user,"name")).put("creatorUrl",Search.field(user==null?null:user.optJSONObject("links"),"html")).put("tracking",tracking);
                    continue;
                case "pexels":
                    JSONObject s=o.optJSONObject("src");url=Search.field(s,"original");thumb=Search.first(Search.field(s,"medium"),Search.field(s,"small"));title=Search.first(o.optString("alt"),o.optString("photographer"));page=o.optString("url");break;
            }
            add(rows,id,url,thumb,title,page,w,h,clean);
            JSONObject row=rows.get(original(url));
            if(row!=null){row.put("description",o.optString("description")).put("tags",o.optString("tags"));}
            if(row!=null&&id.equals("openverse"))row.put("license",o.optString("license")).put("creator",o.optString("creator"));
            if(row!=null&&id.equals("pexels"))row.put("creator",o.optString("photographer"));
        }
    }
    static void trackUnsplash(String url,String key)throws Exception{
        if(!url.startsWith("https://api.unsplash.com/")||!onDomain(url,"api.unsplash.com")||key.isEmpty())throw new IOException("Unsplash indirme doğrulaması yapılamadı.");
        json(url,"Client-ID "+key);
    }
    private static void commons(String q,int page,Map<String,JSONObject> rows,boolean clean) throws Exception {
        JSONObject d=json("https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrnamespace=6&gsrlimit=35&gsroffset="+(page*35)+"&prop=imageinfo&iiprop=url%7Cmime%7Csize&iiurlwidth=330&format=json&gsrsearch="+Search.enc(q+" filetype:bitmap"),"");
        commonsRows(d,rows,clean);
    }
    static void commonsRows(JSONObject d,Map<String,JSONObject> rows,boolean clean)throws JSONException {
        JSONObject query=d.optJSONObject("query"),pages=query==null?null:query.optJSONObject("pages");
        if(pages==null)return;
        for(Iterator<String> it=pages.keys();it.hasNext();){
            JSONObject p=pages.getJSONObject(it.next());
            if(p.optInt("ns",-1)!=6||!p.optString("title").startsWith("File:"))continue;
            JSONArray a=arr(p,"imageinfo");if(a.length()==0)continue;
            JSONObject o=a.getJSONObject(0);
            String mime=o.optString("mime"),url=o.optString("url"),thumb=o.optString("thumburl");
            if(!Arrays.asList("image/jpeg","image/png","image/gif","image/webp","image/tiff","image/bmp","image/avif").contains(mime)||!wikiMedia(url))continue;
            if(!wikiMedia(thumb))thumb=url;
            add(rows,"commons",url,thumb,p.optString("title").substring(5),o.optString("descriptionurl"),o.optInt("width"),o.optInt("height"),clean);
        }
    }
    static boolean wikiMedia(String url){
        return url.startsWith("https://")&&onDomain(url,"upload.wikimedia.org")&&!url.contains("/wiki/Category:");
    }
    static boolean commonsIndexedFile(JSONObject row){
        if(!wikiMedia(row.optString("url")))return false;
        String title=row.optString("title").toLowerCase(Locale.ROOT);
        String page=row.optString("pageUrl").toLowerCase(Locale.ROOT);
        return !title.startsWith("category:")&&!page.contains("/category:")&&!page.contains("/category%3a")&&!page.contains("/category%253a");
    }
    static void webRows(String id,String html,String base,Map<String,JSONObject> rows,boolean clean) throws JSONException {
        Document doc=Jsoup.parse(html,base);
        // Only result-sized images; never promote arbitrary script assets or navigation graphics.
        if(id.equals("pinterest")){
            Matcher m=PIN.matcher(html.replace("\\/","/").replace("\\u002f","/").replace("\\u002F","/"));
            while(m.find()&&rows.size()<LIMIT){
                String u=m.group();if(u.contains("/75x")||u.contains("/60x")||u.contains("/30x"))continue;
                add(rows,id,u,u,"Pinterest","",0,0,clean);
            }
            return;
        }
        for(Element e:doc.select("main img, [role=main] img, article img, figure img, [data-testid*=photo] img")){
            String thumb=Search.first(e.absUrl("data-src"),e.absUrl("src"));
            String best=Search.first(e.absUrl("data-original"),e.absUrl("data-full-src"),thumb);
            String set=Search.first(e.attr("data-srcset"),e.attr("srcset"));
            long largest=0;
            for(String part:set.split(",")){
                String[] bits=part.trim().split("\\s+");if(bits.length!=2)continue;
                try{
                    long size=bits[1].endsWith("w")?Long.parseLong(bits[1].replace("w","")):
                        bits[1].endsWith("x")?(long)(Double.parseDouble(bits[1].replace("x",""))*1000):0;
                    if(size>largest){String u=new URL(new URL(base),bits[0]).toString();if(Search.web(u)){largest=size;best=u;}}
                }catch(Exception ignored){}
            }
            int w=number(e.attr("width")),h=number(e.attr("height"));
            Element anchor=e.closest("a[href]");
            String landing=anchor==null?"":anchor.absUrl("href");
            if(!cdn(id,best))continue;
            String title=Search.first(e.attr("alt"),e.attr("title"),anchor==null?"":anchor.attr("aria-label"));
            add(rows,id,best,thumb,title,landing,w,h,clean);
        }
    }
    private static int number(String n){try{return Integer.parseInt(n);}catch(Exception e){return 0;}}
    private static boolean cdn(String id,String url){
        switch(id){
            case "flickr":return onDomain(url,"staticflickr.com");
            case "pexels":return onDomain(url,"images.pexels.com");
            case "pixabay":return onDomain(url,"pixabay.com")&&Search.host(url).equals("cdn.pixabay.com");
            case "unsplash":return onDomain(url,"images.unsplash.com");
            case "deviantart":return onDomain(url,"wixmp.com")||onDomain(url,"deviantart.net");
            case "artstation":return onDomain(url,"artstation.com")&&Search.host(url).contains("cdn");
            case "freepik":return onDomain(url,"freepik.com")&&Search.host(url).startsWith("img.");
            case "stocksnap":return onDomain(url,"cdn.stocksnap.io");
            case "rawpixel":return onDomain(url,"rawpixel.com")&&Search.host(url).startsWith("images.");
            default:return false;
        }
    }
    private static String publicPage(String id,String q,int page)throws Exception{
        String term=Search.enc(q),p=""+(page+1);
        switch(id){
            case "pinterest":return "https://www.pinterest.com/search/pins/?q="+term;
            case "flickr":return "https://www.flickr.com/search/?text="+term+"&page="+p;
            case "pexels":return "https://www.pexels.com/search/"+term.replace("+","%20")+"/?page="+p;
            case "pixabay":return "https://pixabay.com/images/search/"+term.replace("+","%20")+"/?pagi="+p;
            case "unsplash":return "https://unsplash.com/s/photos/"+term.replace("+","-");
            case "deviantart":return "https://www.deviantart.com/search?q="+term;
            case "artstation":return "https://www.artstation.com/search?sort_by=relevance&query="+term;
            case "freepik":return "https://www.freepik.com/search?query="+term+"&page="+p;
            case "stocksnap":return "https://stocksnap.io/search/"+term.replace("+","%20");
            case "rawpixel":return "https://www.rawpixel.com/search/"+term.replace("+","%20");
            default:return "";
        }
    }
    private static void copy(String id,Map<String,JSONObject> from,Map<String,JSONObject> to,boolean clean,boolean restrict) throws JSONException {
        for(JSONObject o:from.values()){
            String u=o.optString("url"),page=o.optString("pageUrl");
            if(id.equals("commons")&&!commonsIndexedFile(o))continue;
            String domain=DOMAINS.get(id);
            if(restrict&&!(id.equals("pinterest")?(Search.pinterestHost(u)||Search.pinterestSource(page)):
                domain!=null&&(onDomain(page,domain)||onDomain(u,domain)||cdn(id,u))))continue;
            add(to,id,u,o.optString("thumb"),o.optString("title"),page,o.optInt("width"),o.optInt("height"),clean);
            JSONObject added=to.get(original(u));if(added!=null)for(String key:new String[]{"description","tags","license","creator","creatorUrl","tracking"})if(!o.optString(key).isEmpty())added.put(key,o.optString(key));
        }
    }
    private static void indexed(String id,String q,int page,boolean clean,Map<String,JSONObject> rows)throws Exception{
        String domain=DOMAINS.get(id);
        if(domain==null)return;
        String term=q+" site:"+domain;
        for(String engine:new String[]{"bing","google","yandex"}){
            if(Thread.currentThread().isInterrupted())return;
            try{
                Map<String,JSONObject> tmp=new LinkedHashMap<>();
                String html=Search.read(Search.endpoint(engine,term,page));
                if(Search.blocked(html))continue;
                Search.parse(engine,html,tmp,id.equals("pinterest"));
                copy(id,tmp,rows,clean,true);
                if(Relevance.useful(q,rows))return;
            }catch(Exception ignored){}
        }
    }
    static Map<String,JSONObject> provider(String id,String q,int page,boolean clean,JSONObject keys,boolean pinterestOnly)throws Exception{
        Map<String,JSONObject> out=new LinkedHashMap<>();
        String engineTerm=pinterestOnly?q+" site:pinterest.com":q;
        if(id.equals("bing")||id.equals("google")||id.equals("yandex")){
            try{
                JSONObject req=new JSONObject().put("engine",id).put("q",q).put("page",page).put("pinterest",pinterestOnly).put("key",keys.optString("serpapi"));
                JSONObject result=Search.run(req);
                Map<String,JSONObject> rows=new LinkedHashMap<>();
                for(int i=0;i<result.getJSONArray("rows").length();i++){
                    JSONObject o=result.getJSONArray("rows").getJSONObject(i);rows.put(o.optString("url"),o);
                }
                copy(id,rows,out,clean,false);
            }catch(Exception ignored){}
            if(out.isEmpty()&&id.equals("google")&&!keys.optString("serpapi").isEmpty()){
                try{Map<String,JSONObject> tmp=new LinkedHashMap<>();Search.parse(id,Search.read(Search.endpoint(id,engineTerm,page)),tmp,pinterestOnly);copy(id,tmp,out,clean,false);}catch(Exception ignored){}
            }
            if(out.isEmpty()&&id.equals("bing")){
                Map<String,JSONObject> tmp=new LinkedHashMap<>();
                Search.parse(id,Search.read("https://www.bing.com/images/search?q="+Search.enc(engineTerm)+"&first="+(page*35+1)),tmp,pinterestOnly);
                copy(id,tmp,out,clean,false);
            }
            if(out.isEmpty()&&id.equals("google")){
                Map<String,JSONObject> tmp=new LinkedHashMap<>();
                Search.parse(id,Search.read("https://www.google.com/search?tbm=isch&q="+Search.enc(engineTerm)+"&start="+(page*20)),tmp,pinterestOnly);copy(id,tmp,out,clean,false);
            }
            if(out.isEmpty()&&id.equals("yandex")){
                Map<String,JSONObject> tmp=new LinkedHashMap<>();
                Search.parse(id,Search.read("https://yandex.ru/images/search?text="+Search.enc(engineTerm)+"&p="+page),tmp,pinterestOnly);copy(id,tmp,out,clean,false);
            }
            return out;
        }
        if(id.equals("commons")){commons(q,page,out,clean);return out;}
        if(id.equals("openverse")){
            apiRows(id,json("https://api.openverse.org/v1/images/?q="+Search.enc(q)+"&page_size=35&page="+(page+1),""),out,clean);return out;
        }
        if(id.equals("duckduckgo")){
            // Public image results page only; no undocumented token or challenge bypass.
            String html=Search.read("https://duckduckgo.com/?q="+Search.enc(q)+"&iax=images&ia=images");
            Document doc=Jsoup.parse(html);
            for(Element a:doc.select("a[href*=image_url]"))add(out,id,Search.param(a.attr("href"),"image_url"),"",a.text(),"",0,0,clean);
            return out;
        }
        try{
            if(id.equals("unsplash")&&!keys.optString(id).isEmpty())
                apiRows(id,json("https://api.unsplash.com/search/photos?query="+Search.enc(q)+"&per_page=30&page="+(page+1)+"&content_filter=high","Client-ID "+keys.optString(id)),out,clean);
            if(id.equals("flickr")){
                // Openverse's public catalog includes openly licensed Flickr photos; Flickr's own general search may require user-scoped access.
                Map<String,JSONObject> flickr=new LinkedHashMap<>();
                apiRows("openverse",json("https://api.openverse.org/v1/images/?source=flickr&q="+Search.enc(q)+"&page_size=35&page="+(page+1),""),flickr,clean);
                for(JSONObject row:flickr.values()){
                    row.put("provider","flickr");out.put(row.getString("url"),row);
                }
            }
            if(id.equals("pexels")&&!keys.optString(id).isEmpty())
                apiRows(id,json("https://api.pexels.com/v1/search?query="+Search.enc(q)+"&per_page=35&page="+(page+1),keys.optString(id)),out,clean);
            if(id.equals("pixabay")&&!keys.optString(id).isEmpty())
                apiRows(id,json("https://pixabay.com/api/?key="+Search.enc(keys.optString(id))+"&q="+Search.enc(q)+"&per_page=35&page="+(page+1)+ "&safesearch=true",""),out,clean);
        }catch(Exception ignored){}
        if(out.isEmpty()){
            try{
                String url=publicPage(id,q,page);
                if(!url.isEmpty()){
                    String html=Search.read(url);
                    if(!Search.blocked(html))webRows(id,html,url,out,clean);
                }
            }catch(Exception ignored){}
        }
        if(!Relevance.useful(q,out)){
            Map<String,JSONObject> better=new LinkedHashMap<>();indexed(id,Relevance.expanded(q),page,clean,better);
            if(!better.isEmpty()){
                Map<String,JSONObject> combined=new LinkedHashMap<>(better);
                for(Map.Entry<String,JSONObject> entry:out.entrySet())if(combined.size()<LIMIT)combined.putIfAbsent(entry.getKey(),entry.getValue());
                out=combined;
            }
        }
        return out;
    }
    static JSONObject run(JSONObject req)throws Exception{
        String q=req.optString("q").trim();int page=req.optInt("page",0);
        if(q.isEmpty()||q.length()>256||page<0||page>1000)throw new IOException("Geçersiz arama.");
        JSONArray chosen=req.optJSONArray("providers");
        LinkedHashSet<String> ids=new LinkedHashSet<>();
        if(chosen==null)ids.addAll(Arrays.asList(IDS));
        else for(int i=0;i<chosen.length();i++)if(Arrays.asList(IDS).contains(chosen.optString(i)))ids.add(chosen.optString(i));
        if(ids.isEmpty())throw new IOException("En az bir kaynak seçin.");
        JSONObject keys=req.optJSONObject("keys");final JSONObject credentials=keys==null?new JSONObject():keys;
        boolean clean=req.optBoolean("clean",true),pinterestOnly=req.optBoolean("pinterest",false);
        ExecutorService pool=Executors.newFixedThreadPool(Math.min(10,ids.size()));
        CompletionService<Map<String,JSONObject>> completion=new ExecutorCompletionService<>(pool);
        Map<Future<Map<String,JSONObject>>,String> pending=new HashMap<>();
        Map<String,Map<String,JSONObject>> byId=new LinkedHashMap<>();
        JSONObject status=new JSONObject();JSONArray next=new JSONArray();
        try{
            for(String id:ids)pending.put(completion.submit(()->{
                Map<String,JSONObject> rows=provider(id,q,page,clean,credentials,pinterestOnly);
                if(!Relevance.useful(q,rows)&&!Thread.currentThread().isInterrupted()&&Arrays.asList("bing","google","yandex").contains(id)){
                    try{
                        Map<String,JSONObject> alternate=provider(id,Relevance.expanded(q),page,clean,credentials,pinterestOnly);
                        if(Relevance.useful(q,alternate)||rows.isEmpty())rows=alternate;
                    }catch(Exception ignored){}
                }
                return rows;
            }),id);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(65);
            while(!pending.isEmpty()){
                long left=deadline-System.nanoTime();if(left<=0)break;
                Future<Map<String,JSONObject>> f=completion.poll(left,TimeUnit.NANOSECONDS);if(f==null)break;
                String id=pending.remove(f);
                try{
                    Map<String,JSONObject> rows=f.get();byId.put(id,rows);status.put(id,rows.size());
                    if(!rows.isEmpty()&&!id.equals("duckduckgo"))next.put(id);
                }catch(Exception e){status.put(id,-1);}
            }
            for(String id:pending.values())status.put(id,-1);
        }finally{for(Future<?> f:pending.keySet())f.cancel(true);pool.shutdownNow();}
        // Round-robin prevents the first provider from dominating the gallery.
        List<Iterator<JSONObject>> iterators=new ArrayList<>();
        for(String id:ids)if(byId.containsKey(id))iterators.add(byId.get(id).values().iterator());
        Map<String,JSONObject> all=new LinkedHashMap<>();
        boolean progress=true;
        while(progress){
            progress=false;
            for(Iterator<JSONObject> it:iterators)if(it.hasNext()){
                progress=true;JSONObject o=it.next();String url=o.getString("url");
                if(!all.containsKey(url))all.put(url,o);
                else{
                    JSONObject previous=all.get(url);
                    String s=previous.optString("provider");
                    if(!Arrays.asList(s.split(",")).contains(o.optString("provider")))previous.put("provider",s+","+o.optString("provider"));
                    for(String key:new String[]{"tracking","creator","creatorUrl","license"})if(!o.optString(key).isEmpty()&&previous.optString(key).isEmpty())previous.put(key,o.optString(key));
                    if(previous.optInt("width")==0&&o.optInt("width")>0)previous.put("width",o.optInt("width")).put("height",o.optInt("height"));
                }
            }
        }
        return new JSONObject().put("rows",new JSONArray(all.values())).put("source","Çoklu arama").put("status",status)
            .put("nextProviders",next).put("more",next.length()>0).put("notice","");
    }
}
