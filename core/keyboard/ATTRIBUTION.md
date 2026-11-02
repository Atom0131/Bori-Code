# Bori Keyboard engine: data attribution

The code in this module is copied from the Bori Keyboard project (GPL-3.0-or-later) on 2026-10-27. The bundled dictionaries (`src/main/assets/dict/*.bdic`)
are built by Bori Keyboard's `tools/build_dict.py` from these open data sets:

| Data | Source | License |
|---|---|---|
| Word frequencies (en, es) | FrequencyWords by Hermit Dave, from OpenSubtitles 2018 — https://github.com/hermitdave/FrequencyWords | CC BY-SA 4.0 |
| English word list & capitalisation | SCOWL / en_US Hunspell dictionary by Kevin Atkinson, via https://github.com/wooorm/dictionaries | SCOWL license (permissive, see SCOWL-NOTICE.txt) |
| Spanish word list & accents | RLA-ES Spanish Hunspell dictionary, via https://github.com/wooorm/dictionaries | MPL 1.1 / LGPL 3 / GPL 3 (tri-licensed) |
| Next-word pairs (en, es) | Tatoeba sentences — https://tatoeba.org | CC BY 2.0 FR |
| Modern words: slang 1920s–2020s, texting, AI/tech terms, app names (en, es) | Hand-curated for this project, `tools/wordlists/` — no third-party dictionary copied | Project's own (GPL-3.0) |

Because they're derived from CC BY-SA 4.0 data, the generated `.bdic` files are distributed
under **CC BY-SA 4.0**.
