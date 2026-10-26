# Security

RimpyDroid asks for no account, no password and no personal data. It logs in to Steam anonymously and only
reads and writes the RimWorld folder you choose (the Mods folder and `ModsConfig.xml`).

- **Mods are somebody else's files.** The app reads `About.xml` and preview pictures from them (size-limited, XML with
  its own entity definitions is refused) and never runs anything from a mod. RimWorld itself does load the code
  of the mods you enable, so install mods you trust.
- **Found a problem?** Please use *Security → Report a vulnerability* on the GitHub page of the repository
  instead of a public issue.
