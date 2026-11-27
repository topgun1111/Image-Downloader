package app.imagedownloader;
import org.json.*;
import java.util.*;
import java.util.regex.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

final class ClipTokenizer {
    private final JSONObject vocab;
    private final Map<String,Integer> ranks=new HashMap<>();
    private final String[] bytes=new String[256];
    private final long start,end,pad;
    private static final Pattern TOKEN=Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+");
    ClipTokenizer(JSONObject data)throws JSONException{
        JSONObject model=data.getJSONObject("model");vocab=model.getJSONObject("vocab");
        JSONArray merges=model.getJSONArray("merges");
        for(int i=0;i<merges.length();i++){
            Object item=merges.get(i);String key;
            if(item instanceof JSONArray){JSONArray a=(JSONArray)item;key=a.getString(0)+" "+a.getString(1);}else key=String.valueOf(item);
            ranks.put(key,i);
        }
        int extra=0;
        for(int i=0;i<256;i++){
            boolean visible=(i>=33&&i<=126)||(i>=161&&i<=172)||(i>=174);
            bytes[i]=String.valueOf((char)(visible?i:256+extra++));
        }
        start=vocab.getLong("<|startoftext|>");end=vocab.getLong("<|endoftext|>");
        pad=vocab.getLong("!");
    }
    long[] encode(String text,int len)throws JSONException{
        long[] ids=new long[len];Arrays.fill(ids,pad);ids[0]=start;int pos=1;
        text=Normalizer.normalize(text,Normalizer.Form.NFC).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
        Matcher m=TOKEN.matcher(text);
        while(m.find()&&pos<len-1){
            List<String> pieces=new ArrayList<>();
            for(byte b:m.group().getBytes(StandardCharsets.UTF_8))pieces.add(bytes[b&255]);
            if(pieces.isEmpty())continue;
            int last=pieces.size()-1;pieces.set(last,pieces.get(last)+"</w>");
            while(pieces.size()>1){
                int best=Integer.MAX_VALUE;String pair=null;
                for(int i=0;i+1<pieces.size();i++){
                    String key=pieces.get(i)+" "+pieces.get(i+1);Integer rank=ranks.get(key);
                    if(rank!=null&&rank<best){best=rank;pair=key;}
                }
                if(pair==null)break;
                List<String> next=new ArrayList<>();
                for(int i=0;i<pieces.size();i++){
                    if(i+1<pieces.size()&&(pieces.get(i)+" "+pieces.get(i+1)).equals(pair)){next.add(pieces.get(i)+pieces.get(++i));}
                    else next.add(pieces.get(i));
                }
                pieces=next;
            }
            for(String p:pieces){if(pos>=len-1)break;ids[pos++]=vocab.getLong(p);}
        }
        ids[pos]=end;return ids;
    }
    long[] mask(long[] ids){
        long[] m=new long[ids.length];boolean ended=false;
        for(int i=0;i<ids.length;i++){if(!ended)m[i]=1;if(i>0&&ids[i]==end)ended=true;}
        return m;
    }
}
