<p align="center">
  <img src="res/mipmap-xxxhdpi/ic_launcher.png" width="88" height="88" alt="MapNotes icon">
</p>

<h1 align="center">MapNotes</h1>

<p align="center">
  <strong>Your walks, revealed on a map.</strong><br>
  A local-first Android walking journal with geotagged photos and private notes.
</p>

<p align="center">
  <a href="https://github.com/dotliberty/MapNotes/releases/tag/v1.2.4">Download 1.2.4</a> ·
  <a href="#your-first-walk">Get started</a> ·
  <a href="docs/1.2.4-RELEASE.md">Release notes</a> ·
  <a href="README.ru.md">Русский</a>
</p>

<p align="center">Android 8.0+ · English / Русский · No account required</p>

---

## A journal of places you have actually been

Start a walk, explore a neighbourhood and take photos along the way. MapNotes records your route, reveals the map around accepted walking points and keeps your photos and notes connected to their locations.

It is a walking journal, not a route planner or turn-by-turn navigation app. The illustrated atlas is a presentation of real map data, not a game world.

### What you can do

- **See your exploration grow.** Reveal areas along your walks, with a maximum 100 m radius and approximate building-wall occlusion. Zoom and move around the map with continuous transitions.
- **Keep a walk history.** Start, finish or resume a walk; review its route, elapsed time and associated photos. Recording uses a visible foreground-service notification.
- **Photograph a place, not just a moment.** Use the built-in camera, select supported lenses/photo sizes and adjust zoom where the device allows it. Open a photo from the map and add a note.
- **Keep originals private.** Photos stay inside MapNotes unless you choose **Save to gallery**, which creates a separate copy.
- **Take available maps offline.** Download an atlas region or connect a compatible local `.mbtiles` file. Regional downloads support pause and continuation.
- **Use English or Russian.** Follow the system language or choose one in **⋯ → App language**.

## A look inside

<table>
  <tr>
    <th>Explore</th>
    <th>Photo journal</th>
    <th>Camera</th>
  </tr>
  <tr>
    <td><img src="docs/screenshots/atlas-124.png" width="250" alt="Atlas view with map controls and walk actions"></td>
    <td><img src="docs/screenshots/gallery-124.png" width="250" alt="Test photo catalog with framed entries, dates and notes"></td>
    <td><img src="docs/screenshots/camera-124.png" width="250" alt="Camera layout with side controls, gallery, shutter and mode selection"></td>
  </tr>
</table>

These are **native Canvas renders of the application in Robolectric**, not screenshots from a physical phone. The map example uses a checked-in public Moscow vector tile; journal entries are test fixtures. The black camera preview is intentional: layout tests do not provide a real camera feed.

[English interface](docs/screenshots/main-en.png) · [Russian interface](docs/screenshots/main-ru.png)

## Install or update

1. Open the [1.2.4 release](https://github.com/dotliberty/MapNotes/releases/tag/v1.2.4).
2. Download **`MapNotes-1.2.4.apk`**. `SHA256SUMS` is available alongside it for checking the download's integrity.
3. Open the APK on your Android device and allow installation from your browser/file manager if prompted.

**Already using the official MapNotes app? Install the new APK over it. Do not uninstall the app or clear its storage.** The official release keeps the same package and signing certificate. An APK with `-debug` in its name is a separate test app, not an update to the official installation.

> There is no complete journal backup/restore feature yet. Uninstalling or clearing storage removes app-private routes, photos and notes. Exporting a photo to the gallery does not back up your journal.

**Requirements:** Android 8.0 or newer. Precise location and system GPS are needed for exploration and geotagged photos. Camera/lens capabilities depend on the device.

## Your first walk

1. **Allow precise location** and turn on system location/GPS. Wait for a current fix.
2. **Choose Start walk.** The recording notification provides a stop action. Screen-off recording remains subject to Android and device-vendor background restrictions.
3. **Tap + photo** when you want to remember a place. A photo requires a fresh location fix; its original stays in MapNotes.
4. **Finish the walk.** Open **Walk history** to review it, or select a photo on the map to view it and edit its note.
5. **Prepare offline detail before you need it.** Try a small 1–4 km atlas region first, or connect an existing compatible MBTiles map.

Opening the map can also record exploration points outside an explicitly started walk. Those points do not create invented entries in walk history. Recorded distance is conservative; elapsed walk time includes waiting and interruptions.

## Maps and offline use

The APK contains a **world overview**, not a detailed map of the planet. Street detail comes from downloaded regions or a map you connect.

| Source | What it provides |
| --- | --- |
| Bundled Natural Earth overview | Broad world outlines; no detailed street coverage. |
| Regional atlas downloads | OpenStreetMap streets/buildings obtained through public Overpass servers. |
| Local `.mbtiles` map | Read-only raster or supported MVT/PBF vector data, including compatible OpenMapTiles/Protomaps-style layers. |
| Optional satellite layer | EOX Sentinel-2 cloudless **2016** mosaic, approximately 10 m/pixel; not current high-resolution imagery. |

Previously downloaded data can be viewed without a network connection. Downloads and online layers require access to their providers; public servers may time out or rate-limit requests. Pausing network activity does not stop GPS recording.

A connected MBTiles file is opened directly where the Android file provider allows it. Otherwise, MapNotes needs space for a local copy. Only one external map is active at a time. PMTiles, Mapsforge `.map` and raw `.osm.pbf` are not supported imports.

[Map sources and attribution](docs/ATTRIBUTIONS.md) · [Detailed limitations](docs/KNOWN-LIMITATIONS.md)

## Privacy, without an account

Routes, original photos and notes are stored on the device. MapNotes does not implement a developer-operated sync service, advertising/analytics SDK or automatic crash-report upload.

**Local-first does not mean anonymous online maps.** Map providers receive requested areas/tiles, your IP address and ordinary connection metadata. A photo exported to the system gallery may also be backed up by your gallery/cloud settings.

[Read the privacy and permissions guide](docs/PRIVACY.md) before relying on the app for sensitive locations or photos.

## Status and limitations

1.2.4 introduces the illustrated interface, redesigned camera controls, camera-switch safety fixes and readable, consistently formatted source code.

The release verification run completed **256 Android/JVM tests**, with **3 external-data checks skipped** and no failures. Formatting, resource, geometry, metadata and signing checks also passed. These are local software checks, **not a claim of physical-device camera/GPS acceptance or guaranteed frame rate**.

- GPS accuracy and background recording depend on reception, Android and the device vendor.
- Lens selection, zoom, photo sizes and camera light depend on advertised hardware support.
- Exploration approximates visibility from 2D geometry; it does not model real terrain or line of sight.
- Dense photo collections and long histories can still need time to load or redraw.
- MapNotes is not intended for emergency navigation or safety-critical decisions.

If something goes wrong, describe your Android/device model, app version and the steps that trigger it. A local crash report can be viewed and explicitly copied from the map menu; review it for private information before sharing.

## Build, test and contribute

The app uses Java and Android platform APIs. The separate test harness uses Gradle/Robolectric. Follow the build guide for the toolchain, APK packaging and signing instructions.

- [Build and test](docs/BUILDING.md)
- [Code style and formatting checks](docs/FORMATTING.md)
- [1.2.4 release notes](docs/1.2.4-RELEASE.md)
- [UI verification](docs/1.2.4-DESIGN.md) and [camera-switch regressions](docs/1.2.4-CAMERA-SAFETY.md)
- [Experimental imagery-assisted tracing](docs/IMAGERY-TRACE.md) — separate beta feature; its earlier version notes are historical.

Keep changes focused, retain EN/RU strings and include regression tests where appropriate. Do not commit private signing keys, credentials, personal routes or photos. An independently signed build cannot update the official app while preserving its Android installation identity.

## License and attribution

Original software source is licensed under **[Mozilla Public License 2.0](LICENSE)**. That does not separately license map datasets or illustration assets. See [license scope and exceptions](LICENSE-NOTICE.md), [data attribution](docs/ATTRIBUTIONS.md) and [decorative-asset provenance](assets/atlas124/SOURCES.md).
