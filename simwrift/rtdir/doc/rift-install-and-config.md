# SimWeaver Rift — Installation and configuration

SimWeaver Rift is an HLA/DIS datasource gateway with a REST API and a browser-based Web UI.
This guide covers installing the server, starting it, and the configuration files it reads.

- **Using the Web UI:** see `README.md` in the Web UI folder.
- **Integrating over the REST API:** see `docs/rest-api.md`.

---

## Requirements

| | |
|---|---|
| **Java** | Java 11 or later. No other libraries are needed |
| **RTI** | For HLA datasources: an RTI for the standards you use (IEEE 1516-2010 and/or IEEE 1516-2025), with its LSD/CRC reachable from the server |
| **DIS** | For DIS and C-DIS datasources: no RTI. The server must receive the UDP traffic (default port 3000; the C-DIS sample uses 3100): broadcast, unicast or multicast, and through the firewall |
| **Network** | Clients must reach the server's HTTPS port (the REST API and the Web UI share one port) |
| **Browser** | A current Microsoft Edge or Google Chrome. No internet access is needed |

---

## 1. Install

Download the SimWeaver Rift zip file and extract it to a folder of your choice. That folder is
the **installation folder**: the server reads `etc/`, the web folder and its key store relative
to it. All paths below are relative to it.

```
<installation folder>/
├── run.sh                      start script, Linux
├── run.bat                     start script, Windows
├── lib/simweaverrift.jar
└── etc/
    ├── server.root.settings
    ├── datasources/
    ├── junctions/              Junction gateway configurations
    ├── junctions.dist/         every sample configuration, as delivered (never read by the server)
    ├── fom/
    ├── Docker/Dockerfile
    └── systemd/simweaver-rift.service
```

**Upgrading:** replace `lib/simweaverrift.jar`, `webroot/` and `doc/`, and `etc/junctions.dist/`
with the new version's. Keep your `etc/server.root.settings`, `etc/datasources/` and
`etc/junctions/`. Comparing the old and the new `etc/junctions.dist/` shows what changed in the
sample gateway configurations; copy what you want from there.

### The RTI library

**HLA datasources** need the HLA RTI's Java library on the class path, for example
`prti1516e.jar` for Pitch pRTI. It is not part of SimWeaver Rift. **DIS datasources need no
RTI.**

All ways of starting the server below take the library from the variable **`RTI_JAR`**:
- The start scripts have a default (`/opt/prti1516e/lib/prti1516e.jar`,
  `C:\Program Files\prti1516e\lib\prti1516e.jar`), and an environment variable `RTI_JAR`
  overrides it.
- Several libraries (e.g. one for HLA 1516-2010 and one for HLA 4) are separated with `:` on
  Linux and `;` on Windows.
- Set `RTI_JAR` empty for a DIS-only installation. A path that does not exist is ignored by
  Java, so the default does no harm where no RTI is installed.
- An optional `JAVA_OPTS` (e.g. `-Xmx2g`) is passed on to Java.

If the RTI also needs native libraries, they must be on `LD_LIBRARY_PATH` (Linux) or `PATH`
(Windows); see the RTI's own documentation.

Then start the server in one of these ways.

### 1a. In a console

```
./run.sh          (Linux)
run.bat           (Windows)
```

- The scripts change to the installation folder themselves, so they can be started from
  anywhere.
- They run `java` from the `PATH` with `lib/simweaverrift.jar` and `RTI_JAR` on the class
  path, and main class `com.simweaver.rift.Server`.
- Arguments given to the script are passed on to the server.
- **Ctrl+C** stops the server.
- To use another RTI location, edit the `RTI_JAR` line in the script, or set `RTI_JAR` as an
  environment variable.

### 1b. As a systemd service (Linux)

The service file `etc/systemd/simweaver-rift.service` runs `run.sh` as the user `simweaver`,
from `/opt/simweaver-rift`. It restarts the server if it fails.

1. Install SimWeaver Rift in `/opt/simweaver-rift`, or change the paths in the service file.
2. Create the service user and give it the installation folder:

    ```sh
    sudo useradd --system --home-dir /opt/simweaver-rift simweaver
    sudo chown -R simweaver /opt/simweaver-rift
    ```

3. Check the `RTI_JAR` line in the service file (remove it for DIS only).

4. Install, enable and start the service:

    ```sh
    sudo cp /opt/simweaver-rift/etc/systemd/simweaver-rift.service /etc/systemd/system/
    sudo systemctl daemon-reload
    sudo systemctl enable --now simweaver-rift
    ```

- `systemctl status simweaver-rift` shows its state.
- `journalctl -u simweaver-rift -f` follows its console output.
- `sudo systemctl stop simweaver-rift` stops it.

### 1c. In Docker

The Dockerfile `etc/Docker/Dockerfile` builds an image on Java 11 (`eclipse-temurin:11-jre`)
with the whole installation folder in `/opt/simweaver-rift`. The server runs as the user
`simweaver`, not as root.

Build it from the installation folder; the build context is that folder, not `etc/Docker`:

```sh
docker build -f etc/Docker/Dockerfile -t simweaver-rift .
```

The RTI is not in the image. Mount its installation at `/opt/rti`:

```sh
docker run -d --name simweaver-rift --network host \
    -v /opt/prti1516e:/opt/rti:ro \
    simweaver-rift
```

- The image expects the RTI library at `/opt/rti/lib/prti1516e.jar`. For another RTI or
  layout, add `-e RTI_JAR=/opt/rti/<path to its jar>`. For DIS only, leave out the RTI mount.
- **Network:** `--network host` is the simplest for HLA, because RTIs often open connections
  in both directions between the federate and the CRC/LSD. With Docker's default network,
  publish the server port instead (`-p 8843:8843`, with the port from
  `etc/server.root.settings`) and make sure the RTI can reach the container.
  **DIS needs `--network host`**, because broadcast and multicast PDUs do not cross Docker's
  default network.
- **Configuration and logs:** to keep them outside the container, mount `etc/` and the log
  folder as volumes, e.g. `-v /srv/rift/datasources:/opt/simweaver-rift/etc/datasources`.
- `docker logs -f simweaver-rift` follows its console output.
- `docker stop simweaver-rift` stops it.
- `etc/Docker/Dockerfile.dockerignore` keeps logs, the Windows script and the service files
  out of the image.

### Check that it runs

When the server is ready, its output shows:

```
HTTPS-server started at <port>
```

Open `https://<server>:<port>/` in a browser. If the server uses a self-signed certificate,
the browser warns on the first visit; accept it, or install the certificate as trusted on
the client machines.

---

## 2. Configure

> The **Config assistant** page of the Web UI (`config-assistant.html`) writes these files for you: it asks what you need and shows each file's name, place and content, ready to copy or download. It does not change anything on the server.

All configuration files are plain text in Java properties format: `key = value`, one per line;
`#` starts a comment. Files are read as UTF-8.

| File | Scope | Read |
|---|---|---|
| `etc/server.root.settings` | Server-wide settings | At startup |
| `etc/datasources/*.datasource.settings` | One datasource configuration per file | Each time the configuration list is shown |
| `etc/listprofiles.properties` | Optional: which attributes the object list shows | At startup |
| `maps/raster/<map>/<map>.tif` | Optional: background maps of the map view | At startup |

### 2.1 Server settings: `etc/server.root.settings`

Server-wide settings. Restart the server after changing this file.

| Key | Default | Meaning |
|---|---|---|
| `webserver.port` | `8843` | HTTPS port of the Web UI and the REST API |
| `webserver.root` | `webroot` | The web folder |
| `webserver.keystore.path` | `keystore.jks` | Key store with the server certificate |
| `webserver.keystore.pwd` | `password` | Its password |
| `logdir` | `logs` | Log folder |
| `junction.enabled` | `true` | Run SimWeaver Junction gateways (see [junction.md](junction.md)). The 0.9.0 name `junktion.enabled` is still read |
| `junction.dir` | `etc/junctions` | Junction's configuration folder (0.9.0 name `junktion.dir` still read). `etc/junctions.dist` next to it holds every delivered sample, untouched |
| `junction.datasourceconfigs` | `true` | List each gateway endpoint as a datasource configuration; `false`: only the files in `etc/datasources` (Inspect on the Junction page still works) |
| `map.enabled` | `true` | The map view of the datasource page (2.4); `false` removes it everywhere |
| `map.file` | | The default background map: an image file, in `map.dir` or anywhere on the server (relative to the installation folder), e.g. `maps/raster/HYP_HR_SR_W_DR/HYP_HR_SR_W_DR.tif` |
| `map.files` | | More maps outside `map.dir`, `;`-separated files |
| `map.dir` | `maps/raster` | The background maps: one sub folder per map |
| `map.cache.dir` | `maps/cache` | Map tiles made from them |
| `map.maxzoom` | from the raster | Deepest tile zoom (Natural Earth 10 m: 6); closer zooms scale those tiles |
| `map.default` | the first | Id (file name without extension) of the default map, when `map.file` is not set |
| `map.cache.memorymb` / `map.threads` / `map.jpegquality` / `map.background` | `48` / `2` / `0.85` / `#9db7c9` | Tiles in memory; tile makers; JPEG quality; colour where the raster has no data |
| `map.overlays` | | Shapefiles drawn over the map (2.5), `;`-separated `.shp` files or `.zip` packages |
| `map.overlays.dir` | `maps/overlays` | Every `.shp` and `.zip` package in this folder (and its sub folders) is an overlay too |
| `map.overlay.<id>.*` | | The style and labels of an overlay (2.5) |

The server uses **HTTPS only**, so it needs a key store with its certificate. `keystore-gen-line.txt` has a `keytool` command that makes a self-signed one.

### 2.2 Datasource configurations: `etc/datasources/`

Each `.datasource.settings` file in `etc/datasources/` is one **datasource configuration**:
a recipe from which users start datasources in the Web UI (or with `POST /api/datasources`).
Some samples are provided; copy one and adapt it to add your own:

| File | Name | What |
|---|---|---|
| `hla3-rpr2.datasource.settings` | HLA3-RPR2 | HLA Evolved, RPR FOM v2.0 with Link 16 |
| `hla4-rpr2.datasource.settings` | HLA4-RPR2 | HLA 4, the same RPR FOM v2.0 with Link 16 in the HLA 4 format |
| `hla3-rpr2-talk.datasource.settings` | HLA3-RPR2-Talk | HLA Evolved, RPR v2 and the Pitch Talk FOM |
| `dis6.datasource.settings` | DIS6 | A DIS 6 network on UDP port 3000 |
| `cdis.datasource.settings` | CDIS | A C-DIS network (SISO-STD-023-2024) on UDP port 3100 |


A file is listed only when it contains both of these settings:

```properties
datasource.name     = HLA3-RPR2
datasource.standard = IEEE1516-2010
```

- `datasource.standard` must be `IEEE1516-2010` (HLA Evolved) or `IEEE1516-2025` (HLA 4), or
  `DIS6` / `DIS7` (DIS 6 / DIS 7), or `CDIS` (C-DIS, Compressed DIS, SISO-STD-023-2024).
- Files without these two settings are skipped, with no message.
- The other settings, such as the RTI connection, federation and FOM files, are shown in the
  samples.
- **DIS configurations** keep the HLA 1516-2010 FOM settings, pointing at the RPR FOM: it is
  the reference that DIS PDUs are mapped to and decoded with. They add:
  - `datasource.dis.exid`: the default exercise, 0–255, where 0 means every exercise. The
    Web UI asks for the exercise when a datasource is created.
  - the DIS interface, port and entity timeout. The interface can be empty (all interfaces),
    an address or interface name, or a multicast group such as `239.1.2.3` or
    `239.1.2.3@eth0`.
  - PDUs read: Entity State (and Entity State Update), Fire, Detonation, Collision, Transmitter,
    Signal (Link 16 Signal PDUs, TDL type 100, as the `Link16RadioSignal` subclasses of the
    Link-16 FOM module, SISO-STD-002), Receiver, Start/Resume, Stop/Freeze, Acknowledge, **Designator** (an
    `EmbeddedSystem.Designator` `<entity>/designator`), **IFF** (an IFF object
    `<entity>/iff-<system type>` of the class of its system type, e.g. `NatoIFFTransponder`, with
    modes, codes and layer 2 data; kept at least 24 s, as IFF heartbeats are 10 s) and
    **Electromagnetic Emission**.
    An Electromagnetic Emission PDU gives an `EmitterSystem` object per emitter
    (`<entity>/emitter-<n>`, hosted by the entity) and a `RadarBeam` or `JammerBeam` (beam
    function *Jamming*) per beam (`<entity>/emitter-<n>/beam-<m>`), as the RPR GRIM maps them.
    A beam that is no longer listed is removed, and so is a system missing from a heartbeat;
    otherwise they time out with the entity timeout.
- **C-DIS configurations** (`datasource.standard = CDIS`, 0.9.18) are DIS 7 configurations
  whose network carries C-DIS: each datagram is decoded to DIS 7 PDUs (all 22 C-DIS PDU types;
  full and partial updates; one or several PDUs per datagram) and then read as above. A partial
  update of an object whose first full update has not arrived is dropped. The decoder forgets an
  object after `datasource.cdis.timeoutmultiplier` (2.4) × its heartbeat
  (`datasource.cdis.heartbeat.entitystate` 5, `.emission` 10, `.designator` 5,
  `.transmitter` 2, `.iff` 10, seconds). Altitude is the height above the WGS84 ellipsoid. See
  [junction.md](junction.md), section 9, *C-DIS endpoints*, for the codec.
- **LSD choices** (HLA only): `datasource.hla.lsd = default;crc2;crc@10.0.0.5:8989` offers
  several, the first is the default. The Create dialog offers the others, and any other value
  can be typed there; empty is the RTI's own default. An empty entry in the list offers that
  too. An LSD that contains `;` cannot be listed, but can be typed.
- **FOM from the federation** (HLA only): `datasource.hla.foms = dynamic` joins without FOM
  files and takes the FOM from the federation, from its MOM object
  (`HLAobjectRoot.HLAmanager.HLAfederation`, attribute `HLAcurrentFDD`). The datasource then
  decodes whatever FOM the federation runs. The federation must already exist; the
  datasource never creates it. `datasource.hla.fddtimeoutseconds` (default 15) is how long it
  waits for the FDD after the join. Classes added later by federates that join with more FOM
  modules are seen after the datasource is removed and created again.

  ```properties
  datasource.name     = Blue, FOM from the federation
  datasource.standard = IEEE1516-2010
  datasource.hla.lsd  = default
  datasource.hla.foms = dynamic
  datasource.hla.federation = Blue
  ```
- The folder is read again each time the configuration list is shown. New or changed files
  appear without a restart.

### 2.3 Object list attributes: `etc/listprofiles.properties` (optional)

Sets which attributes the Web UI's object list shows per object class. Without this file,
RPR defaults are used: for example, `EntityIdentifier`, `EntityType` and `ForceIdentifier`
for RPR entities. Each line names a class and its attributes, in display order:

```properties
rift.listprofile.HLAobjectRoot.BaseEntity = EntityIdentifier, EntityType, ForceIdentifier
rift.listprofile.EmbeddedSystem.RadioTransmitter = HostObjectIdentifier, Frequency, TransmitterOperationalStatus
```

- A setting also applies to the subclasses of that class.
- The `HLAobjectRoot.` prefix may be left out.
- Classes without a setting show their first three attributes.
- An empty value removes a default for that class.

Restart the server after changing this file.

### 2.4 Maps: `maps/raster/` (optional)

The datasource page can show its objects on a map (**Map** in the object panel, or **Map tab** for
a page of its own). The background comes from a raster image of the world in latitude/longitude,
for example the Natural Earth *Cross Blended Hypso with Shaded Relief, Water, and Drainages*
(10 m, `HYP_HR_SR_W_DR.tif`, 21600 × 10800 pixels):

```
<installation folder>/maps/raster/HYP_HR_SR_W_DR/HYP_HR_SR_W_DR.tif
```

- One sub folder per map. A map's id is its file name without the extension.
- **The default map** is `map.file` when set, else `map.default`, else the first one found.
  `map.file` can name a file outside `maps/raster`, and `map.files` adds more such files:

  ```properties
  map.file=D:/maps/region-north.tif
  map.files=D:/maps/world-light.tif; maps/extra/night.tif
  ```

- **Each user can pick another one.** With two maps or more, the map view has a map chooser (the
  default is marked). The pick is remembered in that browser for every datasource; choosing the
  default again forgets it. The **Map tab** link and `map.html?…&map=<id>` carry a pick that is not
  the default, so a copied link shows the same background. Users choose among the maps the server
  offers; they cannot point the server at a file of their own.
- **Formats:** TIFF (uncompressed is read straight from the file and is fastest; LZW, Deflate,
  PackBits and JPEG compressed TIFFs work through Java's own reader, more slowly), also PNG and JPEG.
- **Where it lies:** a world file next to the image (`.tfw`, `.wld`), else the GeoTIFF tags, else
  the whole globe (180° W to 180° E, 90° N to 90° S, as the Natural Earth rasters). Only
  latitude/longitude rasters are placed correctly; projected ones are reported in the log.
- **Tiles** (256 px, Web Mercator) are made from the raster when first needed and kept in
  `maps/cache` (rebuilt when the raster file changes). The world view is made in the background
  at start-up. The server log shows each map it found:

  ```
  Map:           HYP_HR_SR_W_DR (default): maps/raster/HYP_HR_SR_W_DR/HYP_HR_SR_W_DR.tif, 21600 x 10800, uncompressed TIFF, 3 x 8 bit RGB, strips of 1 row; whole globe (no georeferencing found); zoom 0-6
  ```

- Without a map the view still works, on a plain background with a grid.
- `map.enabled=false` in `etc/server.root.settings` removes the map from the Web UI and the API.

### 2.5 Map overlays: shapefiles (optional)

Shapefiles (coastlines, borders, roads, towns, airports, areas) can be drawn over the background
map. Users turn each one on or off in the map view (**▦ OVERLAYS**); the server decides which
files there are and how they look.

```properties
map.overlays=D:/gis/roads.shp; D:/gis/towns.zip
# or put them in maps/overlays/: the .zip packages as downloaded, or unpacked .shp files

map.overlay.roads.color=#c0392b
map.overlay.roads.width=1.5
map.overlay.towns.label=NAMN
map.overlay.towns.labelrank=INVANARE
map.overlay.towns.labelminzoom=6
```

- **Files.** A `.shp` with its `.dbf` (attributes: labels), `.prj` (coordinate system) and `.cpg`
  (text encoding) next to it, as GIS tools write them. An overlay's id is its file name without
  `.shp` (`roads`), and its settings are `map.overlay.<id>.*`.
- **Zip packages** are read as they are, nothing is unpacked: drop the `.zip` from the download
  site into `maps/overlays/`. A package may hold one layer or several, at any folder depth; each
  `.shp` in it is an overlay, with the `.dbf`, `.prj` and `.cpg` of the same name in the same
  folder of the zip. Other files in it (README, `.shx`, `__MACOSX/`) are ignored. When the same
  layer (same `.shp` name) is both in a zip and unpacked, the unpacked files are used and the log
  says so. A package or layer that cannot be read is shown in the log and in `problems` of
  `GET /api/maps`; the other overlays still work. To update a layer, replace its zip; the tiles
  are made again on the next start.
- **Coordinate systems** (from the `.prj`):
  - longitude/latitude: WGS 84, ETRS89, SWEREF 99 geographic;
  - Transverse Mercator: SWEREF 99 TM and its local zones, UTM, Gauss-Krüger;
  - Web Mercator.
  - Without a `.prj`, coordinates that look like longitude/latitude are taken as such.
  - Other projections (Lambert, RT 90 on its own datum, ...) are reported in the log and not drawn.
  - No datum shift is applied: data on ETRS89 / SWEREF 99 / WGS 84 is placed within a metre;
    other datums can be off by tens to hundreds of metres, and the log says so.
- **Shapes:** points, multipoints, lines and polygons (with holes), also their Z and M variants.
- **Text encoding** of the `.dbf`: the `.cpg` when there is one, else the dBASE language octet,
  else UTF-8 with a Windows-1252 fallback (Swedish data without a `.cpg` reads right).
- **Style** (`map.overlay.<id>.…`):

  | Key | Default | Meaning |
  |---|---|---|
  | `name` | the file name | Name in the overlay menu |
  | `color` | from a palette | Lines, outlines and point borders: `#rrggbb` or `#rrggbbaa` |
  | `width` | `1.2` | Line width in pixels |
  | `fill` | none | Polygon fill, usually half transparent: `#3498db40` |
  | `point` | `3` | Point radius in pixels; `0` draws no symbol |
  | `pointfill` | the colour | Point fill |
  | `minzoom` / `maxzoom` | `0` / `18` | Zoom levels the overlay is drawn at |
  | `visible` | `true` | Shown until the user turns it off |
  | `label` | none | `.dbf` field with the label text (e.g. `NAME`); without it, no labels |
  | `labelminzoom` | `6` | Labels from this zoom level on |
  | `labelrank` | none | Numeric `.dbf` field that decides which labels win where they would overlap |
  | `labelorder` | `desc` | `desc`: a higher value wins (population); `asc`: a lower one (rank 1 first) |
  | `labelcolor` | `#1b2631` | Label colour (drawn with a white halo) |

- **Labels** are placed by the browser, never cut at tile edges: at the point (to its right),
  the middle of a line, or the centre of a polygon. Where two would overlap, the one that ranks
  higher is shown.
- **Tiles** (transparent PNG) are drawn from the shapes when first asked for and kept in memory
  and under `maps/cache/overlays/<id>/` (made again when a file or the style changes). The files
  are read in the background at start-up; the server log shows each one:

  ```
  Map overlay:   towns: D:/gis/towns.zip!towns.shp, 1956 point features (point), SWEREF99_TM (Transverse Mercator, GRS 1980), 1956 labels (NAMN), 11.120..24.150 E, 55.340..69.050 N
  ```

- Which overlays are on is remembered in each browser (**Clear browser data** forgets it).

**Example: Natural Earth 10 m** (public domain, naturalearthdata.com). The downloaded zips go
in `maps/overlays/` as they are (`ne_10m_coastline.zip`, `ne_10m_airports.zip`, ...); the ids are
the `.shp` names in them:

```properties
map.overlay.ne_10m_coastline.name=Coastline
map.overlay.ne_10m_coastline.color=#1f4e79
map.overlay.ne_10m_coastline.width=0.9

map.overlay.ne_10m_admin_0_boundary_lines_land.name=Borders
map.overlay.ne_10m_admin_0_boundary_lines_land.color=#7d3c98cc
map.overlay.ne_10m_admin_0_boundary_lines_land.width=1.4

map.overlay.ne_10m_populated_places_simple.name=Towns
map.overlay.ne_10m_populated_places_simple.color=#4a2c2a
map.overlay.ne_10m_populated_places_simple.pointfill=#ffffff
map.overlay.ne_10m_populated_places_simple.point=2.5
map.overlay.ne_10m_populated_places_simple.label=name
map.overlay.ne_10m_populated_places_simple.labelrank=pop_max
map.overlay.ne_10m_populated_places_simple.labelminzoom=4

map.overlay.ne_10m_airports.name=Airports
map.overlay.ne_10m_airports.color=#b03a2e
map.overlay.ne_10m_airports.point=3.5
map.overlay.ne_10m_airports.minzoom=5
map.overlay.ne_10m_airports.label=name
map.overlay.ne_10m_airports.labelrank=scalerank
map.overlay.ne_10m_airports.labelorder=asc
map.overlay.ne_10m_airports.labelminzoom=7
map.overlay.ne_10m_airports.labelcolor=#7b241c
```

Field names are matched without regard to case, so `name` and `NAME` both work (the full
`ne_10m_populated_places` writes them in upper case). Its id is then `ne_10m_populated_places`.

---

## 3. Security

The REST API and the Web UI have **no login**. Anyone who can reach the server's port can
start and remove datasources and read everything they receive. Restrict access to the port
with a firewall or network segmentation.

---

By SimWeaver - released under Apache 2.0 license.
