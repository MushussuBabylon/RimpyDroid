<a id="languages"></a>

<p align="center">
  <img src="docs/logo.jpg" alt="RimpyDroid logo" width="200">
</p>

<h1 align="center">RimpyDroid</h1>

<p align="center">
  🇬🇧 <a href="#english">English</a> |  🇮🇶 <a href="#arabic">العربية</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B-green?style=flat-square" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Version-1.0-blue?style=flat-square" alt="Version 1.0">
  <img src="https://img.shields.io/badge/Languages-16-orange?style=flat-square" alt="16 languages">
  <img src="https://img.shields.io/badge/License-MIT-lightgrey?style=flat-square" alt="MIT License">
</p>

<a id="english"></a>

# 🇬🇧 RimpyDroid: manage RimWorld mods from your phone

RimpyDroid is a free, open-source mod manager for **RimWorld on Android**, in the spirit of RimPy and RimSort. It is made for people who run the Windows version of the game through Winlator, GameHub or a similar launcher, and who want to download and update Workshop mods straight from the phone. You don't need the Steam client, a Steam account or a PC: the app talks to Steam itself with an anonymous login.

---

## How does it work?

1. **Choose where the files live.** On the first start, a short guide asks for file access and for the place of your files: the RimpyDroid folder in Downloads, or your own RimWorld folder.
2. **Get mods.** Search the Steam Workshop, paste Workshop or collection links, or browse. Tap download. Mods that are already on your phone are found automatically.
3. **Set the load order.** Enable the mods you want and tap **Sort**. Then open **Problems** and fix them in one tap.
4. **Play.** Start RimWorld from your launcher. The game reads the list RimpyDroid wrote.

---

## What can it do?

- **Workshop:** search by name (with sort, collections and game version) or paste Workshop and collection links. One tap downloads a mod, and a collection downloads all of its mods. **Browse** opens the Steam Workshop inside the app, with a Download button under every mod.
- **Background downloads:** downloads are queued and keep running with a notification. Up to 8 mods can download at the same time (5 by default).
- **Updates:** finds the Workshop mods that changed on Steam and updates them. Only the changed parts of a mod are transferred.
- **Load order:** enable or disable mods and move them (to top, up, down, to bottom, before or after another mod), or tap **Sort**. Sorting follows RimPy and RimSort: dependencies first, then the mods' own `loadBefore` / `loadAfter`, then the RimSort community rules. Core and the DLCs stay at the top.
- **Problems:** finds missing or disabled dependencies, wrong order, mods that are incompatible with each other, mods made for another game version, duplicate folders and entries for mods that are no longer installed. Most problems have a one-tap fix, and **Fix automatically** does the safe ones in one go.
- **Clean up:** from the Mods tab menu, delete mods that are incompatible with your game version, all inactive mods or duplicate copies, or just disable the incompatible ones (with Undo). Every delete shows a preview first, and Core and the DLCs are never deleted.
- **Lists:** save the current load order as a named list, apply it later, share it, or import one. RimPy and RimSort lists, `ModsConfig.xml`, plain Workshop links and plain mod names (one per line) all work. A list can download its missing mods and apply itself afterwards. A mod known only by name is looked up on the Workshop first, and names that are not found are listed afterwards.
- **Collections:** every Workshop collection you download is kept as one unit. **Apply** it (your load order becomes its installed mods, sorted), **enable** it (its mods are added with the installed mods they need), **update** it (new and newer mods are downloaded, and the changes are reported) or **delete** it. *Remove only* keeps the mods, and *Delete mods too* never deletes a mod that another collection uses. **Update all** does it for every collection, and *Add collection* adds one by its link.
- **Backups:** every time the app overwrites `ModsConfig.xml` it keeps a backup next to it (the last 8, restorable from the app).
- **Help built in:** a short guide on the first start, and a **(?)** button in Settings that opens a searchable help page explaining every tab, button and setting.
- **16 languages, your choice:** English, Arabic, Persian, Russian, Spanish, French, German, Portuguese, Turkish, Chinese, Japanese, Korean, Hindi, Indonesian, Italian and Ukrainian. Choose one in *Settings → Language*, or let the app follow your phone. Arabic and Persian are fully right to left. Texts that are not translated yet are shown in English.
- **Your look:** white, black, or follow the phone's dark mode. The accent colour can be any colour (ready-made colours, sliders, or a hex code such as `#1565C0`), and the text always stays readable. *Settings → Appearance → Style* also offers **Liquid glass**, with see-through cards over a soft moving gradient. **Classic** is the default.

---

## Where your files live

Choose in *Settings → Storage*:

| Choice | What it means |
| --- | --- |
| **RimpyDroid folder** | `Download/RimpyDroid`, made by the app, with `Mods`, `Config` (`ModsConfig.xml` and its backups) and `Lists`. |
| **Your RimWorld folder** | The game folder you pick. |
| **RimpyDroid folder somewhere else** | You pick the place, and the app makes the RimpyDroid folder inside it. |

In a RimpyDroid folder the app cannot see the game, so it never reports Core or a DLC as missing. It still always writes Core into `ModsConfig.xml`, because RimWorld rejects a file without it.

The RimpyDroid folder holds an empty `.nomedia` file, so mod previews don't show up in your gallery. Saved lists and collections are copied there after every change, and they come back by themselves if you remove and install the app again.

---

## Requirements

- Android 8.0 or newer.
- RimWorld for Windows running through Winlator, GameHub or a similar launcher. RimpyDroid manages the mod files. It does not run the game.
- A connection that reaches Steam. Depending on your country or network you may need a VPN or another DNS.

---

## Installation

1. Download the RimpyDroid APK file.
2. Open it on your phone. If Android asks, allow installing apps from this source.
3. Open RimpyDroid and follow the welcome guide.

---

## Privacy and safety

- **No account, no personal data.** The app logs in to Steam anonymously.
- **Only your files are touched.** The app only reads and writes the Mods folder, `ModsConfig.xml` and its backups, plus the copies of your lists and collections.
- **A few permissions:** internet, access to files (for the folders above), notifications and background work (for downloads).
- **Mods are somebody else's files.** The app reads `About.xml` and preview pictures from them and never runs anything from a mod. RimWorld itself does load the code of the mods you enable, so install mods you trust.
- **A careful browser.** *Browse* stays on `steamcommunity.com` over HTTPS. Other links open in your own browser, and `steam://`, `file:` and `intent:` links are never opened. The page cannot read your files.
- **Safe by default.** The app never follows a redirect from HTTPS to plain HTTP.
- **Found a problem?** Please read [SECURITY.md](SECURITY.md) and report it privately.

---

## Limits

- **Public Workshop items only.** Items that are hidden, friends-only or removed cannot be downloaded.
- **A `packageId` is needed.** Mods without a `packageId` in `About.xml` cannot be put into the load order, because RimWorld itself needs the id.
- **Mods you already have** (copied from a PC or from the Steam client) are found and sorted like any other. They have no download record, so the app offers to **Fetch** them once to enable update checks. Identical files are not downloaded again.
- **If downloading on the phone does not work for you,** Winlator can run SteamCMD (`steamcmd +login anonymous +workshop_download_item 294100 <id> +quit`). Copy the mod from `steamapps/workshop/content/294100/<id>` into `Mods`, and the app can sort and manage it.

---

## Credits and License

- Author: Mushussu Babylon
- Released under the [MIT License](LICENSE). You are free to use, modify and share it, with credit to the source.
- Sorting rules: the [RimSort Community Rules Database](https://github.com/RimSort/Community-Rules-Database), downloaded when you tap *Update* in Settings (it is not bundled).
- Steam access: [JavaSteam](https://github.com/Longi94/JavaSteam) (MIT).
- The way Workshop items are downloaded follows `SteamAnonModDownloader` from [RimDroid](https://github.com/udarmolota/rimdroid) by udarmolota (MIT, Copyright (c) 2026 udarmolota), ported to Kotlin. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- Inspired by RimPy and RimSort. Not affiliated with Ludeon Studios, Valve, RimPy, RimSort or RimDroid. RimWorld is a trademark of Ludeon Studios.

---

Made for everyone who wants to play RimWorld with mods on a phone. Enjoy!

<p align="center">
  <a href="#languages">⬆ Back to language selection</a>
</p>

---

<a id="arabic"></a>

<div dir="rtl">

# 🇮🇶 RimpyDroid: أدر مودات RimWorld من هاتفك

RimpyDroid مدير مودات مجاني ومفتوح المصدر للعبة **RimWorld على أندرويد**، على نهج RimPy وRimSort. صُنع لمن يشغّلون نسخة ويندوز من اللعبة عبر Winlator أو GameHub أو مشغّل مشابه، ويريدون تنزيل مودات الورشة (Workshop) وتحديثها مباشرة من الهاتف. لا تحتاج إلى برنامج Steam ولا إلى حساب Steam ولا إلى حاسوب: يتصل التطبيق بـ Steam بنفسه عبر تسجيل دخول مجهول.

---

## كيف يعمل؟

1. **اختيار مكان الملفات.** عند أول تشغيل يسألك دليل قصير عن صلاحية الوصول إلى الملفات وعن مكان ملفاتك: مجلد RimpyDroid في التنزيلات، أو مجلد RimWorld الخاص بك.
2. **الحصول على المودات.** ابحث في ورشة Steam، أو الصق روابط الورشة أو المجموعات، أو تصفّح. ثم اضغط تنزيل. وتُكتشف المودات الموجودة أصلا على هاتفك تلقائيا.
3. **ضبط ترتيب التحميل.** فعّل المودات التي تريدها واضغط **رتّب**. ثم افتح **المشكلات** وأصلحها بنقرة واحدة.
4. **العب.** شغّل RimWorld من مشغّلك. وتقرأ اللعبة القائمة التي كتبها RimpyDroid.

---

## ماذا يفعل التطبيق؟

- **الورشة:** ابحث بالاسم (مع الفرز والمجموعات وإصدار اللعبة) أو الصق روابط الورشة والمجموعات. نقرة واحدة تنزّل مودا، والمجموعة تنزّل كل موداتها. ويفتح **تصفّح** ورشة Steam داخل التطبيق، مع زر تنزيل تحت كل مود.
- **تنزيل في الخلفية:** تُوضع التنزيلات في طابور وتستمر مع إشعار. ويمكن تنزيل حتى 8 مودات في وقت واحد (5 افتراضيا).
- **التحديثات:** يكتشف مودات الورشة التي تغيّرت على Steam ويحدّثها. ولا يُنقل إلا الجزء الذي تغيّر من المود.
- **ترتيب التحميل:** فعّل المودات أو عطّلها وحرّكها (إلى الأعلى، أو لأعلى، أو لأسفل، أو إلى الأسفل، أو قبل مود آخر أو بعده)، أو اضغط **رتّب**. ويتبع الترتيب ما تفعله RimPy وRimSort: الاعتماديات أولا، ثم `loadBefore` / `loadAfter` الخاصة بالمودات، ثم قواعد مجتمع RimSort. ويبقى Core والإضافات (DLC) في الأعلى.
- **المشكلات:** يكتشف الاعتماديات المفقودة أو المعطّلة، والترتيب الخاطئ، والمودات غير المتوافقة مع بعضها، والمودات المصنوعة لإصدار آخر من اللعبة، والمجلدات المكررة، وإدخالات المودات التي لم تعد مثبتة. ولمعظم المشكلات إصلاح بنقرة واحدة، و**إصلاح تلقائي** ينفّذ الإصلاحات الآمنة دفعة واحدة.
- **التنظيف:** من قائمة تبويب المودات احذف المودات غير المتوافقة مع إصدار لعبتك، أو كل المودات غير المفعّلة، أو النسخ المكررة، أو عطّل غير المتوافقة فقط (مع تراجع). ويُعرض قبل كل حذف معاينة، ولا يُحذف Core ولا الإضافات أبدا.
- **القوائم:** احفظ ترتيب التحميل الحالي كقائمة باسم، وطبّقها لاحقا، أو شاركها، أو استورد قائمة. وتعمل قوائم RimPy وRimSort، وملف `ModsConfig.xml`، وروابط الورشة العادية، وأسماء المودات العادية (اسم في كل سطر). ويمكن للقائمة أن تنزّل مودّاتها المفقودة ثم تطبّق نفسها. والمود المعروف باسمه فقط يُبحث عنه في الورشة أولا، وتُعرض الأسماء التي لم يُعثر عليها بعد ذلك.
- **المجموعات:** تُحفظ كل مجموعة ورشة تنزّلها كوحدة واحدة. **طبّقها** (يصبح ترتيب التحميل مودات المجموعة المثبتة، مرتبة)، أو **فعّلها** (تُضاف موداتها مع المودات المثبتة التي تحتاجها)، أو **حدّثها** (تُنزَّل المودات الجديدة والأحدث ويُعرض ما تغيّر)، أو **احذفها**. «إزالة فقط» تُبقي المودات، و«حذف المودات أيضا» لا يحذف أبدا مودا تستخدمه مجموعة أخرى. و**تحديث الكل** ينفّذ ذلك لكل المجموعات، و«إضافة مجموعة» تضيف مجموعة برابطها.
- **النسخ الاحتياطية:** في كل مرة يستبدل التطبيق ملف `ModsConfig.xml` يحتفظ بنسخة احتياطية بجانبه (آخر 8 نسخ، ويمكن استعادتها من التطبيق).
- **مساعدة مدمجة:** دليل قصير عند أول تشغيل، وزر **(؟)** في الإعدادات يفتح صفحة مساعدة قابلة للبحث تشرح كل تبويب وزر وإعداد.
- **ست عشرة لغة باختيارك:** العربية والإنجليزية والفارسية والروسية والإسبانية والفرنسية والألمانية والبرتغالية والتركية والصينية واليابانية والكورية والهندية والإندونيسية والإيطالية والأوكرانية. اختر لغة من *الإعدادات ← اللغة*، أو اترك التطبيق يتبع لغة هاتفك. والعربية والفارسية مدعومتان من اليمين إلى اليسار بالكامل. والنصوص التي لم تُترجم بعد تظهر بالإنجليزية.
- **مظهرك:** أبيض أو أسود أو اتباع الوضع الداكن في الهاتف. ويمكن أن يكون لون التمييز أي لون (ألوان جاهزة أو منزلقات أو رمز hex مثل `#1565C0`)، ويبقى النص مقروءا دائما. وتقدّم *الإعدادات ← المظهر ← النمط* أيضا **الزجاج السائل**، ببطاقات شفافة فوق تدرّج لوني ناعم متحرك. ويبقى **الكلاسيكي** هو الافتراضي.

---

## أين تُحفظ ملفاتك

اختر من *الإعدادات ← التخزين*:

| الخيار | المعنى |
| --- | --- |
| **مجلد RimpyDroid** | `Download/RimpyDroid`، ينشئه التطبيق، ويضم `Mods` و`Config` (ملف `ModsConfig.xml` ونسخه الاحتياطية) و`Lists`. |
| **مجلد RimWorld الخاص بك** | مجلد اللعبة الذي تختاره. |
| **مجلد RimpyDroid في مكان آخر** | تختار أنت المكان، وينشئ التطبيق مجلد RimpyDroid بداخله. |

في مجلد RimpyDroid لا يستطيع التطبيق رؤية اللعبة، لذلك لا يبلّغ أبدا عن غياب Core أو إضافة. ومع ذلك يكتب Core دائما في `ModsConfig.xml`، لأن RimWorld ترفض ملفا لا يحتوي عليه.

يحتوي مجلد RimpyDroid على ملف `.nomedia` فارغ، فلا تظهر صور معاينة المودات في معرض هاتفك. وتُنسخ القوائم والمجموعات المحفوظة إليه بعد كل تغيير، وتعود من تلقاء نفسها إذا أزلت التطبيق وثبّتّه من جديد.

---

## المتطلبات

- أندرويد 8.0 أو أحدث.
- لعبة RimWorld لويندوز تعمل عبر Winlator أو GameHub أو مشغّل مشابه. يدير RimpyDroid ملفات المودات ولا يشغّل اللعبة.
- اتصال يصل إلى Steam. وقد تحتاج، بحسب بلدك أو شبكتك، إلى VPN أو DNS آخر.

---

## طريقة التثبيت

1. حمّل ملف RimpyDroid بصيغة APK.
2. افتحه على هاتفك. وإذا طلب أندرويد ذلك، اسمح بتثبيت التطبيقات من هذا المصدر.
3. افتح RimpyDroid واتبع دليل الترحيب.

---

## الخصوصية والأمان

- **لا حساب ولا بيانات شخصية.** يسجّل التطبيق الدخول إلى Steam بشكل مجهول.
- **لا تُمَسّ إلا ملفاتك.** لا يقرأ التطبيق ولا يكتب إلا في مجلد Mods وملف `ModsConfig.xml` ونسخه الاحتياطية، إضافة إلى نسخ قوائمك ومجموعاتك.
- **صلاحيات قليلة:** الإنترنت، والوصول إلى الملفات (للمجلدات أعلاه)، والإشعارات، والعمل في الخلفية (للتنزيلات).
- **المودات ملفات يصنعها غيرك.** يقرأ التطبيق منها ملف `About.xml` وصور المعاينة، ولا يشغّل أي شيء من المود أبدا. أما RimWorld نفسها فتحمّل شيفرة المودات التي تفعّلها، لذلك ثبّت مودات تثق بها.
- **متصفح حذر.** يبقى **تصفّح** على `steamcommunity.com` عبر HTTPS. وتُفتح الروابط الأخرى في متصفحك أنت، ولا تُفتح روابط `steam://` و`file:` و`intent:` أبدا. ولا تستطيع الصفحة قراءة ملفاتك.
- **آمن افتراضيا.** لا يتبع التطبيق أبدا تحويلا من HTTPS إلى HTTP العادي.
- **وجدت مشكلة؟** اقرأ [SECURITY.md](SECURITY.md) وأبلغ عنها بشكل خاص.

---

## القيود

- **مودات الورشة العامة فقط.** لا يمكن تنزيل العناصر المخفية أو المقتصرة على الأصدقاء أو المحذوفة.
- **لا بد من `packageId`.** لا يمكن وضع المودات التي ليس في `About.xml` الخاص بها `packageId` في ترتيب التحميل، لأن RimWorld نفسها تحتاج إلى المعرّف.
- **المودات التي لديك أصلا** (المنسوخة من حاسوب أو من برنامج Steam) تُكتشف وتُرتّب كأي مود آخر. وليس لها سجل تنزيل، لذلك يعرض التطبيق **جلبها** مرة واحدة لتفعيل التحقق من التحديثات. ولا تُنزَّل الملفات المطابقة مرة أخرى.
- **إذا لم ينجح التنزيل على الهاتف معك،** يستطيع Winlator تشغيل SteamCMD (`steamcmd +login anonymous +workshop_download_item 294100 <id> +quit`). انسخ المود من `steamapps/workshop/content/294100/<id>` إلى `Mods`، ثم يستطيع التطبيق ترتيبه وإدارته.

---

## الشكر والترخيص

- المؤلف: Mushussu Babylon
- التطبيق: بموجب [رخصة MIT](LICENSE)، يمكنك استخدامه وتعديله وتوزيعه بحرية مع ذكر المصدر.
- قواعد الترتيب: [RimSort Community Rules Database](https://github.com/RimSort/Community-Rules-Database)، وتُنزَّل عند الضغط على *تحديث* في الإعدادات (وهي غير مضمّنة في التطبيق).
- الاتصال بـ Steam: [JavaSteam](https://github.com/Longi94/JavaSteam) (MIT).
- طريقة تنزيل عناصر الورشة تتبع `SteamAnonModDownloader` من [RimDroid](https://github.com/udarmolota/rimdroid) للمطوّر udarmolota (MIT، Copyright (c) 2026 udarmolota)، بعد نقلها إلى Kotlin. راجع [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- مستوحى من RimPy وRimSort. لا علاقة له بـ Ludeon Studios أو Valve أو RimPy أو RimSort أو RimDroid. وRimWorld علامة تجارية لشركة Ludeon Studios.

---

صُنع لكل من يريد لعب RimWorld مع المودات على هاتفه. استمتعوا!

</div>

<p align="center">
  <a href="#languages">⬆ العودة إلى اختيار اللغة</a>
</p>
