# AVIF cache and JXL collection archive

## User controls

Both engines are independent and disabled by default.

- **AVIF cache compression**: affects newly downloaded Glide cache entries. It does not rewrite
  existing cache files. Profiles are CRF18/preset6 (quality), CRF18/preset8 (balanced default),
  CRF24/preset10 (small), and CRF30/preset10 (extreme).
- **JXL collection archive**: can run after a server-confirmed bookmark or from the artwork menu.
  Effort 3, 5 (default), and 7 are exposed. The archive manager is available from experimental
  settings. Archives are never evicted by the automatic-snapshot quota and are not deleted on
  unbookmark.

## AVIF data path

`AvifCompressingUrlLoader` buffers a new network image, encodes it with libavif + the existing
SVT-AV1 4.2.0 target, fully decodes the result, and only then gives the AVIF stream to Glide. The
source is retained whenever encoding fails, validation fails, the image is animated, EXIF
orientation is non-normal, dimensions exceed the memory guard, or AVIF would be larger. Native
AVIF/JXL decoders remain registered when AVIF writing is disabled, so old compressed entries stay
readable. Sketch uses the same native decoding library for the full-screen viewer.

## JXL data path

`JxlArchiveEngine` downloads original URLs directly, deliberately bypassing any AVIF-compressed
cache. JPEG uses libjxl's lossless JPEG-frame transcoding. Other supported images are decoded to
straight-alpha RGBA and encoded as lossless JXL. Every page must pass a full native decode before
its staging directory is atomically published inside the unified `ShaftSnapshots` library.
Regular, automatic, and JXL archives check each other and use a cross-codec creation reservation,
so one artwork cannot acquire three independent copies. Unsupported pages (including 16-bit PNG and oversized images) are kept byte-for-byte in their
source format instead of aborting the whole archive. ugoira is outside this engine.

The existing snapshot manager and `JxlArchiveActivity` read the same files. `JxlArchiveViewerActivity`
displays all pages with the existing Sketch zoom viewer and the native JXL decoder.

`AvifCacheDiagnostics` exposes the verified MIME type, source/output byte counts, profile, and
success/fallback counters in experimental settings and in the `AvifCache` log stream.

## Dependencies and validation

- SVT-AV1 4.2.0 (existing), libavif 1.4.2, libaom 3.14.1, libjxl 0.11.2.
- Native builds use 16 KB LOAD alignment and package license texts under `assets/licenses/`.
- JVM tests cover profile defaults, signature detection without native loading, and independent
  bookmark payload flags.
- Instrumentation tests cover odd-size JXL pixel-exact round trips plus JPEG-to-JXL and AVIF
  readability. Android device performance and large-image memory behavior still require on-device
  acceptance; desktop benchmark timings are not phone timings.
