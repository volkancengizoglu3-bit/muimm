# MU Boss Bot (muimm)

MU Immortal Raklion kırmızı boss avı için Android otomasyon uygulaması.
[Goldingen](https://github.com/volkancengizoglu3-bit/goldingen) ile aynı mantık, sarı yerine kırmızı bosslar için.

- Rota: Raklion 2 kanal 1-2-3, sonra Raklion 1 kanal 1-2-3, sonra baştan.
- Haritada canlı boss ikonu (parlak kırmızı-turuncu alev) + yeşil oyuncu imleci okunur; ölü boss ikonu soluk olduğu için görünmez.
- Canlı boss'a haritadan dokunulur, imleç ikona varınca harita kapanır ve oto saldırı açılır.
- Boss ölümü sağ üstteki mini haritadan (ikon soluklaşınca) anlaşılır.
- Haritalar arası geçiş chat'teki koordinat bağlantılarıyla, kanal geçişi Wire/Switch Line ile yapılır.
- Harita başlığı ("Raklion 2 [Line 3]") okunarak bulunulan yer doğrulanır.
- ATK/DEF (sol üst) okunur; ikisi de minimumun altındaysa buff noktasına gidilir.
- Ölüm ekranında Free Revival'a basılır, rota kaldığı yerden sürer.
- Sağda kapatılabilir respawn sayaç paneli ve öldürülen boss sayacı.

APK: GitHub Actions her push'ta derler, Releases sayfasından indirilir.
