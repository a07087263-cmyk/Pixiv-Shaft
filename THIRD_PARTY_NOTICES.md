# Third-party notices

## Telegram Android

Pixiv-Shaft includes the Telegram Android `SpoilerEffect2` particle renderer and its GLSL
shaders, adapted only to the local package and application utility APIs.

- Upstream project: https://github.com/DrKLO/Telegram
- Upstream revision: `45ab8f4308496e1f01026a97fcdb0d58a5274474`
- Upstream files:
  - `TMessagesProj/src/main/java/org/telegram/ui/Components/spoilers/SpoilerEffect2.java`
  - `TMessagesProj/src/main/res/raw/spoiler_vertex.glsl`
  - `TMessagesProj/src/main/res/raw/spoiler_fragment.glsl`
- License: GNU General Public License, version 2

The full GPL v2 license text is available in [LICENSE](./LICENSE).

## SVT-AV1

The ugoira MP4 exporter includes the SVT-AV1 software encoder.

- Upstream project: https://gitlab.com/AOMediaCodec/SVT-AV1
- Version: `4.2.0` (`v4.2.0`, commit `9292ec8e32bce26f781f277ec8739b53426c4300`)
- Integration: pinned source archive built by `app/src/main/cpp/CMakeLists.txt`
- License: BSD 3-Clause Clear License and Alliance for Open Media Patent License 1.0

The accompanying license and patent texts are packaged under `assets/licenses/svt-av1/`.
