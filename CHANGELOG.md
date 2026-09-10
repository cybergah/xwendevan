# Değişiklikler

Sürüm numaraları `app/build.gradle.kts` içindeki `versionName` ile aynıdır.

## 1.5 — 2026-09-10

**Okuma kolaylıkları**
- **Uyku zamanlayıcısı**: 15/30/45/60 dakika, "paragraf bitince", "belge bitince".
  Süre dolunca ses üç saniyede kısılıp durur.
- **Yer imi ve arama**: cümleyi işaretleme, yer imi listesi, belge içinde arama.
- **Belgeyi MP3 yap**: açık belgenin tamamı tek dosya ya da 15/30 dakikalık
  parçalar hâlinde `İndirilenler/Xwendevan` klasörüne.
- **Telaffuz sözlüğü**: kısaltma ve özel adların okunuşu (kutudan yedi kural).
- **Dinleme istatistiği**: bugün / bu hafta / toplam, son yedi günün grafiği,
  en çok dinlenen belgeler.

**Yeni yetenekler**
- **EPUB** desteği (zip + OPF spine sırası + XHTML çözümü).
- **OCR**: taranmış PDF'ler cihazda Tesseract ile tanınır (Türkçe + Kurmancî);
  sonuç saklanır, ikinci açılışta tekrarlanmaz.
- **Sistem geneli konuşma motoru**: Ayarlar → Erişilebilirlik → Metin okuma
  çıktısı listesinden seçilince Kurmancî ve Türkçe sesler tüm telefonda çalışır.
- **Dil algılama**: belge Kurmancî ise Kurmancî sese kendiliğinden geçilir.
- **Paylaş ve pano**: başka uygulamadan paylaşılan ya da seçilen metin doğrudan
  "Metin okut" ekranına düşer; panodan yapıştırma düğmesi.

## 1.4 — 2026-09-10

- **Açılış ekranı**: logo, ses dalgası animasyonu, Cybergah künyesi.
- **Ana ekran rafları**: "Devam edenler", "Favoriler", "En çok okunanlar".
- Karta basılı tutunca favori ekleme/çıkarma; kartlarda yıldız rozeti.
- Dinleme süresi ve açılma sayısı kitaplıkta tutulmaya başlandı.
- Marka renk paleti tamamlandı (kırmızı tonlar; mor kalan denetimler düzeltildi).

## 1.3 — 2026-09-10

- **Metin okut** ekranı: yazdığınız metni seslendirir.
- **MP3 kaydetme**: cihazda jump3r (LAME) ile kodlama, `İndirilenler/Xwendevan`.
- **Hakkında** ekranı yeniden yazıldı: ne işe yarar, gizlilik, lisanslar ve
  Cybergah künyesi; yazıyla yazılan damga yerine gerçek logo.
- GPL-3.0 lisans metni depoya eklendi.

## 1.2 — 2026-09-10

- Türkçe ve Kurmancî ses modelleri **uygulamaya gömüldü**; klasörden içe aktarma
  ve klasör tarama kaldırıldı.
- Ses ilk kullanımda APK'dan cihaza açılır (yüzdeli ilerleme).
- Arayüz diline göre başlangıç sesi seçimi.
- Cybergah kimliği: kırmızı vurgu rengi, künye, [cybergah.com](https://cybergah.com/tr/) bağlantısı.

## 1.1

- İlk sürüm: PDF · DOCX · TXT sesli okuma, PDF sayfası üzerinde vurgulama,
  üç dokunuşla konum seçme, arka planda okuma, dört dilli arayüz.
