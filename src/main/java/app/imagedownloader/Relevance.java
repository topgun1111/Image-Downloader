package app.imagedownloader;
import org.json.*;
import java.util.*;
import java.net.URLDecoder;
import java.text.Normalizer;

final class Relevance {
    static String norm(String s){
        try{s=URLDecoder.decode(s,"UTF-8");}catch(Exception ignored){}
        return Normalizer.normalize(s,Normalizer.Form.NFKD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT)
            .replace('ı','i').replaceAll("[^\\p{L}\\p{N}]+"," ").trim().replaceAll("\\s+"," ");
    }
    private static Set<String> words(String s){
        Set<String> w=new LinkedHashSet<>();
        for(String a:norm(s).split(" "))if(!a.isEmpty()&&!Arrays.asList("a","an","the","of","and","in","on","ve","bir","ile","for","image","images","photo","photos","gorsel","resim","fotograf").contains(a))w.add(a);
        return w;
    }
    static String expanded(String q){
        String n=norm(q);
        if(n.equals("ronaldo"))return "\"Ronaldo\" football";
        if(n.equals("messi"))return "\"Messi\" football";
        return "\""+q.replace("\"","")+"\"";
    }
    static double score(String q,JSONObject r){
        String normalized=norm(q);
        boolean toca=DeepDiscovery.toca(q);
        if(toca)q=normalized.replaceAll("\\btoca\\s+(boca|life)(\\s+world)?\\b|\\btocaboca\\b","toca");
        Set<String> wanted=words(q);if(wanted.isEmpty())return 1;
        String title=norm(r.optString("title")),description=norm(r.optString("description")),tags=norm(r.optString("tags"));
        String page=r.optString("pageUrl"),url=r.optString("url");
        try{page=new java.net.URL(page).getPath();}catch(Exception e){page="";}
        try{url=new java.net.URL(url).getPath();}catch(Exception e){url="";}
        String paths=norm(page+" "+url);
        Set<String> main=words(title+" "+description+" "+tags),secondary=words(paths);
        if(toca){if(main.contains("tocaboca"))main.add("toca");if(secondary.contains("tocaboca"))secondary.add("toca");}
        double hits=0;
        for(String token:wanted){if(main.contains(token))hits+=1;else if(secondary.contains(token))hits+=.82;}
        double coverage=hits/wanted.size();
        String phrase=norm(q);
        boolean exact=(" "+title+" ").contains(" "+phrase+" ")||(" "+paths+" ").contains(" "+phrase+" ");
        if(exact)coverage=Math.max(coverage,.94);
        if(title.startsWith("category ")&&!exact)coverage*=.7;
        return Math.min(1,coverage);
    }
    static boolean strictName(String q){
        // Proper-name queries need metadata, not visual identity inference.
        String s=q.trim();
        if(s.isEmpty()||DeepDiscovery.toca(q))return false;
        if(Arrays.asList("ronaldo","messi").contains(norm(s)))return true;
        String[] parts=s.split("\\s+");if(parts.length>3)return false;
        for(String part:parts)if(part.isEmpty()||!Character.isUpperCase(part.codePointAt(0)))return false;
        return true;
    }
    static double threshold(String mode){switch(mode){case "ultra":return .82;case "strict":return .60;case "balanced":return .34;default:return .25;}}
    static boolean admit(String q,JSONObject row,String mode,Double visual){
        double m=score(q,row);
        if(strictName(q))return m>=Math.max(.60,threshold(mode));
        if(visual==null||mode.equals("fast"))return m>=threshold(mode);
        // Visual evidence can rescue metadata-poor general subjects, never a named identity.
        double v=Math.max(0,Math.min(1,(visual-.12)/.20));
        if(m==0 && v<(mode.equals("ultra")?.85:mode.equals("strict")?.70:.52))return false;
        return Math.max(m*.65+v*.35,v*.85)>=threshold(mode);
    }
    static double rank(String q,JSONObject row,Double visual){
        double m=score(q,row);
        if(visual==null||strictName(q))return m;
        double v=Math.max(0,Math.min(1,(visual-.12)/.20));
        return .65*m+.35*v;
    }
    static boolean useful(String q,Map<String,JSONObject> rows){
        if(rows.isEmpty())return false;
        int hits=0;for(JSONObject r:rows.values())if(score(q,r)>=.5)hits++;
        return hits>=Math.min(3,rows.size())&&hits*2>=rows.size();
    }
}
