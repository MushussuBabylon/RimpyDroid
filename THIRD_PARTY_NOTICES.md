# Third-party notices

RimpyDroid uses the following work of others.

## RimDroid: Steam Workshop download mechanism (MIT)

The way RimpyDroid downloads Workshop items (anonymous Steam login, then a manual SteamPipe download: the
Workshop depot from PICS, the depot key, the list of content servers, the manifest and finally the chunks)
follows the mechanism of `SteamAnonModDownloader.java` from
[RimDroid](https://github.com/udarmolota/rimdroid) by udarmolota. It is ported to Kotlin and adapted
(installation into the mod folder, incremental updates, retries, progress). The code is in
`app/src/main/java/com/rimpydroid/manager/steam/`. Only this one file of RimDroid is licensed under the MIT
License; no other part of RimDroid (which is GPL-3.0) is used here.

```
SPDX-License-Identifier: MIT

Anonymous Steam Workshop mod downloader, original mechanism by udarmolota for RimDroid.
Copyright (c) 2026 udarmolota

Permission is hereby granted, free of charge, to any person obtaining a copy of this software
and associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following condition: the above copyright notice and this
permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## Libraries

These are downloaded by the build and packaged into the app. Each is used under its own licence.

| Library | Licence |
|---|---|
| [JavaSteam](https://github.com/Longi94/JavaSteam) (`in.dragonbra`) | MIT |
| [Bouncy Castle](https://www.bouncycastle.org/) (`bcprov`) | MIT (Bouncy Castle licence) |
| [zstd-jni](https://github.com/luben/zstd-jni) | BSD 2-Clause |
| [XZ for Java](https://tukaani.org/xz/java.html) | Public domain / 0BSD |
| [OkHttp](https://square.github.io/okhttp/) | Apache 2.0 |
| [Kotlin and kotlinx.coroutines](https://kotlinlang.org/) | Apache 2.0 |
| [AndroidX, Jetpack Compose, Material 3](https://developer.android.com/jetpack) | Apache 2.0 |

The libraries' own dependencies (protobuf, for example) come with their own licences, which are listed in the
libraries' packages.

## Data

- The sorting rules are the [RimSort Community Rules Database](https://github.com/RimSort/Community-Rules-Database).
  They are downloaded by the app on request and are not bundled.
- RimWorld is a trademark of Ludeon Studios. RimpyDroid is not affiliated with Ludeon Studios, Valve, RimPy,
  RimSort or RimDroid.
