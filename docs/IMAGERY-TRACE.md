# Experimental imagery-assisted path candidate core

Status: **limited Android beta workflow implemented**. Main map menu →
**OpenAerialMap tracing · BETA** opens a separate GPS-free coverage map with the
main map's current virtual centre and zoom (not GPS). Pan/pinch or use zoom buttons,
then Search to freeze/query the visible geographic viewport. Search does not run
on opening, panning, zooming or resuming. Green footprints are supported sources;
orange footprints are rejected sources; yellow is selected; blue is the last
queried area. Tap a footprint (overlapping results get a list) or open Sources /
reasons to inspect every returned item, including those with unknown geometry.
Choose a supported source, move the map so the white **128 m** centre box fits
inside its footprint, then Load centre. Source eligibility is independent of
viewport size and partial viewport overlap. Only loading validates full fragment
containment and the 1–256 m per-axis limit. Start/end/colour → trace → inspect
magenta candidate → save locally or export GeoJSON. Saved drafts reopen offline.
An explicit bbox launch retains the earlier advanced fragment workflow.

Local version is **1.1.0-beta.2**, versionCode **27**. The existing unsigned APK
was signed with the established local key and queued for private delivery.
Signature verification, certificate continuity with beta.1, package/version,
zip alignment and delivery-copy SHA-256 equality passed. This version is prepared
as a GitHub prerelease, not a replacement for the stable 1.0.2 release. No automatic
OSM upload or changes to existing walk/photo schemas. The existing EOX satellite
layer is not connected. Physical-device installation remains unverified.
There is **no main-map candidate overlay** in this first beta: preview/reopen are
inside the separate imagery screen, as permitted by the minimum usable flow.

Owner decision: publish the current beta unchanged and pause further work on the
imagery feature/provider selection. OpenAerialMap remains the only connected
imagery provider for tracing; Airbus OneAtlas is not integrated. No new provider
account, imagery purchase or wider licence permission is implied.

Prepublication deterministic rerun: 189 Android/JVM tests, 186 passed, 3 opt-in
network probes skipped, zero failures/errors. Earlier explicitly enabled real-OAM
end-to-end evidence is retained below; a skipped live test is not a fresh service
availability check.

## Android beta limits and behaviour

- `ImageryCoverageMap.java` subclasses the existing `MapView`: real Web Mercator
  projection, inherited pan/pinch gestures, scale/compass and local atlas/MBTiles
  rendering. It has no GPS/current location or walk/candidate layer. **No basemap
  tiles are fetched**. Without saved detailed map data, the actual built-in world
  outlines are the only basemap; this is labelled on-screen, not simulated streets
  or a photographic backdrop. OAM footprints are not imagery thumbnails.
- `OamCoverage.java` retains result IDs and independently accumulated structured
  reasons: licence, missing metadata, GSD, CRS, affine rotation, extent/transform/
  footprint mismatch, source dimensions, unsupported tracing geometry, visual asset,
  ID and collection. EN/RU inspection shows all reasons, not only the first strict
  `Source` exception. Rejected sources cannot be selected for raster loading.
  Valid Polygon/MultiPolygon rings (including holes/nonrectangles) are drawn from
  actual coordinates even when tracing is unsupported. Unknown/malformed geometry
  is explicitly listed without a fabricated bbox polygon. Discovery geometry is
  bounded to 128 parts/rings, 10000 vertices/item, finite ±180°/±85° coordinates,
  closed rings and no date-line-crossing edge; this is not a topology repair tool.
- Coverage query bounds are at most **1° per axis**, within ±85° latitude and
  without antimeridian wrap; wider viewports ask the user to zoom in, not silently
  clamp the search to the centre. The interactive trace selection is fixed at
  **128 m per side** in this iteration; arbitrary 1–256 m bboxes remain supported
  by the advanced workflow/core. There is no size slider or imagery mosaic.
- Network pause/resume is available on the imagery screen and changes the global
  `MapNetwork` setting. Cancel, map gesture during work, zoom buttons, switching
  views, new search and lifecycle pause invalidate active work. Frozen successful
  coverage remains visible while panning; there is no auto-requery. A new search
  clears the old page and selected source; stale jobs/dialogs cannot select it.
  Centre/zoom/view mode survive recreation, but coverage/results and unsaved
  selections do not; search again explicitly. Saved draft restore remains offline.
- `OamImagery.java`: only `api.imagery.hotosm.org`, collection `openaerialmap`.
  Strict item `license == CC-BY-4.0`; unknown, CC-BY-SA and missing attribution,
  capture date or GSD are rejected. GSD must be finite, positive and ≤ 0.5 m.
  Pilot accepts only a visual asset with consistent north-up EPSG:4326 affine
  transform, finite extent and bounded positive source dimensions; rotated,
  unsupported CRS and partly covered requested fragments are rejected. Item geometry
  must be one closed axis-aligned rectangle (Polygon or single MultiPolygon, no
  holes); its bounds must match raster extent within 1e-6 degrees for rounded STAC
  metadata. Requested fragments must fit inside BOTH vector and raster extents.
- Trace input extent: 1–256 m per axis, within ±85° latitude, no antimeridian wrapping.
  Output always 512×512 RGBA PNG, projected explicitly into EPSG:4326. The
  original full TIFF is NEVER downloaded/decoded in Android. Effective output
  resolution ≤ 0.5 m/axis. Source metadata accuracy is not ground-truth accuracy.
- STAC GET `/stac/search?collections=openaerialmap&limit=30&bbox=...`.
  Search JSON ≤ 1 MiB; **only the first page, at most 30 items**, is requested.
  The first-page warning is always shown in coverage mode, independently from
  whether the provider supplies a next-page link. `more` reflects that link only;
  absence is not a promise of complete coverage. No pagination or automatic next
  page fetch. Zero returned items differs from rejected sources and transport
  failure. No fallback provider.
- Verified against the actual OpenAPI fetched from `/raster/api` on 2026-11-07:
  `/raster/collections/openaerialmap/items/{id}/bbox/{w},{s},{e},{n}/512x512.png`
  with `assets=visual`, `coord_crs=EPSG:4326`, `dst_crs=EPSG:4326`,
  `return_mask=true`. The old `tiles.openaerialmap.org` WMTS is NOT used.
  PNG ≤ 2 MiB and dimensions verified before decoding. Transparent pixels block
  traversal. No claim that arbitrary footprint bounding boxes contain real pixels.
- One worker handles network, decoding, tracing and persistence. HTTP timeouts,
  global `MapNetwork` pause/connection cancellation, Future interruption and
  generation guards prevent stale results after bbox/source changes, cancellation
  or pause. No auto-retry/resume request. Opening another app cancels active work.
  A save already atomically written can remain after cancellation; it was explicitly
  requested, and remains visible in Local drafts. Unsaved screen state may be lost
  on recreation; a completed saved draft ID is restored.
- `ImageryDrafts.java`: isolated `files/imagery-drafts-v1/`, AtomicFile JSON v1,
  ≤ 4 MiB/draft, ≤ 20 drafts, ≤ 8192 path vertices. Stores bounded PNG, item
  metadata, pixel geometry, endpoints/sample, affine bbox and GeoJSON provenance.
  Read-back validates source, licence, request, pixel-centre geometry and selection.
  Save is explicit and read back before success is displayed. No walk migration.
  Initial beta has no delete UI: the 20-draft cap is an intentional limitation.
- Export uses Android CREATE_DOCUMENT and writes only a GeoJSON Feature, not the
  raster. It preserves producer/attribution, CC-BY-4.0 and licence URL, capture
  date, source metadata/asset URLs, GSD, exact fragment request, bounds, selections
  and modification/unverified-candidate notice. Export is read back before success;
  document providers without readable output may report failure after creating a file.
- EN/RU strings live in paired `imagery_strings.xml` files. Synthetic/mock tests
  exercise real Activity controls, source chooser, pixel taps, worker tracing,
  persistence/reopen, export intent, no-coverage/licence/CRS/budget rejection,
  cancellation and lifecycle. They are not physical-device or real path acceptance.

## beta.2 verification, 2026-11-07

- Full `tools/test.py` run with `MAPNOTES_OAM_LIVE=1`: **189 tests,
  187 passed, 2 existing opt-in tests skipped, zero failures/errors**.
  `build/beta2-gradle-verification.log` contains the actual run summary.
- Eleven new `OamCoverageTest` tests cover all structured reason categories and
  multiple concurrent reasons; malformed/missing/degenerate geometry; actual
  multipart/holes geometry; broad/partial-overlap eligibility; Activity search,
  native footprint hit testing and dialogs; gesture-based geographic movement;
  128 m load → trace → save → reopen/export; outside-fragment and oversized-query
  refusal; next-page hints; cancelled out-of-order requests and stale chooser;
  global pause; and actual connected local vector MBTiles rendering.
- Updated live Activity test requests real broad coverage, inspects/selects
  `6383e049fba1230007216ec4`, loads only its centre fragment, traces a 280-vertex
  unverified candidate, saves and reopens it. No fixture replaces live failures.
- Native-rendered evidence (ignored, not physical-device screenshots):
  `render-tests/build/oam-live-evidence/actual-coverage-screen.png`,
  `coverage-local-basemap.png`, `actual-beta-screen.png`, actual bounded raster
  and candidate GeoJSON. The local-basemap evidence uses the repository's real
  Moscow MVT fixture; Holland park coverage evidence honestly has world outlines
  only because no local detailed map was connected there.
- Standalone `tools/build.py` produces
  `build/MapNotes-1.1.0-beta.2-unsigned.apk`, versionCode 27; SDK compilation,
  DEX packaging and zip alignment pass. Logs: `build/beta2-build-verification.log`.
  `verify_resources.py`: **399 paired EN/RU strings**; two build/resource metadata
  regressions pass; `git diff --check` passes. No signing/commit/push/delivery.
- No physical-phone acceptance. Multitouch comfort, storage/document providers,
  accessibility and small-screen layout remain device checks. Java 8/deprecation
  and existing Gradle manifest override warnings remain in the test harness;
  standalone APK correctly targets SDK 35.

## Original core design and provider research (retained)

### Previous beta.1 checks, 2026-11-07 (historical)

- Complete Android/JVM run with `MAPNOTES_OAM_LIVE=1`: 178 tests, 176 passed,
  2 existing opt-in probes skipped, zero failures/errors. The normal deterministic
  run skips the additional `LiveOamImageryTest` unless explicitly enabled.
- Live test used the actual Activity and actual network transport: real STAC search,
  source selection, real 512 x 512 PNG decode, tracing, atomic save and reopen.
  Source `6383e049fba1230007216ec4` produced a 280-vertex unreviewed candidate.
  No cached response or mock was substituted for the live service.
- Native Robolectric graphics rendered the actual beta UI using that real raster
  and candidate. `render-tests/build/oam-live-evidence/` holds screenshot, raster
  and GeoJSON. This is a test renderer, not a screenshot from a physical phone.
- Standalone SDK beta APK build and zip alignment passed. Pure Java regressions,
  369 paired EN/RU resources and two build/resource metadata regressions passed.
  The resource audit now covers all XML files in each language directory.
- No physical Android device was attached; installation, touch usability and
  document-provider export on real hardware remain unverified.

The following engine notes describe the original provider-independent core; items
labelled “future” or “remaining” below are historical integration gates, superseded
by the beta implementation and limitations above.

## Implemented boundary

- `src/ru/main/explorer/ImageryTrace.java`: package-private, Android-independent,
  synchronous CPU engine suitable for an executor. No I/O, downloads, database writes,
  OSM edits or dependence on the map renderer.
- Inputs: bounded ARGB raster, optional impassable mask, **two explicit pixel
  endpoints and one explicit colour-sample location**. Sample radius 0–2 means a
  clipped 1×1, 3×3 or 5×5 patch; blocked/non-opaque pixels do not contribute.
- Eight-neighbour Dijkstra, positive RGB colour-error cost, Euclidean step length,
  no diagonal corner cutting. Non-opaque pixels and mask cells are impassable.
  Colour cost is a soft preference, not a road detector or confidence estimate.
- Result: read-only list of immutable pixel/latitude/longitude vertices in start-to-end
  order; both endpoints included. `null` means disconnected. Invalid inputs throw
  `IllegalArgumentException`; cancellation throws `CancellationException` without
  returning a partial line. A uniform field still produces a shortest line; a
  candidate is **not evidence that a road exists or is walkable**.
- Geographic reference: north-up raster **outer-edge** bounds in WGS84 longitude /
  latitude; explicitly choose linear EPSG:4326 or projected EPSG:3857 interpolation.
  Coordinates refer to pixel centres. No CRS inference, rotated/sheared rasters,
  antimeridian-crossing bounds or screen-to-map registration.
- Caller-supplied source ID, attribution, permission description and capture date
  (explicit `unknown` allowed) travel with the candidate, as do the reference,
  raster dimensions, sample settings and endpoints. These are provenance hooks,
  **not verification of licence, ownership or georeferencing accuracy**.

## Resource and worker contract

Maximum 1024 pixels per side and 262144 pixels total (for example 512×512).
Validate these bounds **before decoding** any future user-selected image; this
engine cannot bound memory already allocated by a caller/decoder. It does not
silently downsample. Caller must hold exclusive ownership of input arrays during
execution; they are never modified or retained in the result. All search state is
per invocation. The indexed heap contains at most one entry per pixel, avoiding an
unbounded duplicate-entry priority queue. Search arrays and output are O(pixels);
worst-case output may contain one vertex per pixel. No device heap benchmark is claimed.

Invoke on a worker, never the Android main thread. Supply a thread-safe cancellation
predicate (e.g. `AtomicBoolean::get`), and cancel on lifecycle/source/selection
changes. Thread interruption is also honoured and its flag is preserved. Checks
occur during cost preparation, every expansion and every reconstruction vertex;
no partial result is published. A future UI must reject stale results by revision
before displaying them. Executor lifecycle and publication are not implemented here.

## Remaining integration gates

1. Explicitly permitted imagery with verified licence/derivative-use rules, sufficient
   resolution, geographic extent and source metadata. No Google/Bing scraping or
   new imagery downloads were introduced into Android. The separate provider probe
   below does not imply worldwide OpenAerialMap coverage. Existing EOX 10 m imagery
   is not a narrow-path fallback.
2. Bounded local raster decoding and explicit CRS/bounds entry or validated metadata
   import. Arbitrary screenshots cannot supply geographic coordinates by themselves.
   Current MBTiles support is not automatically connected to this engine.
3. Localized UI for endpoints/sample selection, worker cancellation, stale-result
   rejection and visually distinct **candidate** preview, with discard/manual review.
4. Separate explicit persistence/export design with retained provenance. Never turn
   a candidate into a recorded walk or confirmed road, and never auto-edit OSM.

UI was intentionally left unchanged rather than showing a provider-backed feature
that does not yet function. No legal safety or extraction-accuracy guarantee is made.

## Verification

`render-tests/src/test/java/ru/main/explorer/ImageryTraceTest.java` covers shortest
uniform paths, endpoint identity, same-point paths, detours, blocked diagonals,
transparent obstacles, colour-guided corridors, clipped sample averaging, pixel-centre
mapping in both supported projections, validation, cancellation/interruption,
maximum pixel budget and read-only results. Forty deterministic random small graphs
are also compared against an independent Bellman–Ford cost oracle. Synthetic fixtures
verify algorithms, **not real-world path accuracy**.

Run the repository `tools/test.py --gradle <Gradle 8.9 executable>` for actual Gradle
JVM/Robolectric tests, and `tools/build.py` with JAVA_HOME / ANDROID_HOME for the
unsigned standalone-SDK APK. No signing credentials are needed. No physical-device
tracing acceptance or in-app imagery-provider workflow has been exercised.

## Provider feasibility check, 2026-11-07

Google Maps Platform terms, section 3.2.3(c), explicitly prohibit creating content
from Maps content, including tracing/digitizing roadways from satellite basemaps.
Obtaining tiles technically is not permission. No Google integration is included;
no promise about absence of enforcement or other legal consequences is made.
Reference: https://cloud.google.com/maps-platform/terms

OpenAerialMap is a candidate for an explicitly limited-area pilot, not a replacement
for a global detailed satellite basemap. Its public service describes CC-BY-4.0
imagery, but actual STAC items include both CC-BY-4.0 and CC-BY-SA-4.0. Inspect each
item's licence and attribution; do not apply a catalogue-wide blanket assumption.
Unknown/conflicting permissions must block automatic use. Retain producer, source
ID, capture date, licence link and modification notice with derived candidates.
References: https://openaerialmap.org/about/ and
https://api.imagery.hotosm.org/stac/collections/openaerialmap

Live searches restricted to `collections=openaerialmap`:
- Previous Krylatskoe test bbox 37.416,55.750,37.436,55.761 returned zero items,
  with no next page. A Copernicus DEM result from an unrestricted search is an
  elevation model, NOT replacement optical imagery.
- Wider Moscow-region bbox 36.5,55.0,38.5,56.5 returned ten items on the first page,
  with a next page. Ten is not the total coverage count.

An actual CC-BY-4.0 GeoTIFF was downloaded and decoded for a separate desktop probe:
- Item `6383e049fba1230007216ec4`, "Holland park 2022 November v2".
- Producer Kirill Setdekov / Open Imagery Network; captured 2022-11-20.
- Source is UAV orthophotography, not satellite imagery. Snow and old capture date
  limit suitability; metadata resolution is not independently measured accuracy.
- Actual file: 39789380 bytes; EPSG:4326; 21019 x 24623 pixels. Only a bounded
  resampled window was decoded into the tracing engine, not the entire raster.
- The actual Java `ImageryTrace` class was compiled and run on a 512 x 426 crop,
  with two endpoints and a 5 x 5 colour sample. It produced a 272-vertex candidate.
- Output was exported as GeoJSON with longitude/latitude coordinates from the
  actual raster transform, attribution and an unreviewed-candidate flag. Coordinate
  calculation was checked, but geolocation accuracy on the ground was not measured.
- Visual check: follows much of the visible dark curve, with edge following and
  jagged deviations. No hand correction, accuracy score or road confirmation.

This desktop probe uses an RGB-error cost, not the earlier Python chromaticity cost;
it verifies the actual Java engine path, not equivalence of those two formulations.
Local raw imagery, reports and output are kept in ignored
`build/imagery-provider-check/`, not committed as public source fixtures.

The next integration still needs source discovery/selection, full viewport footprint
coverage checks, bounded raster fetching, licence/provenance handling, localized
selection/preview UI and separately reviewed persistence. Where coverage is absent,
show unavailable: never silently substitute Google tiles or coarse imagery.
