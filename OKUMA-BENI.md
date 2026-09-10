# Xwendevan · sesli belge okuyucu (Android)

Bir **Cybergah** çalışması · [cybergah.com/tr](https://cybergah.com/tr/)

PDF · DOCX · EPUB · TXT belgelerini, **uygulamanın içinde gelen** Türkçe ve
Kurmancî **Piper (VITS/ONNX)** ses modelleriyle telefonda, tamamen **çevrimdışı**
seslendiren okuyucu. İndirme yok, klasör seçme yok: kurun ve okutun.

Taranmış PDF'leri **OCR** ile tanır, yazdığınız metni okuyup **MP3**'e çevirir ve
istenirse **sistemin konuşma motoru** olur — böylece Kurmancî ses telefonun her
yerinde kullanılabilir.

**PDF'ler olduğu gibi açılır** — sayfa düzeni, yazı tipleri, görseller bozulmaz;
okunan cümle sayfanın kendi görüntüsü üzerinde işaretlenir. Metin parça parça
çözülür: ilk cümlenin sesi hazır olur olmaz okuma başlar, kalanı okuma sürerken
arka planda sese çevrilmeye devam eder.

---

## 1. Kurulum

`apk/` klasöründe iki dosya var:

| Dosya | Kimin için |
|---|---|
| `Xwendevan-1.5-arm64-v8a.apk` | **Normal seçim** — 2016 sonrası tüm telefonlar (317 MB — sesler ve OCR dahil) |
| `Xwendevan-1.5-armeabi-v7a.apk` | Çok eski / 32-bit telefonlar (306 MB — sesler ve OCR dahil) |

APK'yı telefona kopyalayıp dosya yöneticisinden açın; "bilinmeyen kaynak"
uyarısında bu uygulamaya izin verin. Kabloyla:

```
adb install -r apk/Xwendevan-1.5-arm64-v8a.apk
```

`telefona-kur.ps1` betiği aynı işi kabloyla yapar.

## 2. Ses modelleri

Modeller **APK'nın içinde** gelir; indirme, klasör seçme veya bilgisayardan
kopyalama yok. Kutudan çıkan sesler:

| Ses | Dil | Not |
|---|---|---|
| Berfin & Renas | Kurmancî (`ku_TR`) | tek modelde iki konuşmacı, tek dokunuşla geçilir |
| Yelda | Türkçe (`tr_TR`) | kadın |
| Fahrettin | Türkçe (`tr_TR`) | erkek |
| Murat | Türkçe (`tr_TR`) | erkek |

İlk açılışta uygulama dile uygun sesi kendisi seçer (arayüz Kurmancî ise
Kurmancî, değilse Türkçe). Bir ses ilk kez kullanılacağı zaman APK'dan
uygulamanın kendi belleğine açılır — birkaç saniye sürer, bir kez olur, ekranda
yüzdesi görünür. Sonrasında **Sesler** panelinden tek dokunuşla geçilir.

Açılan modelin sonuna, sherpa-onnx'in beklediği ONNX üstverisi (~200 bayt)
eklenir. Piper bu alanları yok saydığı için model bilgisayarda da aynı şekilde
çalışmaya devam eder.

## 3. Metin okut ve MP3

Belge açmaya gerek yok: ana ekranın üstündeki **Metin okut** düğmesiyle açılan
ekrana istediğiniz metni yazın.

- **Oku** — yazdığınız metni seçili sesle, cümle cümle seslendirir; ses, konuşmacı
  ve hız aynı ekranda değiştirilir.
- **MP3 kaydet** — aynı metni MP3'e çevirip telefonun
  `İndirilenler/Xwendevan` klasörüne yazar (96 kbit/s mono). Bittiğinde dosya adı,
  süresi ve boyutu gösterilir; oradan doğrudan **Aç** veya **Paylaş** diyebilirsiniz.
  Kodlama cihazda yapılır — jump3r (LAME'in saf Java portu), internet yok.
- Yazdığınız metin uygulamadan çıksanız da saklanır.

## 4. Okuma kolaylıkları

**Uyku zamanlayıcısı** — okuma menüsünden 15/30/45/60 dakika, "paragraf bitince"
veya "belge bitince". Süre dolunca ses üç saniyede kısılır ve okuma durur;
kalan süre menüde ve Ayarlar'da görünür.

**Yer imi ve arama** — menüden **Yer imi ekle** o an okunan cümleyi işaretler,
**Yer imleri** listesinden dokununca oraya döner. **Belgede ara** kelimeyi arar,
sonuca dokununca okuma oradan sürer.

**Belgeyi MP3 yap** — açık belgenin tamamını tek dosya ya da 15/30 dakikalık
parçalar hâlinde `İndirilenler/Xwendevan` klasörüne yazar; sırasında iptal
edilebilir.

**Telaffuz sözlüğü** (Ayarlar) — "TBMM → Te Be Em Me" gibi kurallar; kısaltma ve
özel adlar seslendirilirken düzeltilir, belgenin metni değişmez. Kutudan yedi
örnek kural gelir.

**Dinleme istatistiği** (Ayarlar) — bugün / bu hafta / toplam süre, son yedi
günün sütun grafiği ve en çok dinlenen belgeler.

**Dil algılama** — belgenin ilk paragraflarına bakıp Kurmancî mi Türkçe mi
olduğunu kestirir ve sesi kendiliğinden ona göre seçer (Ayarlar'dan kapatılır).

**Taranmış PDF (OCR)** — metin katmanı yoksa uygulama sorar, kabul ederseniz
sayfaları cihazda Tesseract ile tanır (Türkçe + Kurmancî veri dosyaları APK'da).
Sonuç saklanır; belgeyi ikinci açışta tanıma tekrarlanmaz.

**Paylaş ve pano** — başka uygulamadan **Paylaş → Xwendevan** ya da metni seçip
"Xwendevan" dediğinizde metin doğrudan "Metin okut" ekranına düşer; aynı ekranda
**Yapıştır** düğmesi panodaki metni alır.

## 5. Sistemin konuşma motoru

Ayarlar → Erişilebilirlik → **Metin okuma çıktısı** listesinden "Xwendevan
(Kurmancî · Türkçe)" seçilirse gömülü sesler tüm telefonda kullanılır: TalkBack,
tarayıcıdaki "sesli oku", çeviri uygulamaları… Kurmancî için sistemde başka
seçenek olmadığından bu, erişilebilirlik açısından uygulamanın en geniş etkili
özelliği.

Motor kendi işleminde (`:tts`) çalışır; okuma ekranındaki modelle çakışmaz.

## 6. Ekranlar

`ekranlar/` klasöründe emülatörden alınmış görüntüler var.

**Açılış** — uygulama açılırken logo yerine oturur, altında ses dalgası çubukları
oynar, en altta Cybergah künyesi belirir. Ekrana dokunursanız beklemeden geçer.

**Ana ekran (kitaplık)** — üstte üç yatay raf, altında bütün belgeler:

| Raf | İçerik |
|---|---|
| **Devam edenler** | Başlanmış ama bitmemiş belgeler (%1–97) |
| **Favoriler** | Yıldızladığınız belgeler |
| **En çok okunanlar** | Dinleme süresi + açılma sayısına göre sıralanır |

Raflar boşken görünmez. Altta **Tüm belgeler** ızgarası: PDF'in ilk sayfasından
kapak, tür rozeti, okuma yüzdesi ve son açılma tarihi. Karta dokunun, kaldığınız
yerden devam eder; **basılı tutunca** favoriye ekleme/çıkarma ve kitaplıktan
kaldırma seçenekleri çıkar (dosya silinmez).

**Okuma ekranı**
- PDF **olduğu gibi** çizilir; okunan cümle sayfa üzerinde renkli işaretlenir.
- **Üç kez dokunma:** belgede herhangi bir yere üç kez dokunun, okuma oradan
  devam eder. Boş bir yere dokunursanız o sayfanın başından sürdürür.
- **Tek dokunma:** üst ve alt çubukları gizler/gösterir (tam ekran okuma).
- Üstte her zaman görünen **ses çubuğu**: dokununca ses modeli listesi açılır.
  Çift konuşmacılı modellerde (ör. Kurmancî: Berfin / Renas) konuşmacılar
  yan yana düğme olarak durur, tek dokunuşla geçilir.
- Altta: paragraf geri · cümle geri · oynat/duraklat · cümle ileri · paragraf
  ileri, cümle çubuğu ve hız (0,6x – 2,0x).
- Durum satırı: kaçıncı cümle, yüzde, tahmini kalan süre ve **hazır: +N**
  (arka planda önceden sese çevrilmiş cümle sayısı).
- Ekran kapalıyken de okur: bildirim, kilit ekranı ve kulaklık düğmeleri çalışır.

**Ayarlar**
- Görünüm: tema (sistem/açık/koyu), **uygulama dili**, yazı boyutu, vurgu rengi
- Okuma: okunan yeri takip etme, ekran açık kalsın, varsayılan hız
- Ses: ses modeli, konuşmacı
- Hakkında: sürüm, gömülü sesler, kullanılan bileşenler ve Cybergah künyesi
  (karta dokununca [cybergah.com/tr](https://cybergah.com/tr/) açılır; arayüz
  Kurmancî ise `/ku/`)

**Diller:** Türkçe · Kurmancî · English · العربية (sağdan sola desteklenir).
Uygulama dili sistemden bağımsız değiştirilebilir.

## 7. Metin bütünlüğü

- TXT dosyalarında kodlama otomatik seçilir: BOM'lu UTF-8/UTF-16, katı UTF-8,
  olmazsa windows-1254 / windows-1256 / ISO-8859-9 adayları Türkçe, Kurmancî ve
  Arapça harflerine göre puanlanır; en temiz sonuç kullanılır.
- PDF ve Word'den gelen bağ harfleri (ﬁ, ﬂ, ﬃ …) okunabilir karşılıklarına
  açılır; yumuşak tire, sıfır genişlikli boşluk gibi görünmez karakterler
  ayıklanır. Harfler değiştirilmez.
- PDF'te satır sonu tireleri birleştirilir, sayfa numaraları ayıklanır,
  başlıklar kendi paragrafı olur — ama **gösterilen sayfa hiç değişmez**,
  bu düzeltmeler yalnızca seslendirilen metne uygulanır.

## 8. Bilinen sınırlar

- Eski `.doc` (Word 97-2003) desteklenmiyor — `.docx` veya `.txt` olarak kaydedin.
- Taranmış PDF'lerde OCR yapılır ama tanınan metin sayfa üzerinde işaretlenmez
  (harf konumları çıkarılmaz); sayfa numarası takibi çalışır.
- OCR yalnızca Latin harfli Türkçe ve Kurmancî için hazır gelir; Arapça harfli
  taranmış belgeler tanınmaz.
- EPUB'ta metin akış olarak okunur: kapak, resimler ve dipnot bağlantıları
  gösterilmez, bölüm sırası korunur.
- Dil algılama karışık dilli belgelerde belgenin genel diline karar verir;
  cümle cümle ses değiştirmez (iki modeli aynı anda bellekte tutmak gerekirdi).
- PDF'te yakınlaştırma (pinch-zoom) henüz yok; sayfa ekran genişliğine oturur.

## 9. Kaynaktan derleme

```
gradle assembleRelease
```

- Android SDK 34, JDK 17, Gradle 8.7+, AGP 8.5.2, minSdk 24
- Ses motoru: `app/libs/sherpa-onnx-1.13.7.aar` (sherpa-onnx + ONNX Runtime + espeak-ng)
- Gömülü sesler: `app/src/main/assets/voices/` (`<id>.onnx` + `<id>.onnx.json`).
  Yeni ses eklemek için ikiliyi buraya koyup `tts/Voices.kt` içindeki `CATALOG`
  listesine bir satır ekleyin. `.onnx` dosyaları APK'da sıkıştırılmadan durur
  (`androidResources { noCompress += "onnx" }`).
- PDF metni: `com.tom-roush:pdfbox-android` · PDF çizimi: Android `PdfRenderer`
- MP3: `de.sciss:jump3r` (LAME’in saf Java portu, yerel kütüphane yok)
- OCR: `com.github.adaptech-cz.Tesseract4Android` (JitPack) + `assets/tessdata/`
  içindeki `tur` ve `kmr` veri dosyaları (~8 MB)
- Çıktılar: `app/build/outputs/apk/release/`

### Kod düzeni

| Dosya | İş |
|---|---|
| `data/Library.kt` | Kitaplık kaydı, okuma ilerlemesi, PDF kapakları |
| `doc/PdfSource.kt` | Sayfaların görüntüye çizilmesi, önbellek |
| `doc/PdfTextExtractor.kt` | Sayfa metni + her karakterin konumu (vurgulama bunun sayesinde) |
| `doc/DocumentLoader.kt` | DOCX, EPUB ve TXT çözümü |
| `doc/Html.kt` | XHTML → paragraf (EPUB bölümleri) |
| `doc/Ocr.kt` | Taranmış PDF için Tesseract; tanınan metnin saklanması |
| `doc/LangDetect.kt` | Kurmancî / Türkçe / Arapça kestirimi |
| `doc/TextFix.kt` | Kodlama seçimi, bağ harfleri, görünmez karakterler |
| `doc/Sentencizer.kt` | Cümlelere bölme (kısaltma, ondalık, madde no ayrımı) |
| `player/ReaderPlayer.kt` | Sentez + çalma + konum izleme (3 iş parçacığı) |
| `player/PlayerService.kt` | Ön plan servisi, bildirim, ses odağı, MediaSession |
| `player/SleepTimer.kt` | Uyku zamanlayıcısı (süre · paragraf · belge sonu) |
| `data/Bookmarks.kt` · `data/Stats.kt` | Yer imleri ve dinleme istatistiği |
| `tts/Pronunciation.kt` | Telaffuz sözlüğü, sentezden önce metin düzeltme |
| `tts/XwendevanTtsService.kt` | Sistem geneli konuşma motoru (`:tts` işlemi) |
| `ui/StatsActivity.kt` · `ui/PronunciationActivity.kt` | İstatistik ve sözlük ekranları |
| `tts/TtsEngine.kt` · `tts/Voices.kt` | Gömülü ses kataloğu, modeli APK'dan açma, tokens.txt üretimi, model yükleme |
| `tts/OnnxMeta.kt` | Piper modeline sherpa-onnx üstverisi ekleme |
| `ui/SplashActivity.kt` | Açılış animasyonu |
| `ui/HomeActivity.kt` | Ana ekran: raflar (devam edenler · favoriler · en çok okunanlar) ve kitaplık |
| `ui/ReaderActivity.kt` | Okuma ekranı, üç dokunuş, takip, denetimler |
| `ui/PdfPageAdapter.kt` · `ui/PdfPageView.kt` | Sayfa çizimi ve üzerine vurgulama |
| `ui/SpeakActivity.kt` | Metin okut ekranı, MP3 kaydı |
| `audio/Mp3Writer.kt` · `audio/AudioExport.kt` | MP3 kodlama (jump3r/LAME) ve İndirilenler’e yazma |
| `ui/SettingsActivity.kt` · `ui/AboutActivity.kt` | Ayarlar ve bilgilendirme |

## Lisans

Xwendevan özgür yazılımdır. espeak-ng bileşeni GPL-3.0 olduğu için uygulamanın
tamamı **GPL-3.0 veya sonrası** koşullarıyla dağıtılır; tam metin `LICENSE`
dosyasındadır.

Bileşenler: sherpa-onnx (Apache-2.0) · ONNX Runtime (MIT) · espeak-ng (GPL-3.0) ·
Piper (MIT) · PDFBox (Apache-2.0) · Material Components (Apache-2.0) ·
jump3r/LAME (LGPL-2.1) · Tesseract4Android + Tesseract (Apache-2.0) ·
Leptonica (BSD-2). OCR veri dosyaları (`tur`, `kmr`) tessdata_fast'ten,
Apache-2.0.

Ses modelleri ayrı lisanslıdır:

| Ses | Lisans | Kaynak |
|---|---|---|
| Kurmancî · Berfin & Renas | CC BY-NC 4.0 | rojanu/piper-ku-berfin-renas-medium |
| Türkçe · Murat | CC BY-NC-SA 4.0 (dfki veri kümesi) | rhasspy/piper-voices |
| Türkçe · Yelda, Fahrettin | Piper topluluk modelleri | model kartlarında lisans belirtilmemiş |

**NC (ticari olmayan)** koşulu nedeniyle bu APK ve ürettiği ses dosyaları
satılamaz; eğitim, erişilebilirlik ve kişisel kullanım serbesttir.

---

Xwendevan bir **Cybergah** çalışmasıdır — *Dijital karmaşada özgürlük
karargahınız.* Türkçe ve Kurmancî: [cybergah.com/tr](https://cybergah.com/tr/) ·
[cybergah.com/ku](https://cybergah.com/ku/)
