package app.imagedownloader;
import java.util.concurrent.TimeUnit;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.nl.languageid.*;
import com.google.mlkit.nl.translate.*;
import com.google.mlkit.common.model.DownloadConditions;

/** The full build downloads only the language models requested by the user. */
final class TranslationEngine {
    static boolean available(){return true;}
    static String translate(String body,String target,boolean wifi){
        LanguageIdentifier identifier=null;Translator translator=null;
        try{
            identifier=LanguageIdentification.getClient();
            String source=Tasks.await(identifier.identifyLanguage(body),2,TimeUnit.SECONDS);
            String from=TranslateLanguage.fromLanguageTag(source),to=TranslateLanguage.fromLanguageTag(target);
            if(from==null||to==null||from.equals(to))return null;
            translator=Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build());
            DownloadConditions.Builder conditions=new DownloadConditions.Builder();
            if(wifi)conditions.requireWifi();
            Tasks.await(translator.downloadModelIfNeeded(conditions.build()),5,TimeUnit.SECONDS);
            return Tasks.await(translator.translate(body),3,TimeUnit.SECONDS);
        }catch(InterruptedException e){Thread.currentThread().interrupt();return null;}
        catch(Exception|LinkageError e){return null;}
        finally{if(identifier!=null)identifier.close();if(translator!=null)translator.close();}
    }
}
