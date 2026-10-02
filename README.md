# Pusula — Android

Egemen'in yaşam asistanının (Para / Pusula, `para.egemen.tr`) telefon uygulaması. Play Store'a konmaz,
doğrudan telefona kurulur. Asıl asistan sunucuda (`~/projects/para`); bu uygulama ona açılan kapı.

## Kurulum (telefonda)
1. GitHub → bu depo → **Releases** → en üstteki sürüm → `pusula-…apk`'ya dokun.
2. "Bilinmeyen kaynaklardan yükleme" izni sorulursa tarayıcıya ver, kur.
3. Uygulamayı aç, panel şifrenle bir kez giriş yap (oturum 30 gün kalır).
4. **Asistan tuşu:** Ayarlar → Uygulamalar → Varsayılan uygulamalar → **Dijital asistan uygulaması** →
   Pusula. Artık ana ekran/güç tuşuna uzun basınca Pusula konuşma modunda açılır.

Güncelleme: yeni sürümün APK'sını kurman yeterli, eskisinin üstüne yüklenir (aynı imza).

## Sürüm 0.1 (şu an)
- Paneli uygulama içinde açar; mikrofon izni uygulamadan verilir.
- Asistan tuşu / kulaklık tuşu → `/ses?mod=konusma`: eller serbest konuşma dokunmadan başlar.

## Sıradakiler (yol haritası §4.6.2, Faz 3)
- Health Connect: adım, uyku, kilo, vücut analizi → sunucunun `/veri` ucu
- Konum (OwnTracks yerine)
- "Hey Pusula" uyandırma kelimesi (telefonda yerel algılama)
- Bildirimler

## Derleme
GitHub Actions her push'ta derler (`.github/workflows/build.yml`). Yerelde: JDK 17 + Android SDK,
`gradle :app:assembleRelease`. İmza anahtarı `app/pusula.keystore` depoda — depo **özel** kalmalı.
