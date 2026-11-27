package app.imagedownloader;

import org.json.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

final class DeepDiscovery {
    private static final int MAX_PAGES=6, MAX_RESULTS=45;
    private static final String AGENT="ImageDownloaderAndroid";
    private static final String[] TOCA_OFFICIAL={
        "https://www.tocaboca.com/kids/articles/downloadable/wallpapers",
        "https://www.tocaboca.com/kids/articles/downloadable/creator-content",
        "https://www.tocaboca.com/kids/toca-boca-world",
        "https://www.tocaboca.com/toca-boca-world",
        "https://www.mynewsdesk.com/se/tocaboca/latest_media"
    };
    private static final String[] TOCA_GALLERIES={
        "https://toca-life-world.fandom.com/wiki/Toca_Boca_World/Gallery",
        "https://toca-life-world.fandom.com/wiki/Category:Images",
        "https://thegameextras.com/game/toca-life-world/screenshots/"
    };
    static boolean toca(String query){
        String q=Relevance.norm(query);
        return q.matches(".*\\btoca\\s+(boca|life)\\b.*")||q.matches(".*\\btocaboca\\b.*");
    }
    static String[] variants(String q){
        if(toca(q)){
            String n=Relevance.norm(q);
            if(Arrays.asList("toca boca","toca life","toca life world","toca boca world","tocaboca").contains(n))
                return new String[]{q,"Toca Life World characters","Toca Boca World fan art","Toca Boca wallpapers","Toca Life World gallery","Toca Boca coloring pages","Toca Boca printable","Toca Boca dibujos","Toca Boca раскраски","Toca Boca screenshots","Toca Boca World artwork"};
            return new String[]{q,q+" gallery",q+" artwork",q+" printable",q+" fan art",q+" wallpaper"};
        }
        return new String[]{q,q+" gallery",q+" artwork",q+" high resolution",q+" illustrations",q+" images",q+" fan art",q+" screenshots",q+" printable"};
    }
    private static boolean publicAddress(URL url)throws IOException{
        if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null||url.getPort()!=-1&&url.getPort()!=443)return false;
        for(InetAddress a:InetAddress.getAllByName(url.getHost())){
            if(a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress())return false;
            byte[] b=a.getAddress();if(b.length==16&&(b[0]&0xfe)==0xfc)return false;
            if(b.length==4&&((b[0]&255)==0||(b[0]&255)==100&&(b[1]&0xc0)==64||(b[0]&255)>=224))return false;
        }
        return true;
    }
    private static String read(URL u,int max,boolean robots)throws IOException{
        SearchCancellation.check();
        if(!publicAddress(u))throw new IOException("Yerel veya güvensiz adres.");
        HttpURLConnection c=(HttpURLConnection)u.openConnection();
        c.setConnectTimeout(4500);c.setReadTimeout(6500);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent",Search.WIKI_UA);
        c.setRequestProperty("Accept-Language",SearchLanguages.active()+","+SearchLanguages.language()+";q=0.9");
        c.setRequestProperty("Accept",robots?"text/plain":"text/html,application/xhtml+xml");
        SearchCancellation.track(c);
        try{
            int status=c.getResponseCode();
            if(robots&&(status==404||status==410))return "";
            if(status!=200)throw new IOException("HTTP "+status);
            String type=c.getContentType();
            if(!robots&&(type==null||!type.toLowerCase(Locale.ROOT).contains("html")))throw new IOException("HTML değil.");
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))!=-1){SearchCancellation.check();if(out.size()+n>max)throw new IOException("Sayfa çok büyük.");out.write(buf,0,n);}
                return out.toString("UTF-8");
            }
        }finally{SearchCancellation.close(c);}
    }
    // Conservative robots handling. More restrictive matching rules win; an unavailable
    // robots file is not treated as permission. No login/challenge/redirect following.
    static boolean robotsAllows(String robots,String path){
        boolean applies=false,seenRule=false;int best=-1;boolean allow=true;
        for(String raw:robots.split("\\r?\\n")){
            String line=raw.split("#",2)[0].trim();int colon=line.indexOf(':');if(colon<0)continue;
            String field=line.substring(0,colon).trim().toLowerCase(Locale.ROOT),val=line.substring(colon+1).trim();
            if(field.equals("user-agent")){
                if(seenRule){applies=false;seenRule=false;}
                String agent=val.toLowerCase(Locale.ROOT);
                applies|=agent.equals("*")||!agent.isEmpty()&&AGENT.toLowerCase(Locale.ROOT).contains(agent);
            }else if(field.equals("allow")||field.equals("disallow")){
                seenRule=true;if(!applies||val.isEmpty())continue;
                String p=val.endsWith("$")?val.substring(0,val.length()-1):val;
                StringBuilder regex=new StringBuilder("^");
                for(String bit:p.split("\\*",-1)){if(regex.length()>1)regex.append(".*");regex.append(Pattern.quote(bit));}
                if(val.endsWith("$"))regex.append("$");else regex.append(".*");
                if(path.matches(regex.toString())&&(p.length()>best||p.length()==best&&field.equals("disallow"))){best=p.length();allow=field.equals("allow");}
            }else if(field.equals("crawl-delay")&&applies){try{if(Double.parseDouble(val)>0)return false;}catch(Exception ignored){}}
        }
        return allow;
    }
    private static Document page(String address,Map<String,String> robots)throws Exception{
        URL u=new URL(address);
        String root="https://"+u.getHost();
        if(!robots.containsKey(root))robots.put(root,read(new URL(root+"/robots.txt"),256000,true));
        if(!robotsAllows(robots.get(root),u.getFile()))return null;
        String html=read(u,4000000,false);
        if(Search.blocked(html))return null;
        Document doc=Jsoup.parse(html,address);
        for(Element meta:doc.select("meta[name=robots],meta[name=googlebot]")){
            String value=meta.attr("content").toLowerCase(Locale.ROOT);
            if(value.contains("noimageindex")||value.contains("noindex"))return null;
        }
        return doc;
    }
    private static boolean supported(String u){
        if(!Search.web(u)||MultiSearch.junk(u))return false;
        String h=Search.host(u);
        if(h.isEmpty()||h.equals("localhost")||h.endsWith(".local"))return false;
        try{URL url=new URL(u);return "https".equals(url.getProtocol())&&url.getUserInfo()==null&&(url.getPort()==-1||url.getPort()==443);}
        catch(Exception e){return false;}
    }
    private static String abs(String base,String value){
        if(value==null||value.trim().isEmpty())return "";
        try{return new URL(new URL(base),value.trim()).toString();}catch(Exception e){return "";}
    }
    static String largest(String base,String srcset){
        String best="";double max=-1;
        for(String part:srcset.split(",")){
            String[] bits=part.trim().split("\\s+");if(bits.length==0||bits[0].isEmpty())continue;
            try{
                double n=bits.length==1?1:bits[1].endsWith("w")?Double.parseDouble(bits[1].substring(0,bits[1].length()-1)):bits[1].endsWith("x")?1000*Double.parseDouble(bits[1].substring(0,bits[1].length()-1)):0;
                String candidate=abs(base,bits[0]);if(n>max&&supported(candidate)){max=n;best=candidate;}
            }catch(Exception ignored){}
        }return best;
    }
    private static String fandomOriginal(String u){
        if(MultiSearch.onDomain(u,"wikia.nocookie.net")&&u.contains("/revision/")){
            return u.substring(0,u.indexOf("/revision/"))+"/revision/latest";
        }return u;
    }
    private static int integer(String s){try{return Integer.parseInt(s);}catch(Exception e){return 0;}}
    private static void emit(String provider,String query,Document doc,Map<String,JSONObject> out,String url,String thumb,String label,int w,int h,boolean clean)throws JSONException{
        if(out.size()>=MAX_RESULTS||!supported(url))return;
        String context=Search.first(doc.select("main h1,article h1,h1").text(),doc.title());
        if(toca(query)&&(MultiSearch.onDomain(doc.location(),"tocaboca.com")||MultiSearch.onDomain(doc.location(),"toca-life-world.fandom.com")||doc.location().startsWith("https://www.mynewsdesk.com/se/tocaboca/")))context="Toca Boca · "+context;
        String title=Search.first(label,context);
        JSONObject evidence=new JSONObject().put("title",title).put("description",context).put("pageUrl",doc.location()).put("url",url);
        if(Relevance.score(query,evidence)<.5)return;
        if(Relevance.strictName(query)&&!toca(query)&&Relevance.score(query,new JSONObject().put("title",label).put("url",url))<.6)return;
        String original=fandomOriginal(url);
        MultiSearch.add(out,provider,original,thumb,title,doc.location(),w,h,clean);
        JSONObject row=out.get(MultiSearch.original(original));
        if(row!=null){
            row.put("description",context).put("discovery",true);
            if(!original.equals(url))row.put("fallback",url);
        }
    }
    static void extract(String provider,String query,Document doc,Map<String,JSONObject> out,boolean clean)throws JSONException{
        String base=doc.location();
        for(Element e:doc.select("main img,article img,[role=main] img,.gallery img,.mw-parser-output img,.wikia-gallery img")){
            if(out.size()>=MAX_RESULTS)break;
            String thumb=abs(base,Search.first(e.attr("data-src"),e.attr("data-lazy-src"),e.attr("src")));
            String url=Search.first(abs(base,e.attr("data-original")),abs(base,e.attr("data-full-src")),largest(base,Search.first(e.attr("data-srcset"),e.attr("srcset"))),thumb);
            Element a=e.closest("a[href]");
            if(a!=null){
                String href=a.absUrl("href");
                if(href.toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|webp|gif|avif)(\\?.*)?$")&&supported(href))url=href;
            }
            Element figure=e.closest("figure,.gallerybox,.wikia-gallery-item");
            String label=Search.first(e.attr("alt"),e.attr("title"),figure==null?"":figure.select("figcaption,.gallerytext,.lightbox-caption").text());
            emit(provider,query,doc,out,url,thumb,label,integer(e.attr("width")),integer(e.attr("height")),clean);
        }
        for(Element a:doc.select("main a[href],article a[href],.gallery a[href],.mw-parser-output a[href]")){
            if(out.size()>=MAX_RESULTS)break;
            String href=a.absUrl("href"),path;
            try{path=new URL(href).getPath().toLowerCase(Locale.ROOT);}catch(Exception e){continue;}
            if(path.matches(".*\\.(jpe?g|png|webp|gif|avif)$")){
                String label=Search.first(a.attr("title"),a.attr("aria-label"),a.text());
                emit(provider,query,doc,out,href,href,label,0,0,clean);
            }
        }
        for(Element e:doc.select("meta[property=og:image],meta[name=twitter:image],link[rel=image_src]")){
            String url=abs(base,Search.first(e.attr("content"),e.attr("href")));
            emit(provider,query,doc,out,url,url,doc.title(),0,0,clean);
        }
    }
    private static void discoverLinks(String query,int page,Set<String> urls)throws Exception{
        String[] options=variants(query);int variant=page%options.length,offset=page/options.length;
        String term=options[variant];
        try{
            String html=Search.read("https://www.bing.com/search?q="+Search.enc(term)+"&first="+(offset*10+1)+SearchLanguages.bingParams());
            if(!Search.blocked(html)){
                Document doc=Jsoup.parse(html,"https://www.bing.com/");
                for(Element a:doc.select("li.b_algo h2 a[href]")){
                    String u=a.absUrl("href");
                    if(supported(u)&&!MultiSearch.onDomain(u,"bing.com")&&!Search.pinterestHost(u)&&
                       Relevance.score(query,new JSONObject().put("title",a.text()).put("pageUrl",u))>=.5)urls.add(u);
                    if(urls.size()>=3)break;
                }
            }
        }catch(Exception ignored){}
        for(String engine:new String[]{"bing","google","yandex"}){
            if(Thread.currentThread().isInterrupted()||urls.size()>=MAX_PAGES)break;
            try{
                Map<String,JSONObject> hits=new LinkedHashMap<>();
                String html=Search.read(Search.endpoint(engine,term,offset));
                if(Search.blocked(html))continue;
                Search.parse(engine,html,hits,false);
                for(JSONObject hit:hits.values()){
                    String url=hit.optString("pageUrl");
                    if(supported(url)&&Relevance.score(query,hit)>=.5&&!Search.pinterestHost(url)&&!MultiSearch.onDomain(url,"bing.com")&&!MultiSearch.onDomain(url,"google.com")&&!MultiSearch.onDomain(url,"yandex.com"))urls.add(url);
                    if(urls.size()>=MAX_PAGES)break;
                }
            }catch(Exception ignored){}
        }
    }
    static Map<String,JSONObject> run(String id,String query,int page,boolean clean)throws Exception{
        Map<String,JSONObject> result=new LinkedHashMap<>();
        LinkedHashSet<String> urls=new LinkedHashSet<>();
        boolean curated=toca(query)&&!id.equals("deepweb");
        if(curated){
            String[] seeds=id.equals("officialmedia")?TOCA_OFFICIAL:TOCA_GALLERIES;
            // Each seed has its own position in the discovery stream.
            if(page>=seeds.length)return result;
            urls.add(seeds[page]);
        }else if(id.equals("deepweb")){
            discoverLinks(query,page,urls);
        }else return result;
        Map<String,String> robots=new HashMap<>();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        for(String url:urls){
            if(Thread.currentThread().isInterrupted()||System.nanoTime()>=deadline||result.size()>=MAX_RESULTS)break;
            try{
                Document doc=page(url,robots);if(doc!=null)extract(id,query,doc,result,clean);
            }catch(Exception ignored){}
        }
        return result;
    }
}
