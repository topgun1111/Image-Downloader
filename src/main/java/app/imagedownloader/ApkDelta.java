package app.imagedownloader;

import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;

/** IDP1: byte-exact copy/literal operations, independent of APK ZIP alignment. */
final class ApkDelta {
    static final int MAX=512*1024*1024;
    static String sha256(File file)throws Exception{
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new BufferedInputStream(new FileInputStream(file))){
            byte[] b=new byte[65536];int n;
            while((n=in.read(b))!=-1){check();md.update(b,0,n);}
        }
        StringBuilder hex=new StringBuilder("sha256:");
        for(byte b:md.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
        return hex.toString();
    }
    private static void check()throws InterruptedIOException{
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
    }
    static void apply(File base,File patch,File target,long expected)throws IOException{
        if(expected<=0||expected>MAX||base.length()<=0||base.length()>MAX||patch.length()<=0||patch.length()>MAX)
            throw new IOException("Geçersiz yama boyutu.");
        try(RandomAccessFile old=new RandomAccessFile(base,"r");
            DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(patch)));
            OutputStream out=new BufferedOutputStream(new FileOutputStream(target))){
            if(in.readInt()!=0x49445031||in.readInt()!=expected)throw new IOException("Geçersiz yama başlığı.");
            long written=0;int ops=0;byte[] buf=new byte[65536];
            while(true){
                check();int type=in.readUnsignedByte();
                if(type==0)break;
                if(++ops>1000000||type!=1&&type!=2)throw new IOException("Geçersiz yama işlemi.");
                int length=in.readInt();
                if(length<=0||length>expected-written)throw new IOException("Yama çıktı sınırı aşıldı.");
                if(type==1){
                    int offset=in.readInt();
                    if(offset<0||(long)offset+length>old.length())throw new IOException("Yama kaynak sınırı aşıldı.");
                    old.seek(offset);
                }
                int remaining=length;
                while(remaining>0){
                    check();int count=Math.min(remaining,buf.length);
                    if(type==1)old.readFully(buf,0,count);else in.readFully(buf,0,count);
                    out.write(buf,0,count);remaining-=count;
                }
                written+=length;
            }
            if(written!=expected||in.read()!=-1)throw new IOException("Eksik veya geçersiz yama.");
        }
    }
    // CLI used by CI to check the exact decoder used in the Android app.
    public static void main(String[] args)throws Exception{
        if(args.length!=4)throw new IllegalArgumentException("base patch target size");
        apply(new File(args[0]),new File(args[1]),new File(args[2]),Long.parseLong(args[3]));
    }
}
