# Known limitations and verification scope

## What local tests can establish

The repository includes pure geometry/GPS checks and Android/Robolectric regressions for SQLite migration and persistence, walk/session boundaries, photos and notes, gallery-export logic, map downloads and continuation, local HTTP handling, vector/raster map rendering, bounded photo layout, dense-map detail, camera UI/orientation calculations and language selection. Test output is evidence for those exercised code paths, not for untested hardware.

Existing route/photo storage remains SQLite schema v3; this release does not intentionally migrate or delete routes, originals or notes. Tests cover migrations from older schemas and preservation of test records. Same-certificate APK verification demonstrates signing compatibility, not a completed real-user installation.

## Not verified on a physical phone for this release

- Camera2 session creation and image quality at every advertised resolution, continuous light, autofocus and physical orientation changes.
- Real GPS reception and recorded walking accuracy, screen-off/battery behavior, vendor background restrictions and Android 15 lifecycle policies.
- An update using an actual user's private database, SAF behavior for a complete planet-sized MBTiles file or a full live 100 × 100 km download.
- Device FPS, peak memory or a guarantee that a previously reported device-specific crash cannot recur.

No physical Android device was connected to the release environment. Generated screenshots are synthetic-data Robolectric renders, explicitly labeled in the README.

## Data and usability

- No complete backup/restore workflow for routes/database/private photos. A gallery copy is not a backup of the journal.
- GPS filtering is conservative: inaccurate, mock, stale or implausibly fast fixes are rejected. Distance can undercount when reception is poor. Elapsed time includes interruptions and waiting.
- Exploration is an approximation based on two-dimensional building geometry, not visibility analysis of real terrain, doors, trees or height. It is not suitable for emergency/navigation decisions.
- Photo capture requires a fresh location fix (up to 30 seconds old). Digital zoom, lens selection and manual exposure are not implemented. Large JPEG sizes can increase memory use.
- Dense photo callouts are bounded; not every thumbnail fits simultaneously. Zoom in or use the photo catalog. Full spatial indexing/pagination of every dataset is not implemented.
- First-time rendering of a very long history or a new region can still require background work. Map caches are adaptive to the app heap, but on a low-memory device a bounded cache can still evict decoded geometry, which then reloads from disk.
- Public map servers can time out, reject requests or rate-limit. Retry/failover/continuation do not guarantee service availability. A previous direct Java public-server probe failed on the development PC; successful curl retrieval was not a successful application end-to-end network test. Normal release tests use deterministic fixtures/local HTTP, not a claim of live server health.
- Satellite imagery is the 2016 EOX mosaic, roughly 10 m/pixel. No current high-resolution satellite world is bundled.
- Only one external MBTiles source is active at once. Unsupported schemas/providers may fail or require copying. A whole planet map can require many tens of GB.
- UI translation covers app-owned text, not arbitrary upstream exception details, map place names, user notes or data-provider metadata. Those are preserved as supplied.

## Suggested device acceptance

Install over the previous signed version without uninstalling; verify old data, EN/RU/system selection, camera portrait/landscape and several photo sizes, one screen-off walk, stop from notification, interrupted recording/continuation, timeline/photo map links, explicit gallery export and a small atlas download followed by offline viewing. Keep any real diagnostic data private when reporting issues.
