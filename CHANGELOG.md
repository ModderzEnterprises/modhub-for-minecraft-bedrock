# Changelog

## 1.13
- New **Import file** button (home screen and mod pages): pick a `.mcpack`, `.mcaddon`, `.mcworld` or `.mctemplate` you downloaded yourself and ModHub imports it and restarts Minecraft like a normal install. Use it for mods whose author doesn't allow other apps to download them.
- Clearer message for those mods: "Open website", download there, then "Import file".
- Modrinth removed from the app (it has no Bedrock content).
- The finished-import card now reminds you to sign in to Google Play if Minecraft sent you there instead.

## 1.12
- "Most popular" now covers the whole MCPEDL catalogue (up to 10,000 mods), not just the most recently updated ones, so big older mods such as Twixxel's Stalkers show up. Loads in the background and is cached on the phone.
- CurseForge home lists go deeper: 400 most popular and 200 newest per category (needs a working API key).
- Home lists refresh by themselves every 30 minutes and when you return to the app; there is a refresh button and pull-down-to-refresh.
- Multi-word searches match each word (for example "stalker inspiration"), ranked by how many words match.

## 1.11
- Restart timing option in Settings: Safe (default, waits 15-45 s so Minecraft finishes unpacking), Fast (old behaviour) or Ask me (notification). Fixes the restart cutting imports short on some phones.

## 1.10
- API key box no longer capitalises, autocorrects or suggests words (this was corrupting typed keys). Live character count and a "Paste from clipboard" button.
- "Test key" now tells a wrong key apart from a blocked network.

## 1.9
- Settings: "Test key" button that tells you in plain words whether your CurseForge key works.
- API keys are cleaned automatically (spaces, line breaks, quotes from copy/paste).
- Clearer note that Modrinth is mostly Java mods.

## 1.8
- CurseForge: shows required dependencies on the detail page with a tap-through to install them.
- MCPEDL: "Heads up" note when the author mentions required packs, or when a listing has base + add-on files.

## 1.7
- First public release, signed with a new release key.
- Top-100 home lists, full descriptions, installed marker, optional automatic Minecraft restart, first-run permission explainer, CurseForge opt-outs respected.
