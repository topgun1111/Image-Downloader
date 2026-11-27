package app.imagedownloader;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
public class ApkDeltaTest {
    private void run(byte[] encoded,byte[] expected,boolean valid)throws Exception{
        File dir=Files.createTempDirectory("delta-test").toFile();
        File base=new File(dir,"old"),patch=new File(dir,"patch"),target=new File(dir,"new");
        try{
            Files.write(base.toPath(),new byte[]{10,20,30,40});
            Files.write(patch.toPath(),encoded);
            try{
                ApkDelta.apply(base,patch,target,expected.length);
                if(!valid)fail("Malformed patch accepted");
                assertArrayEquals(expected,Files.readAllBytes(target.toPath()));
            }catch(IOException e){if(valid)throw e;}
        }finally{target.delete();patch.delete();base.delete();dir.delete();}
    }
    private byte[] patch(int offset,boolean trailing)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream d=new DataOutputStream(bytes);
        d.writeInt(0x49445031);d.writeInt(4);
        d.writeByte(1);d.writeInt(2);d.writeInt(offset);
        d.writeByte(2);d.writeInt(2);d.write(new byte[]{1,2});d.writeByte(0);
        if(trailing)d.writeByte(99);
        return bytes.toByteArray();
    }
    @Test public void reconstructs()throws Exception{run(patch(1,false),new byte[]{20,30,1,2},true);}
    @Test public void rejectsInvalidOffset()throws Exception{run(patch(3,false),new byte[4],false);}
    @Test public void rejectsTrailingBytes()throws Exception{run(patch(1,true),new byte[4],false);}
    @Test public void rejectsTruncation()throws Exception{run(java.util.Arrays.copyOf(patch(1,false),10),new byte[4],false);}
    @Test public void rejectsWrongTargetLength()throws Exception{run(patch(1,false),new byte[5],false);}
}
