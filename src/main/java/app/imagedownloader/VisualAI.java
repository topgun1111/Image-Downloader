package app.imagedownloader;
import android.content.Context;
import android.graphics.*;
import ai.onnxruntime.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class VisualAI implements AutoCloseable {
    private static final String BASE="https://huggingface.co/Xenova/mobileclip_s0/resolve/20c6e4f26ad3f7f7e9cde13c4f9bb54852dd42c6/";
    private static final String TEXT="text_model_quantized.onnx",VISION="vision_model_quantized.onnx";
    private static final String TEXT_SHA="b8557b10e5c23a0126c6d2e6eba48d240484979007917d128953b31618a04211",VISION_SHA="fcbd153d1aa1314fb72ea39b20c37e0572e7e7b05359b51f3efee5d682658472";
    interface Progress {void update(String message);}
    private final Context ctx;
    private OrtEnvironment env;
    private OrtSession text,vision;
    private ClipTokenizer tokenizer;
    private final Map<String,Sample> cache=new LinkedHashMap<String,Sample>(128,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Sample> e){return size()>384;}
    };
    private final Map<String,float[]> queries=new LinkedHashMap<String,float[]>(16,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,float[]> e){return size()>32;}
    };
    VisualAI(Context c){ctx=c.getApplicationContext();}
    private File dir(){return new File(ctx.getFilesDir(),"mobileclip-s0-20c6e4f");}
    boolean installed(){return new File(dir(),"ready").isFile()&&new File(dir(),TEXT).isFile()&&new File(dir(),VISION).isFile()&&new File(dir(),"tokenizer.json").isFile();}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    private static void get(String path,File dest,String expected,long max,Progress p)throws Exception{
        File part=new File(dest.getPath()+".part");
        HttpURLConnection c=(HttpURLConnection)new URL(BASE+path).openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(true);
        try{
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(part)){
                byte[] buf=new byte[65536];long size=0;int n;long last=0;
                while((n=in.read(buf))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();size+=n;if(size>max)throw new IOException("Model dosyası çok büyük.");out.write(buf,0,n);hash.update(buf,0,n);
                    if(size-last>1000000){last=size;p.update(dest.getName()+" · "+size/1000000+" MB");}}
                if(size==0)throw new IOException("Boş model dosyası.");
            }
            if(!expected.isEmpty()&&!expected.equals(hex(hash.digest())))throw new IOException("Model SHA-256 uyuşmuyor.");
            java.nio.file.Files.move(part.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }finally{c.disconnect();part.delete();}
    }
    void install(Progress p)throws Exception{
        if(installed()){load();return;}
        File d=dir();if(!d.isDirectory()&&!d.mkdirs())throw new IOException("Model klasörü oluşturulamadı.");
        if(d.getUsableSpace()<150000000L)throw new IOException("En az 150 MB boş alan gerekli.");
        get("onnx/"+TEXT,new File(d,TEXT),TEXT_SHA,65000000,p);
        get("onnx/"+VISION,new File(d,VISION),VISION_SHA,30000000,p);
        get("tokenizer.json",new File(d,"tokenizer.json"),"",5000000,p);
        get("LICENSE",new File(d,"LICENSE"),"",100000,p);
        load();
        // Exercise both actual encoders before marking the model ready.
        float[] a=query("a photograph of a flower");
        float[] b=encodeImage(new float[3*256*256]);
        if(a.length!=b.length||a.length<64)throw new IOException("AI model çıkış boyutları uyuşmuyor.");
        new File(d,"ready").createNewFile();
    }
    private void load()throws Exception{
        if(text!=null&&vision!=null&&tokenizer!=null)return;
        if(env==null)env=OrtEnvironment.getEnvironment();
        closeSessions();
        try{
            tokenizer=new ClipTokenizer(new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(dir(),"tokenizer.json").toPath()),StandardCharsets.UTF_8)));
            try(OrtSession.SessionOptions opt=new OrtSession.SessionOptions()){
                opt.setIntraOpNumThreads(2);opt.setInterOpNumThreads(1);
                text=env.createSession(new File(dir(),TEXT).getAbsolutePath(),opt);
                vision=env.createSession(new File(dir(),VISION).getAbsolutePath(),opt);
            }
        }catch(Exception e){closeSessions();throw e;}
    }
    private static float[] output(OrtSession.Result r,String name)throws Exception{
        Object value=r.get(name).isPresent()?r.get(name).get().getValue():r.get(0).getValue();
        if(!(value instanceof float[][])||((float[][])value).length!=1)throw new IOException("Beklenmeyen AI çıkışı.");
        float[] a=((float[][])value)[0].clone();double norm=0;for(float x:a)norm+=x*x;
        if(!(norm>0)||!Double.isFinite(norm))throw new IOException("Geçersiz AI vektörü.");
        for(int i=0;i<a.length;i++)a[i]/=(float)Math.sqrt(norm);
        return a;
    }
    private float[] query(String q)throws Exception{
        float[] cached=queries.get(q);if(cached!=null)return cached;
        long[] ids=tokenizer.encode(q,77);
        Map<String,OnnxTensor> inputs=new HashMap<>();
        try{
            for(String name:text.getInputNames()){
                long[] data=name.equals("input_ids")?ids:name.equals("attention_mask")?tokenizer.mask(ids):null;
                if(data==null)throw new IOException("Desteklenmeyen metin modeli girdisi.");
                TensorInfo info=(TensorInfo)text.getInputInfo().get(name).getInfo();
                OnnxTensor tensor;
                if(info.type==OnnxJavaType.INT32){int[] ints=new int[data.length];for(int i=0;i<data.length;i++)ints[i]=(int)data[i];tensor=OnnxTensor.createTensor(env,IntBuffer.wrap(ints),new long[]{1,77});}
                else tensor=OnnxTensor.createTensor(env,LongBuffer.wrap(data),new long[]{1,77});
                inputs.put(name,tensor);
            }
            try(OrtSession.Result r=text.run(inputs)){float[] a=output(r,"text_embeds");queries.put(q,a);return a;}
        }finally{for(OnnxTensor t:inputs.values())t.close();}
    }
    private float[] encodeImage(float[] data)throws Exception{
        try(OnnxTensor t=OnnxTensor.createTensor(env,FloatBuffer.wrap(data),new long[]{1,3,256,256});
            OrtSession.Result r=vision.run(Collections.singletonMap("pixel_values",t))){return output(r,"image_embeds");}
    }
    private static byte[] imageBytes(String address)throws Exception{
        URL u=new URL(address);
        for(int i=0;i<4;i++){
            if(!"https".equals(u.getProtocol())&&!"http".equals(u.getProtocol()))throw new IOException("Geçersiz görsel.");
            for(InetAddress ip:InetAddress.getAllByName(u.getHost()))if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress())throw new IOException("Yerel adres reddedildi.");
            HttpURLConnection c=Search.open(u.toString());c.setConnectTimeout(3500);c.setReadTimeout(4500);c.setInstanceFollowRedirects(false);
            try{
                int code=c.getResponseCode();
                if(code>=300&&code<400&&c.getHeaderField("Location")!=null){u=new URL(u,c.getHeaderField("Location"));continue;}
                if(code!=200)throw new IOException("Görsel alınamadı.");
                try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buf=new byte[16384];int n;
                    while((n=in.read(buf))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();if(out.size()+n>4000000)throw new IOException("Önizleme çok büyük.");out.write(buf,0,n);}
                    return out.toByteArray();
                }
            }finally{c.disconnect();}
        }
        throw new IOException("Çok fazla yönlendirme.");
    }
    static final class Sample {final float[] vector;final long hash;Sample(float[] v,long h){vector=v;hash=h;}}
    private Sample sample(String url,boolean embed)throws Exception{
        String key=(embed?"ai:":"hash:")+url;Sample old=cache.get(key);if(old!=null)return old;
        byte[] data=imageBytes(url);
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,o);
        if(o.outWidth<96||o.outHeight<96)throw new IOException("Küçük görsel.");
        o.inJustDecodeBounds=false;o.inSampleSize=1;
        while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>1024)o.inSampleSize*=2;
        Bitmap raw=BitmapFactory.decodeByteArray(data,0,data.length,o);if(raw==null)throw new IOException("Görsel çözülemedi.");
        Bitmap tiny=null,crop=null,scaled=null;
        try{
            tiny=Bitmap.createScaledBitmap(raw,9,8,true);long hash=0;int k=0;
            for(int y=0;y<8;y++)for(int x=0;x<8;x++){if(gray(tiny.getPixel(x,y))>gray(tiny.getPixel(x+1,y)))hash|=1L<<k;k++;}
            float[] v=null;
            if(embed){
                int side=Math.min(raw.getWidth(),raw.getHeight());
                crop=Bitmap.createBitmap(raw,(raw.getWidth()-side)/2,(raw.getHeight()-side)/2,side,side);
                scaled=Bitmap.createScaledBitmap(crop,256,256,true);
                int[] pixels=new int[256*256];scaled.getPixels(pixels,0,256,0,0,256,256);
                float[] input=new float[3*pixels.length];
                for(int i=0;i<pixels.length;i++){int c=pixels[i];input[i]=Color.red(c)/255f;input[i+pixels.length]=Color.green(c)/255f;input[i+2*pixels.length]=Color.blue(c)/255f;}
                v=encodeImage(input);
            }
            Sample result=new Sample(v,hash);cache.put(key,result);return result;
        }finally{if(tiny!=null&&tiny!=raw)tiny.recycle();if(scaled!=null&&scaled!=crop&&scaled!=raw)scaled.recycle();if(crop!=null&&crop!=raw)crop.recycle();raw.recycle();}
    }
    private static int gray(int c){return (Color.red(c)*30+Color.green(c)*59+Color.blue(c)*11)/100;}
    private static double cosine(float[] a,float[] b){if(a.length!=b.length)return -1;double s=0;for(int i=0;i<a.length;i++)s+=a[i]*b[i];return s;}
    JSONArray rank(JSONArray rows,String q,String mode,boolean useAI,boolean duplicates,Set<Long> seen,Progress p,java.util.function.Consumer<JSONArray> emit)throws Exception{
        boolean active=useAI&&installed()&&!mode.equals("fast");
        float[] target=null;
        if(active){load();target=query("a photo of "+q);}
        final boolean sportsName=Arrays.asList("ronaldo","messi").contains(Relevance.norm(q));
        float[] sports=active&&sportsName?query("a football soccer player, sports portrait or football illustration"):null;
        List<JSONObject> candidates=new ArrayList<>();for(int i=0;i<rows.length();i++)candidates.add(rows.getJSONObject(i));
        candidates.sort((a,b)->Double.compare(Relevance.score(q,b),Relevance.score(q,a)));
        JSONArray result=new JSONArray(),batch=new JSONArray();
        int evaluated=0,limit=mode.equals("ultra")?100:mode.equals("strict")?65:45;
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(55);
        for(JSONObject row:candidates){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            Double visual=null;Sample sample=null;
            // A name must have metadata evidence; never use image vectors to identify a person.
            boolean name=Relevance.strictName(q);
            if(name&&!Relevance.admit(q,row,mode,null))continue;
            if((active||duplicates)&&evaluated<limit&&System.nanoTime()<deadline){
                evaluated++;
                try{sample=sample(row.optString("thumb",row.optString("url")),active&&(!name||sportsName));if(active&&sample.vector!=null)visual=cosine(name?sports:target,sample.vector);}catch(Exception ignored){}
            }
            if(!Relevance.admit(q,row,mode,visual))continue;
            if(sportsName&&visual!=null&&visual<.12&&(mode.equals("strict")||mode.equals("ultra")))continue;
            if(duplicates&&sample!=null){
                boolean dup=false;for(long h:seen)if(Long.bitCount(h^sample.hash)<=4){dup=true;break;}if(dup)continue;seen.add(sample.hash);
                if(seen.size()>1500)seen.remove(seen.iterator().next());
            }
            row.put("relevance",sportsName&&visual!=null?.8*Relevance.rank(q,row,null)+.2*Math.max(0,Math.min(1,(visual-.12)/.20)):Relevance.rank(q,row,visual));row.put("aiChecked",visual!=null);
            result.put(row);batch.put(row);
            if(batch.length()>=8){emit.accept(batch);batch=new JSONArray();}
            if(evaluated>0&&evaluated%10==0&&sample!=null)p.update("Görseller değerlendiriliyor · "+evaluated);
        }
        if(batch.length()>0)emit.accept(batch);
        return result;
    }
    private void closeSessions(){if(text!=null)try{text.close();}catch(Exception ignored){}if(vision!=null)try{vision.close();}catch(Exception ignored){}text=null;vision=null;tokenizer=null;queries.clear();}
    @Override public void close(){closeSessions();cache.clear();}
}
