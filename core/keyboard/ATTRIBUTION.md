# Data attribution

Bori Keyboard's code is our own. The bundled dictionaries (`app/src/main/assets/dict/*.bdic`)
are built by `tools/build_dict.py` from these open data sets:

| Data | Source | License |
|---|---|---|
| Word frequencies (en, es) | FrequencyWords by Hermit Dave, from OpenSubtitles 2018 — https://github.com/hermitdave/FrequencyWords | CC BY-SA 4.0 |
| English word list & capitalisation | SCOWL / en_US Hunspell dictionary by Kevin Atkinson, via https://github.com/wooorm/dictionaries | SCOWL license (permissive, see tools/.cache/raw/en_license) |
| Spanish word list & accents | RLA-ES Spanish Hunspell dictionary, via https://github.com/wooorm/dictionaries | MPL 1.1 / LGPL 3 / GPL 3 (tri-licensed) |
| Every Wiktionary word and inflection (en, es): slang of every era, science, medicine, computing, education... | Wiktionary, via kaikki.org's wiktextract data — https://kaikki.org, https://en.wiktionary.org | CC BY-SA 4.0 (and GFDL) |
| Word frequencies from Wikipedia, Reddit, Twitter, news, books, web (en, es) | wordfreq by Robyn Speer et al. — https://github.com/rspeer/wordfreq (data only; the package isn't bundled) | CC BY-SA 4.0 (data) |
| Next-word pairs (en, es) | Tatoeba sentences — https://tatoeba.org | CC BY 2.0 FR |
| Common misspellings, English (in `*_typos.txt`) | Wikipedia "Lists of common misspellings", via Roger Mitton's Birkbeck corpus copy (wikipedia.dat) — https://en.wikipedia.org/wiki/Wikipedia:Lists_of_common_misspellings | CC BY-SA 4.0 |
| Common misspellings, Spanish (in `*_typos.txt`) | Spanish Wikipedia "Wikipedia:Corrector ortográfico/Listado" — https://es.wikipedia.org/wiki/Wikipedia:Corrector_ortogr%C3%A1fico/Listado | CC BY-SA 4.0 |
| Modern words: slang 1920s–2020s, texting, AI/tech terms, app names (en, es) | Hand-curated for this project, `tools/wordlists/` — no third-party dictionary copied | Project's own (GPL-3.0) |

Because they're derived from CC BY-SA 4.0 data, the generated `.bdic` files and the typo lists
(`*_typos.txt`, built by `tools/build_typos.py`, which also adds generated keyboard slips) are
distributed under **CC BY-SA 4.0**.
