# Security

RimpyDroid asks for no account, no password and no personal data. It logs in to Steam anonymously and only
reads and writes the RimWorld folder you choose (the Mods folder and `ModsConfig.xml`).

- **Mods are somebody else's files.** The app reads `About.xml` and preview pictures from them (size-limited, XML with
  its own entity definitions is refused) and never runs anything from a mod. RimWorld itself does load the code
  of the mods you enable, so install mods you trust.
- **The Workshop browser (Browse)** stays on `steamcommunity.com` over HTTPS. Other links open in your own browser, and
  `steam://`, `file:` and `intent:` links are not opened at all. The Download buttons it adds can only start a Workshop
  download (the call needs a random secret that only the app's own script has), and the page cannot read your files.
- **Your lists and collections** are copied to `Download/RimpyDroid` so that they survive removing the app. They hold mod
  names and ids only. Files found there are read back only if they are smaller than 8 MB, up to 100 files and 16 MB in one go.
- **The app never follows a redirect from HTTPS to plain HTTP,** and the Steam age-check cookie is only sent to Steam.
- **Found a problem?** Please use *Security → Report a vulnerability* on the GitHub page of the repository
  instead of a public issue.
