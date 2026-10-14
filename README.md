# DinoGram

<img src=".github/dinogram-mark.svg" width="96" alt="DinoGram mark">

DinoGram — DINOV uchun shaxsiy Telegram klienti (Android).

**AyuGram for Android** (Radolyn Labs) asosida qurilgan. AyuGram esa exteraGram va Telegraher asosida, ularning hammasi rasmiy Telegram for Android kodiga tayanadi.

## Imkoniyatlar

- **Ghost mode** — onlayn, "o'qildi" va "yozmoqda" holatlarini yashirish (sozlanadi)
- **O'chirilgan xabarlarni saqlash** — o'chirilgan xabarlar chatda belgi bilan qoladi, media `Download/DinoGram/Saved Attachments` papkasiga nusxalanadi
- **Tahrir tarixi** — xabarning har bir eski varianti saqlanadi
- **Reklamasiz** va **lokal Premium** ko'rinishlari
- DinoGram nomi va ikonkasi (yashil chat-dinozavr)

## DinoGram'da AyuGram'dan farqi

- AyuGram ochiq kodda chiqarmagan ikki klass (`AyuMessageUtils`, `AyuHistoryHook`) mustaqil ravishda qayta yozildi: `TMessagesProj/src/main/java/com/radolyn/ayugram/proprietary/`
- Paket nomi: `uz.dinov.dinogram`, AyuGram bilan yonma-yon o'rnatsa bo'ladi
- Firebase analitika va crash hisobotlari o'chirilgan
- APK GitHub Actions'da yig'iladi, kompyuter kerak emas

## APK'ni yig'ish

1. [my.telegram.org](https://my.telegram.org) → **API development tools** bo'limidan `api_id` va `api_hash` oling.
2. Repozitoriya → **Settings → Secrets and variables → Actions → New repository secret** orqali uchta secret qo'shing:
   - `APP_ID` — api_id (faqat raqam)
   - `APP_HASH` — api_hash
   - `KEYSTORE_PASSWORD` — imzo kaliti paroli (`TMessagesProj/config/dinogram.jks`)
3. **Actions → DinoGram APK → Run workflow** tugmasini bosing va telefon turini tanlang (odatda `arm64`).
4. Yig'ish tugagach, **Releases** bo'limidan `.apk` faylni telefonga yuklab olib o'rnating.

## Ogohlantirish

- Kod Telegram 9.6.6 (2023-yil) asosida. Undan keyingi Telegram funksiyalari bu yerda yo'q.
- Rasmiy bo'lmagan klientlar Telegram qoidalariga zid funksiyalarga ega. Akkaunt cheklanishi xavfi bor.
- Google push xabarnomalari ishlamasligi mumkin. Unda **DinoGram Preferences → DinoGram Push Service** (fonda ishlash) ni yoqing.

## Litsenziya

GPL-2.0. Asl mualliflar: [AyuGram](https://github.com/AyuGram/AyuGram4A) (Radolyn Labs), [exteraGram](https://github.com/exteraSquad/exteraGram), Telegraher, [Telegram for Android](https://github.com/DrKLO/Telegram).
