package app.imagedownloader;
import java.io.IOException;
import java.util.*;
import org.json.*;

final class ShareSelection {
    static final int MAX_IMAGES=20,MAX_PROMPT=8000;
    final List<String> ids;
    final String prompt;
    ShareSelection(JSONObject req)throws JSONException,IOException{
        JSONArray input=req.getJSONArray("ids");
        if(input.length()<1||input.length()>MAX_IMAGES)throw new IOException("1–20 görsel seçin.");
        LinkedHashSet<String> unique=new LinkedHashSet<>();
        for(int i=0;i<input.length();i++){
            Object value=input.get(i);
            if(!(value instanceof String)||((String)value).isEmpty())throw new IOException("Geçersiz görsel seçimi.");
            unique.add((String)value);
        }
        ids=Collections.unmodifiableList(new ArrayList<>(unique));
        prompt=req.optString("prompt","").trim();
        if(prompt.length()>MAX_PROMPT)throw new IOException("Talimat çok uzun.");
    }
}
