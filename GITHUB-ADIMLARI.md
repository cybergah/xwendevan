# GitHub — yayın ve güncelleme adımları

Depo: <https://github.com/cybergah8-lang/xwendevan>

İlk yayın yapıldı: kod gönderildi, `v1.5.0` etiketi atıldı, APK'lar Release'e
yüklendi. Aşağıdakiler sonraki güncellemeler için.

---

## 1 · Yeni sürüm çıkarma

```bash
# 1. Sürüm numarasını yükselt
#    app/build.gradle.kts → versionCode = 7, versionName = "1.6"
#    CHANGELOG.md → yeni başlık

# 2. Derle ve dene
./gradlew :app:assembleRelease

# 3. Gönder
git add -A
git commit -m "1.6 — kısa özet"
git push

# 4. Etiketi at: workflow APK'ları derleyip Release'i kendisi oluşturur
git tag v1.6.0
git push origin v1.6.0
```

Etiketi atınca **Actions** sekmesinde `Release` iş akışı çalışır; bitince
APK'lar **Releases** sayfasında görünür.

APK'ları elle yüklemek isterseniz:

```bash
gh release create v1.6.0 \
  apk/Xwendevan-1.6-arm64-v8a.apk \
  apk/Xwendevan-1.6-armeabi-v7a.apk \
  --title "Xwendevan 1.6" --notes-file surum-notu.md
```

---

## 2 · İmza anahtarı (önemli)

Uygulama `xwendevan-release.jks` ile imzalanır. **Bu dosya depoda değildir**
(`.gitignore`) ve kaybolursa aynı uygulamayı güncelleyemezsiniz — kullanıcılar
"uygulama zaten yüklü, imza uyuşmuyor" hatası alır.

- Anahtar dosyası: `xwendevan-release.jks`
- Parolalar: `keystore.properties`
- **İkisini de yedekleyin** (şifreli bir yere; e-postaya değil).

Parmak izi:

```
SHA-256  28:79:63:54:32:B5:50:5B:CB:0B:4C:2F:38:F2:68:FA:2D:EB:25:82:0C:C1:3E:91:55:46:6C:5F:A6:5A:33:3E
```

### GitHub Actions'ın da imzalaması için

Depo → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Değer |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 xwendevan-release.jks` çıktısı |
| `KEYSTORE_PASSWORD` | `keystore.properties` içindeki `storePassword` |
| `KEY_ALIAS` | `xwendevan` |
| `KEY_PASSWORD` | `keystore.properties` içindeki `keyPassword` |

Base64'ü almak için (bu klasörde):

```bash
base64 -w0 xwendevan-release.jks > anahtar-base64.txt
```

Secret'ları girdikten sonra `anahtar-base64.txt` dosyasını **silin**.
Secret yoksa iş akışı yine çalışır ama APK hata ayıklama anahtarıyla imzalanır —
o APK'yı yayınlamayın.

---

## 3 · Depo hakkında

- Ses modelleri (255 MB) ve OCR verisi depodadır; klonlama bu yüzden büyüktür.
  `git clone --depth 1` ile geçmişsiz, daha hızlı klonlanır.
- APK'lar depoda tutulmaz (`apk/*.apk` yok sayılır), yalnızca Release'e yüklenir.
- `local.properties`, `keystore.properties` ve `*.jks` asla gönderilmez.

---

## 4 · Sürüm notu şablonu

```markdown
## Yenilikler
- …

## Düzeltmeler
- …

## Kurulum
`Xwendevan-1.6-arm64-v8a.apk` — 2016 sonrası telefonlar
`Xwendevan-1.6-armeabi-v7a.apk` — eski / 32-bit telefonlar

Ses modelleri ve OCR verisi APK'nın içindedir; sonrasında hiçbir şey indirilmez.
```
