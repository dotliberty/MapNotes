# Attribution and third-party data

MapNotes draws its own UI and map styling. It does not bundle game graphics, proprietary game map tiles or logos.

## OpenStreetMap

Map geometry, street/building data and the real-world vector test fixture contain **© OpenStreetMap contributors** data, under the Open Database License (ODbL).

- Copyright and license information: https://www.openstreetmap.org/copyright
- ODbL: https://opendatacommons.org/licenses/odbl/1-0/
- Public Overpass instances are separately operated services; query availability and usage rules are not guaranteed by this app.
- OSM tile policy (where relevant to normal online tile viewing): https://operations.osmfoundation.org/policies/tiles/

Do not interpret the app's source-availability notice as replacing map-data terms. Bulk regional downloads use geometry queries rather than bulk downloading the standard OSM raster tile service.

## Satellite imagery

**Sentinel-2 cloudless by EOX IT Services GmbH. Contains modified Copernicus Sentinel data 2016.** The application uses the EOX 2016 mosaic, approximately 10 m/pixel.

- https://s2maps.eu/
- https://cloudless.eox.at/
- CC BY 4.0: https://creativecommons.org/licenses/by/4.0/
- Source service: https://tiles.maps.eox.at/wmts/1.0.0/WMTSCapabilities.xml

Satellite data is fetched as needed or by explicit regional download; it is not a current high-resolution world dataset embedded in the APK.

## World overview

The bundled overview geometry is derived from **Natural Earth**, public-domain map data.

- https://www.naturalearthdata.com/
- Terms: https://www.naturalearthdata.com/about/terms-of-use/

## Vector format and fixture

- MVT specification: https://github.com/mapbox/vector-tile-spec
- MBTiles specification: https://github.com/mapbox/mbtiles-spec
- OpenMapTiles schema: https://openmaptiles.org/schema/
- Protomaps documentation: https://docs.protomaps.com/
- The public OpenFreeMap/OpenMapTiles fixture is documented in `render-tests/src/test/resources/world/sources.md`. It is a public map tile, not an owner's recorded route. © OpenStreetMap contributors, ODbL; service https://openfreemap.org/.

Compatible external MBTiles files are supplied by the user. Each dataset may require its own attribution and redistribution conditions; compatibility does not grant rights to redistribute it.

## Build and test dependencies

The production app is written against the Android platform; the test harness separately downloads Android Gradle Plugin, Gradle, JUnit and Robolectric. Those dependencies retain their respective licenses and notices. They are not copied into this source export as vendor binaries. Check their upstream projects before redistributing toolchain components.
