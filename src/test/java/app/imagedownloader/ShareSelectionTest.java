package app.imagedownloader;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Arrays;
public class ShareSelectionTest {
    @Test public void orderAndDedup()throws Exception{
        ShareSelection s=new ShareSelection(new JSONObject().put("ids",new JSONArray().put("b").put("a").put("b")).put("prompt"," Color "));
        assertEquals(Arrays.asList("b","a"),s.ids);assertEquals("Color",s.prompt);
    }
    @Test public void originalAllowsEmptyPrompt()throws Exception{
        assertEquals("",new ShareSelection(new JSONObject().put("ids",new JSONArray().put("a"))).prompt);
    }
    @Test(expected=IOException.class) public void emptyRejected()throws Exception{
        new ShareSelection(new JSONObject().put("ids",new JSONArray()));
    }
    @Test(expected=IOException.class) public void tooManyRejected()throws Exception{
        JSONArray a=new JSONArray();for(int i=0;i<21;i++)a.put("item"+i);
        new ShareSelection(new JSONObject().put("ids",a));
    }
    @Test(expected=IOException.class) public void invalidIdRejected()throws Exception{
        new ShareSelection(new JSONObject().put("ids",new JSONArray().put(123)));
    }
    @Test(expected=IOException.class) public void longPromptRejected()throws Exception{
        char[] p=new char[8001];Arrays.fill(p,'x');
        new ShareSelection(new JSONObject().put("ids",new JSONArray().put("a")).put("prompt",new String(p)));
    }
}
