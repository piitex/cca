# Third-party notices

CCA's own source code is under the [MIT License](LICENSE). The components below are not covered by it.
Each stays under its own license, and nothing here changes those licenses.

Versions are the ones the build uses at the time of writing (see [pom.xml](pom.xml)).

## Libraries

Downloaded by Maven. They are not copied into the CCA source tree, but the installers bundle them.

| Component | Version | License | Used for |
|---|---|---|---|
| [RenGL](https://github.com/piitex/RenGL) (`rengl-core`, `rengl-downloads`, `rengl-spellcheck`) | 0.1.0 | RenGL Open-Source-Game License (RSGL) 1.0 | The UI engine, download manager and spell checker |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | 1.28.0 | Apache-2.0 | Unpacking llama.cpp release archives |
| [JSON-java (org.json)](https://github.com/stleary/JSON-java) | 20250517 | Public domain | Parsing GitHub's API and the data inside character cards |
| [metadata-extractor](https://github.com/drewnoakes/metadata-extractor) | 2.19.0 | Apache-2.0 | Reading metadata embedded in PNG character cards |

RenGL is source-available, not OSI-approved open source. CCA is an "Open Application" under its terms
because CCA's complete source is public under the MIT License. RenGL's own dependencies (LWJGL, Skiko, the
Hunspell dictionaries and others) and their licenses are listed in RenGL's
[THIRD-PARTY-NOTICES.md](https://github.com/piitex/RenGL/blob/main/THIRD-PARTY-NOTICES.md). They are
bundled in the installers too.

## Bundled files

| File | Source | License |
|---|---|---|
| `src/main/resources/fonts/Roboto-Regular.ttf` | [Roboto](https://fonts.google.com/specimen/Roboto), Google | Apache-2.0 |
| `src/main/resources/images/icon.png`, `packaging/icons/` | CCA | MIT |
| `src/main/resources/images/avatar_example.png` | AI-generated for CCA by the maintainer | MIT |

The full Apache License 2.0 text is at <https://www.apache.org/licenses/LICENSE-2.0.txt>.

## Downloaded at run time

CCA does not ship these. It downloads them when you ask it to, and they are under their own licenses:

* [llama.cpp](https://github.com/ggml-org/llama.cpp) release builds (MIT)
* Models from [Hugging Face](https://huggingface.co). Each model has its own license, which you must
  follow. The optional starter model, Qwen2.5-0.5B-Instruct, is under the Apache-2.0 license.
