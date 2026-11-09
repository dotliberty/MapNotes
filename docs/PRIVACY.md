# Privacy and permissions

MapNotes is a local-first walking journal. There is no app account, developer-operated synchronization server, advertising SDK or analytics SDK. The application does not implement automatic uploading of routes, photos, notes or crash reports.

## Stored on the device

- SQLite stores location points, timestamps, accuracy, recording segments, walk periods, photo paths/geotags and notes.
- Version 1.3 adds per-point movement labels, source (automatic/manual), monotonic timestamps, reported speed and stop flags. Explicitly confirmed training segments contribute bounded local speed samples to a personal walking/running profile. Automatic predictions do not confirm themselves. Profile reset removes training samples, not the journal.
- Original photos live in app-private storage. Photo GPS coordinates are stored in the database; this is not a promise that coordinates are written into EXIF. Existing image metadata is preserved when a gallery copy is exported.
- Downloaded map geometry/imagery, source selections, resumable download state and rendering caches are local.
- A bounded Java crash report and Android's available exit reason can be displayed and explicitly copied. Review a report before sharing: stack traces and diagnostic text may contain private paths or contextual information. Nothing is sent automatically.

App backup is disabled in the manifest (`allowBackup=false`). This is not a substitute for encryption or protection against an unlocked/rooted device. A comprehensive backup/export/restore feature for routes and the private database is not implemented. Uninstalling or clearing storage removes app-private data.

## Network requests

When online map layers/downloads are enabled, map services receive tile requests or geographic bounding boxes for the viewed/requested area, together with IP address and normal HTTP connection metadata. Automatic nearby geometry requests are based on location. Those requests can reveal an approximate area of interest even though the recorded route database is not uploaded.

The application uses public OpenStreetMap/Overpass sources and EOX satellite tiles; availability, retention and policies are controlled by those providers. A user-entered HTTPS download URL contacts the server the user chooses. Public map access is not anonymous. Pause the network layer for offline viewing of previously available data; GPS recording is independent of that pause.

## Permissions

Version 1.3 optionally uses Google Play services Activity Recognition during an explicitly started recording in automatic mode. The user requests activity permission from the Movement screen. Manual mode, missing services or denied permission do not prevent GPS recording. App code does not upload the profile or route for this feature; Google Play services is a separate installed component with its own behavior and policies. This is not a guarantee that Google services perform no network activity. No raw accelerometer stream or continuous activity-event log is persisted by MapNotes.

| Permission | Purpose |
| --- | --- |
| Precise/coarse location | Map position, accepted walking points and geotagged photos. Precise location is needed for exploration and photos. |
| Foreground service / location service | Explicit walk recording with a visible notification. |
| Foreground data-sync service | Explicit offline map downloads with a visible notification. |
| Notifications (Android 13+) | Recording/download status and stop/pause controls. |
| Camera | Built-in Camera2 photo capture. |
| Physical activity (Android 10+); legacy Google activity-recognition permission on older Android | Optional automatic walking/running/vehicle observations during an explicit recording. |
| Internet | Map tiles, geometry and explicitly requested map files. |
| Legacy external-storage write (Android 8–9 only) | Explicit export of a photo copy to Pictures/MapNotes. |

The document picker grants access only to the selected file where supported; a persistent read grant may be retained for a connected MBTiles file. The source is opened read-only. Some providers require an app-private copy.

## Explicit export and sharing

**Save to gallery** exports a separate image copy through Android storage APIs. The original remains private in MapNotes. The exported copy and its existing metadata become available to other software with gallery access and may be synchronized by the user's gallery/cloud settings. MapNotes does not control those services. Copying a crash report to the clipboard also exposes the copied text according to Android's clipboard rules.

This document describes the source in this release, not a guarantee about the operating system, device vendor or third-party data providers.
