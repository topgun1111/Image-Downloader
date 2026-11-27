# Image Downloader

Android 10 ve üzeri için Türkçe görsel arama ve indirme uygulaması.
Bing ve Yandex sonuçlarında **Daha fazla göster** sonraki sayfayı getirir. Önceki seçimler korunur. Google Görseller, isteğe bağlı SerpApi ve Wikimedia Commons yedeği de bulunur. İndirilen fotoğraflar ortak **Download/Google** klasörüne kaydedilir.

Android Studio ile projeyi açıp çalıştırın veya JDK 17, Android SDK 35 ve Gradle 8.9 ile `gradle assembleDebug` komutunu kullanın. GitHub Actions her gönderimde imzalı APK üretir ve Releases bölümünde yayımlar. Artifact yüklemez.

**Güncelleme:** Depo herkese açık olduğunda Ayarlar → Güncellemeleri kontrol et seçeneği erişim anahtarı istemez. Eski anahtar isteyen sürümden geçiş için yeni APK’yı Releases bölümünden bir defa elle yükleyin. Android’in kurulum onayı gerekir. İmzalama anahtarı yalnızca GitHub Actions secret olarak tutulur.

## Çoklu kaynak araması
Filtre düğmesinde 17 kaynak seçilebilir. Aramalar paralel yapılır; kaynak başına en fazla 45 sonuç ve sayfa başına 65 saniyelik genel süre sınırı vardır. Wikimedia Commons ve Openverse herkese açık API'leri kullanır; SerpApi, Pexels ve Pixabay anahtarları isteğe bağlıdır. Diğer kaynaklarda herkese açık web sayfası ve kaynak alan adıyla sınırlandırılmış indeks aramaları denenir. Giriş, CAPTCHA, ücretli içerik ve erişim engelleri aşılmaz. Sonuç alınması sağlayıcının erişimine bağlıdır. Kaynak sayfasından kullanım haklarını kontrol edin. İkon/yer tutucu filtresi URL ve bilinen boyutları kontrol eder; Pinterest için orijinal bağlantı denenir ve gerekirse indekslenmiş görsel kullanılır. Boyut sıralamasında bilinmeyenler sona gelir.
