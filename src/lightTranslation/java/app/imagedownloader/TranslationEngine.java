package app.imagedownloader;

/** No ML Kit libraries or remote translation services in the lightweight build.
 * Locale search and common built-in translations remain available.
 */
final class TranslationEngine {
    static boolean available(){return false;}
    static String translate(String body,String target,boolean wifi){return null;}
}
