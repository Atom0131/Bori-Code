# Data attribution

Bori Keyboard's code is our own. The bundled dictionaries (`app/src/main/assets/dict/*.bdic`)
are built by `tools/build_dict.py`, and the phrase models (`*.ngram`) by `tools/ngram_count.py` +
`tools/build_ngrams.py`, from these open data sets:

| Data | Source | License |
|---|---|---|
| Word frequencies (en, es) | FrequencyWords by Hermit Dave, from OpenSubtitles 2018 — https://github.com/hermitdave/FrequencyWords | CC BY-SA 4.0 |
| English word list & capitalisation | SCOWL / en_US Hunspell dictionary by Kevin Atkinson, via https://github.com/wooorm/dictionaries | SCOWL license (permissive, see tools/.cache/raw/en_license) |
| Spanish word list & accents | RLA-ES Spanish Hunspell dictionary, via https://github.com/wooorm/dictionaries | MPL 1.1 / LGPL 3 / GPL 3 (tri-licensed) |
| Every Wiktionary word and inflection (en, es): slang of every era, science, medicine, computing, education... | Wiktionary, via kaikki.org's wiktextract data — https://kaikki.org, https://en.wiktionary.org | CC BY-SA 4.0 (and GFDL) |
| Word frequencies from Wikipedia, Reddit, Twitter, news, books, web (en, es) | wordfreq by Robyn Speer et al. — https://github.com/rspeer/wordfreq (data only; the package isn't bundled) | CC BY-SA 4.0 (data) |
| Next-word pairs and 3–4 word phrases (en, es) | Tatoeba sentences — https://tatoeba.org | CC BY 2.0 FR |
| Next-word pairs and 3–4 word phrases (en, es): counts only, no text is shipped | OpenSubtitles 2018 via OPUS (first 20M lines per language) — P. Lison and J. Tiedemann, "OpenSubtitles2016: Extracting Large Parallel Corpora from Movie and TV Subtitles", LREC 2016; https://opus.nlpl.eu/OpenSubtitles/ ; http://www.opensubtitles.org | Distributed by OPUS for research use; statistical counts only, like FrequencyWords above |
| Common misspellings, English (in `*_typos.txt`) | Wikipedia "Lists of common misspellings", via Roger Mitton's Birkbeck corpus copy (wikipedia.dat) — https://en.wikipedia.org/wiki/Wikipedia:Lists_of_common_misspellings | CC BY-SA 4.0 |
| Common misspellings, Spanish (in `*_typos.txt`) | Spanish Wikipedia "Wikipedia:Corrector ortográfico/Listado" — https://es.wikipedia.org/wiki/Wikipedia:Corrector_ortogr%C3%A1fico/Listado | CC BY-SA 4.0 |
| Modern words and texting phrases: slang 1920s–2020s, texting, AI, phone and brand words, Puerto Rican and regional Spanish, everyday texting sentences (en, es) | Hand-curated for this project, `tools/wordlists/` — no third-party dictionary copied | Project's own (GPL-3.0) |

Because they're derived from CC BY-SA 4.0 data, the generated `.bdic` and `.ngram` files and the typo lists
(`*_typos.txt`, built by `tools/build_typos.py`, which also adds generated keyboard slips) are
distributed under **CC BY-SA 4.0**.

## Emoji styles

The emoji style packs (`app/src/main/assets/emoji/*.bep`, built by `tools/build_emoji_packs.py`)
hold resized copies (72 px WebP) of these open emoji sets. They change only how emoji look in
Bori's emoji panel; each pack keeps its own license.

| Pack | Source | License |
|---|---|---|
| `twemoji.bep` | Twemoji, Copyright 2019 Twitter, Inc and other contributors; maintained fork https://github.com/jdecked/twemoji | Graphics: CC BY 4.0 |
| `fluent.bep` | Fluent Emoji (3D) by Microsoft — https://github.com/microsoft/fluentui-emoji | MIT License |
| `openmoji.bep` | All emojis designed by OpenMoji, the open-source emoji and icon project — https://openmoji.org | CC BY-SA 4.0 |
| `noto.bep` | Noto Emoji by Google (flags from the region-flags set bundled there) — https://github.com/googlefonts/noto-emoji | Images: Apache License 2.0; flags: public domain |
| `blobmoji.bep` | Blobmoji (Google's old "blob" emoji, kept alive as a Noto fork) — https://github.com/C1710/blobmoji, SVGs rasterised with librsvg | Apache License 2.0 |
| `emojione2.bep` | EmojiOne 2.2.7 artwork — https://github.com/joypixels/emojione/tree/v2.2.7 (EmojiOne 2.x artwork was released under CC BY 4.0; later JoyPixels versions are not used) | CC BY 4.0 |
| `fxemoji.bep` | FxEmoji (Firefox OS emoji) by Mozilla — https://github.com/mozilla/fxemoji | CC BY 4.0 |
| `custom.bep` | Bori's own drawings from `tools/make_emoji_overrides.py`: Puerto Rico flag with the light-blue (1895) triangle | Project's own (GPL-3.0); flag design is public domain |

Apple, Samsung, Xiaomi, Oppo and other phone makers' emoji designs are proprietary and are not included.


## Keyboard fonts

In `app/src/main/assets/fonts/`, from https://github.com/google/fonts, subset to Latin
(variable fonts pinned to one weight) by `tools/build_fonts.py`. Each font's full licence is
shipped next to it in `assets/fonts/licenses/<id>.txt`. Reserved font names are kept unchanged.

| File | Font | Copyright | Licence |
|---|---|---|---|
| `inter.ttf` | Inter | Copyright 2020 The Inter Project Authors (https://github.com/rsms/inter) | OFL-1.1 |
| `nunito.ttf` | Nunito | Copyright 2014 The Nunito Project Authors (https://github.com/googlefonts/nunito) | OFL-1.1 |
| `atkinson.ttf` | Atkinson Hyperlegible | Copyright 2020 Braille Institute of America, Inc. | OFL-1.1 |
| `jetbrains_mono.ttf` | JetBrains Mono | Copyright 2020 The JetBrains Mono Project Authors (https://github.com/JetBrains/JetBrainsMono) | OFL-1.1 |
| `doto.ttf` | Doto | Copyright 2024 The Doto Project Authors (https://github.com/oliverlalan/Doto) | OFL-1.1 |
| `press_start.ttf` | Press Start 2P | Copyright 2012 The Press Start 2P Project Authors (cody@zone38.net), with Reserved Font Name "Press Start 2P". | OFL-1.1 |
| `bungee.ttf` | Bungee | Copyright 2023 The Bungee Project Authors (https://github.com/djrrb/Bungee) | OFL-1.1 |
| `monoton.ttf` | Monoton | Copyright (c) 2011 by vernon adams (vern@newtypography.co.uk) | OFL-1.1 |
| `orbitron.ttf` | Orbitron | Copyright 2018 The Orbitron Project Authors (https://github.com/theleagueof/orbitron), with Reserved Font Name: "Orbitron" | OFL-1.1 |
| `pacifico.ttf` | Pacifico | Copyright 2018 The Pacifico Project Authors (https://github.com/googlefonts/Pacifico) | OFL-1.1 |
| `permanent_marker.ttf` | Permanent Marker | Font Diner (Google Fonts) | Apache-2.0 |
| `special_elite.ttf` | Special Elite | Astigmatic (Google Fonts) | Apache-2.0 |
| `creepster.ttf` | Creepster | Copyright (c) 2011, Font Diner, Inc (diner@fontdiner.com) | OFL-1.1 |
| `comic_neue.ttf` | Comic Neue | Copyright 2014 The Comic Neue Project Authors (https://github.com/crozynski/comicneue) | OFL-1.1 |

## GIF search

Optional GIF search is Powered by KLIPY (https://klipy.com). GIFs are loaded from KLIPY's
servers under their API terms; no GIFs are bundled with Bori.

## Key sounds
All key sounds in `app/src/main/assets/sounds/` are synthesized by `tools/build_sounds.py`
(our own code, no recordings or samples from other products), GPL-3.0-or-later like the app.

## Settings icons
Most icons in `settings/SettingsIcons.kt` use path data from Google's Material Icons
(https://github.com/google/material-design-icons), Apache License 2.0. The swipe and emoji icons
are our own drawings.

## Weather

The optional "Follows the weather" background effect (off by default) uses weather data by
Open-Meteo.com (https://open-meteo.com), licensed CC BY 4.0, and its geocoding API to find the
chosen city. Coordinates are rounded to 0.1° before they are sent; nothing typed is sent.
