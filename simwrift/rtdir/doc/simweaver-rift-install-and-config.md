# SimWeaver Rift — Installation and configuration

SimWeaver Rift is an HLA/DIS datasource gateway with a REST API and a browser-based Web UI.
This guide covers installing the server, starting it, and the configuration files it reads.

- **Using the Web UI:** see [web-ui.md](web-ui.md).
- **Integrating over the REST API:** see [rest-api.md](rest-api.md) and, for Junction, [junction-rest-api.md](junction-rest-api.md).

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
  `etc/server.root.settings`) and make sure the RTI can reach the container. With J2J (2.8), publish its port too, TCP and UDP (`-p 8844:8844 -p 8844:8844/udp`).
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
| `etc/j2j/network.settings`, `etc/j2j/network.key` | Optional: the J2J network of Junction servers (2.8) | At startup, and on *Reload member list* |
| `etc/auth/api-tokens.settings` | Optional: the API tokens, when API authentication is on (2.9) | At startup, and again within a second of each change |
| `etc/enumerations/SISO-REF-010.xml` | Optional: SISO's enumerations, to name entity types in the object view (2.10) | At startup |

### 2.1 Server settings: `etc/server.root.settings`

Server-wide settings. Restart the server after changing this file.

| Key | Default | Meaning |
|---|---|---|
| `webserver.port` | `8843` | HTTPS port of the Web UI and the REST API |
| `webserver.root` | `webroot` | The web folder |
| `webserver.keystore.path` | `keystore.jks` | Key store with the server certificate |
| `webserver.keystore.pwd` | `password` | Its password |
| `logdir` | `logs` | Log folder (2.7) |
| `syslog.enabled` | `false` | Also send the log to a syslog server (2.7) |
| `syslog.host` / `syslog.port` | / `514` (`6514` for TLS) | The syslog server |
| `syslog.protocol` | `udp` | `udp`, `tcp` or `tls` |
| `syslog.format` | `rfc5424` | `rfc5424` or `rfc3164` (the older BSD format) |
| `syslog.facility` / `syslog.level` | `local0` / `INFO` | Facility; lowest level sent: `SEVERE`, `WARNING`, `NOTICE`, `INFO`, `CONFIG`, `FINE` |
| `syslog.*` (more) | | Names, framing, lengths, queue and TLS trust: see 2.7 |
| `junction.enabled` | `true` | Run SimWeaver Junction gateways (see [junction.md](junction.md)). The 0.9.0 name `junktion.enabled` is still read |
| `junction.dir` | `etc/junctions` | Junction's configuration folder (0.9.0 name `junktion.dir` still read). `etc/junctions.dist` next to it holds every delivered sample, untouched |
| `junction.datasourceconfigs` | `true` | List each gateway endpoint as a datasource configuration; `false`: only the files in `etc/datasources` (Inspect on the Junction page still works) |
| `infra.enabled` | `true` | The Infra page (2.6): the DIS / C-DIS exercises and HLA federations the datasources and gateways have contact with; `false` collects nothing |
| `infra.hla.mom` | `true` | Ask each joined federation's MOM about its federates (2.6); `false`: the federates are known only by their traffic |
| `infra.hla.pollseconds` / `infra.hla.slowpollseconds` | `5` / `60` | Seconds between MOM requests while someone watches a federation / otherwise |
| `infra.dis.forgetseconds` | `600` | A DIS sender not heard for this long is forgotten (`0` = never) |
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
| `j2j.node.id` | | This server's id in the J2J member list (2.8). J2J itself is switched on by the member list, not here |
| `j2j.*` (more) | | J2J ports, the last resort on 443, timers: see 2.8 |
| `enumerations.siso.file` | `etc/enumerations/SISO-REF-010.xml` | SISO-REF-010, to name entity types (2.10); empty: none |
| `api.auth.enabled` | `false` | Require an API token for `/api` and a sign-in for the Web UI (2.9) |
| `api.auth.tokens` | `etc/auth/api-tokens.settings` | The token file (2.9) |
| `api.auth.sessionhours` | `12` | How long a browser stays signed in (2.9) |
| `api.auth.origins` | | More origins whose pages may change things with a signed-in browser, `;`-separated, e.g. a reverse proxy's `https://rift.example.org` (2.9) |

The server uses **HTTPS only**, so it needs a key store with its certificate. `keystore-gen-line.txt` has a `keytool` command that makes a self-signed one.

### 2.2 Datasource configurations: `etc/datasources/`

Each `.datasource.settings` file in `etc/datasources/` is one **datasource configuration**:
a recipe from which users start datasources in the Web UI (or with `POST /api/datasources`).
Some samples are provided; copy one and adapt it to add your own:

| File | Name | What |
|---|---|---|
| `hla3-rpr2.datasource.settings` | HLA3-RPR2 | HLA Evolved, RPR FOM v2.0 with Link 16 |
| `hla4-rpr2.datasource.settings` | HLA4-RPR2 | HLA 4, the same RPR FOM v2.0 with Link 16 in the HLA 4 format |
| `hla3-rpr2-talk.datasource.settings` | HLA3-RPR2-Talk | HLA Evolved with RPR v2 and the Talk FOM, taken from the running federation (`datasource.hla.foms=dynamic`: no Talk FOM is delivered; to use your own copy of the Talk FOM files, put them in `etc/fom/ieee1516-2010` and list them) |
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

### 2.6 The Infra page (0.9.20)

**Infra** in the top bar (`infra.html`) lists what this server has contact with, from everything that is running: Rift's datasources and Junction's gateway endpoints. Nothing needs configuring.

| List | What appears |
|---|---|
| DIS 6 / DIS 7 exercises | Every exercise a DIS socket hears, by protocol version, *before* the socket's own version and exercise filters: a DIS 7 datasource listening to exercise 1 also reports the DIS 6 exercise 3 on its port |
| C-DIS exercises | The exercises of the C-DIS a C-DIS datasource or endpoint decodes |
| HLA 1516-2010 / 1516-2025 federations | Every federation a datasource or an endpoint has joined |

- **One row per contact.** The same exercise heard by two sockets (a datasource and a gateway on port 3000, say) is two rows, each with its source. The source links to the datasource page or the Junction page.
- **A row opens to its live detail** (every 1.5 s):
  - *Exercise*: the PDU types, and each sender — address and port (*this computer* when it is one of the server's own addresses), the site and application numbers in its PDUs, entities, PDU types, PDUs per second, traffic and the last minute as a sparkline. Site and application are read from the first record of every PDU (8-bit numbers for the Live Entity family). A Junction endpoint does not list its own PDUs.
  - *Federation*: the RTI, the time implementation and the federates. Name, type, host, state and time state come from the MOM (`HLAmanager.HLAfederate`), with what each one sends and receives per second (from the MOM's counters) and what this datasource or endpoint receives from it (by producing federate). Click a federate for all its MOM values and **Ask the MOM for publications & subscriptions**: the RTI answers with report interactions, shown as object classes with their attributes and interaction classes (passive subscriptions marked).
- **How the MOM is used.** The federate that is already joined subscribes the MOM classes and asks for their values with attribute update requests: every `infra.hla.pollseconds` while someone has the federation open, every `infra.hla.slowpollseconds` otherwise. Publications and subscriptions are asked for only on the button. Nothing is sent that changes the federation (no `HLAsetTiming`, no switches). An RTI whose MOM lacks an attribute or report of the standard is shown with what it has; without a MOM the federates are known by their traffic only.
- **Breakout tab.** Tick rows and **Open breakout tab**: a tab (`infra.html?watch=key1,key2`) that keeps showing those items, live, while you work elsewhere. The keys stay the same when the same datasource configuration or gateway starts again, so the tab picks an item up again after a restart; until then it says *not connected now*. The marks are kept in your browser.
- The rows stay while their source runs. A DIS sender is forgotten after `infra.dis.forgetseconds` of silence, a resigned federate after five minutes.

### 2.7 Logging and syslog (0.9.22)

**The log folder.** The server writes everything it prints to `<logdir>/simwjunction.log`; warnings and errors also go to `simwjunction.err.log`. Each line starts with the date and time; each file is rotated at 1 MB, five files kept. A log line is `LEVEL: text`, and an *event* (below) adds its fields in brackets:

```
2026-10-26 21:01:54.476 NOTICE: Datasource HLA3-RPR2 (Blue) created  [event=datasource.created id=4b33… config=HLA3-RPR2 standard=IEEE1516_2010 federation=Blue by=10.0.0.5]
```

**Events.** Lifecycle and audit events, logged at level `NOTICE` (normal but significant) or, when something failed, `WARNING`. Each carries `event=<id>` and `by=` — who asked for it:

| `by=` | Meaning |
|---|---|
| an IP address, e.g. `10.0.0.5` | A REST call (the Web UI or a script) from that client. Behind a proxy that sets `X-Forwarded-For`: `10.0.0.1 (forwarded for 10.0.0.5)`; the forwarded address is the client's claim, not checked |
| `autostart` | A gateway with `junction.autostart=true` at start-up |
| `shutdown` | The server stopping |
| `rti`, `reconnect` | The RTI or the network (a lost connection, a reconnect) |
| `server` | The server itself |

| Event | Level | Fields |
|---|---|---|
| `server.started` / `server.stopping` / `server.stopped` | NOTICE | `version`, `port`, `pid` |
| `server.webfailed` | SEVERE | `port`: the web server could not start |
| `datasource.created` / `datasource.createfailed` | NOTICE / WARNING | `id`, `config`, `standard`, `federation`, `lsd`, `code` (failed) |
| `datasource.removed` / `datasource.removefailed` | NOTICE / WARNING | `id`, `config`, `federation`, `code` (failed) |
| `datasource.connectionlost` | WARNING | `id`, `config`, `federation`: the RTI connection (or the DIS socket) was lost; the datasource is removed |
| `gateway.started` / `gateway.startfailed` | NOTICE / WARNING | `gateway`, `instance`, `group`, `targets`, `code` (failed: also a start the server refused, e.g. `VARIABLES_REQUIRED`) |
| `gateway.stopped` | NOTICE | `gateway`, `instance`, `group` |
| `group.started` / `group.startfailed` | NOTICE / WARNING | `group`, `instance`, `started`, `failed` (startfailed: none started) |
| `group.stopped` | NOTICE | `group`, `instance`, `stopped` |
| `endpoint.connectionlost` / `endpoint.reconnected` | WARNING / NOTICE | `gateway`, `endpoint`, `federation`: a Junction HLA endpoint lost its RTI connection / joined again |

**Syslog.** With `syslog.enabled=true` the server sends the same log to a syslog server too (rsyslog, syslog-ng, a SIEM). Messages wait in a queue, so logging never waits for the network; while the syslog server cannot be reached the queue is kept and sending is retried (after 1 s, doubling up to a minute). When the queue is full, newer messages are dropped; after the next connection a NOTICE tells the syslog server how many. The local log notes once that syslog cannot be reached, and when it is connected again. The Web UI's server readout (top bar, tooltip) shows the syslog target and what was sent, queued and dropped; `GET /api/serverinfo` has it as `syslog`.

| Key | Default | Meaning |
|---|---|---|
| `syslog.enabled` | `false` | Send the log to a syslog server |
| `syslog.host` | (required) | Its name or address |
| `syslog.port` | `514`; `6514` for TLS | Its port |
| `syslog.protocol` | `udp` | `udp` (RFC 5426: one message per datagram, nothing is known about delivery), `tcp` (RFC 6587) or `tls` (RFC 5425: TCP with TLS) |
| `syslog.format` | `rfc5424` | `rfc5424`, or `rfc3164` for older collectors (BSD format, local time without a year) |
| `syslog.facility` | `local0` | `kern`, `user`, `mail`, `daemon`, `auth`, `syslog`, `lpr`, `news`, `uucp`, `cron`, `authpriv`, `ftp`, `local0` … `local7`, or a number 0-23 |
| `syslog.level` | `INFO` | Lowest level sent: `SEVERE`, `WARNING`, `NOTICE` (events and problems only), `INFO`, `CONFIG`, `FINE` (more detail than the local log shows) |
| `syslog.appname` | `simweaver` | APP-NAME (RFC 5424) / TAG (RFC 3164) |
| `syslog.hostname` | this computer's name | HOSTNAME in each message |
| `syslog.tcp.framing` | `octet` (rfc5424), `lf` (rfc3164) | TCP / TLS: octet counting (`<length> <message>`) or a line feed after each message (for collectors that read lines) |
| `syslog.maxlength` | `2048` (UDP; `1024` with rfc3164), `8192` (TCP, TLS) | Longer messages are cut |
| `syslog.queuesize` | `10000` | Messages kept while the syslog server cannot be reached |
| `syslog.bom` | `true` | RFC 5424: mark the message as UTF-8 with a BOM, as the RFC asks; `false` for collectors that show it as junk |
| `syslog.tls.truststore` | Java's CA certificates | TLS: a `.jks` or `.p12` file with the certificate of the CA that signed the syslog server's certificate (or that certificate itself) |
| `syslog.tls.truststorepassword` | | Its password |
| `syslog.tls.verifyhostname` | `true` | TLS: the server's certificate must name `syslog.host`; `false` accepts any certificate the trust store trusts |

A mistake in these settings (an unknown protocol, no host) is shown at start-up (`Syslog: WARNING: …`) and syslog stays off; the server starts anyway.

A message in RFC 5424 format:

```
<133>1 2026-10-26T21:01:54.476+01:00 rift-1 simweaver 2645 datasource.created [origin software="SimWeaver" swVersion="0.9.22"] Datasource HLA3-RPR2 (Blue) created  [event=datasource.created id=… by=10.0.0.5]
```

- PRI = facility × 8 + severity. `SEVERE` is severity 3 (error), `WARNING` 4, `NOTICE` 5, `INFO` 6, `CONFIG` and `FINE` 7 (debug).
- MSGID is the event id (`-` for other messages), so a collector can filter on it; the event's fields are in the message as `key=value` pairs.
- The process id is the PROCID. Line breaks in a message become ` | `.

Example: TLS to a central collector, events and problems only.

```
syslog.enabled=true
syslog.host=logs.example.org
syslog.protocol=tls
syslog.tls.truststore=etc/syslog-ca.p12
syslog.tls.truststorepassword=changeit
syslog.level=NOTICE
```

### 2.8 J2J network (0.10.0)

**J2J** (Junction to Junction) connects SimWeaver servers into one network: each server keeps encrypted links to the others, traffic finds its way through the servers that can reach each other, and the **J2J** page (`j2j.html`) shows every member, how it is reached and how well. Junction gateways on different servers share traffic through it in *sessions*: a gateway joins one with a `j2j` endpoint ([junction.md](junction.md), *J2J sessions*) — DIS PDUs as they are, or the objects and interactions of an HLA object model (RPR, NETN, Talk …).

J2J is **off** until `etc/j2j/network.settings` lists members. While it is off nothing listens and the **J2J** button is hidden in the top bar.

**Switching it on** — on every server of the network:

1. Put **the same** `etc/j2j/network.settings` on every server: the whole network, also the servers it cannot reach itself.
2. Put **the same** network key in `etc/j2j/network.key` (the J2J page makes one: *New key text*).
3. Set this server's own id in `etc/server.root.settings`: `j2j.node.id=sto-1`.
4. Restart the server, or open the J2J page and press **Reload member list**.

```
# etc/j2j/network.settings - the same file on every member; no members = J2J off
network.id=exercise-net
network.name=Exercise network North

member.sto-1.name=Stockholm lab
member.sto-1.addresses=10.10.0.5:8844; 192.0.2.10:8844
member.sto-1.fallback=gw.sto.example.org:443
member.sto-1.site=North

member.got-2.name=Gothenburg
member.got-2.addresses=10.20.0.7
member.got-2.site=West

# no addresses: behind NAT or a strict firewall, it dials out
member.mlm-3.name=Malmo
member.mlm-3.site=South
```

| Key | Meaning |
|---|---|
| `network.id` | The network's id (required). Servers of another network are refused |
| `network.name` | A name for the page |
| `member.<id>.name` | A name for the page. The `<id>` (1-32 of `a-z`, `0-9`, `-`) is fixed: it is how the servers know each other |
| `member.<id>.addresses` | Where it listens, tried in order, `;`-separated `host` or `host:port` (port `8844` when not given). None: nobody dials it; it dials the others |
| `member.<id>.fallback` | The last resort, `host:443`: J2J inside TLS, for sites where only HTTPS gets through |
| `member.<id>.site` | Optional: groups servers in the network diagram |
| `member.<id>.connect` | `false`: no server dials it (known to be out of reach); it is reached when it dials, or through others |

```
# etc/j2j/network.key - the same on every member; keep it secret (readable by the server's account only)
k1=3q2+7wQ0yk1o2J6m2b1pZ7C5mQ0jY8c9aYd6Hc1yJ0E=
```

- A key is `<key id>=<32 random octets in base64>`. The page shows only its id and a fingerprint (8 hex digits): compare fingerprints over the phone, never the key.
- **Changing the key without a stop:** add the new key as a second line on every server; then add `preferred=k2` on every server (new links use it); then remove the old line. Reload the member list after each step.

**Server settings** (`etc/server.root.settings`, read at start-up):

| Key | Default | Meaning |
|---|---|---|
| `j2j.node.id` | | This server's id in the member list (required when the list has members) |
| `j2j.port` | `8844` | TCP and UDP port of the links |
| `j2j.bind` | all addresses | Listen on this address only |
| `j2j.udp` | `true` | Also use UDP for best-effort traffic; `false`: TCP only |
| `j2j.fallback.port` | `443` | Port of the TLS last-resort listener; `0`: none. On Linux a port below 1024 needs the right to bind it (e.g. `setcap cap_net_bind_service=+ep` on `java`, or a firewall redirect from 443 to a high port) |
| `j2j.fallback.keystore` / `j2j.fallback.keystorepwd` | the web server's key store | Certificate of the TLS listener. It is not checked by the other side: the J2J handshake inside authenticates |
| `j2j.fallback.proxy` | | `host:port` of an HTTP proxy: the last resort is dialled through it with `CONNECT` |
| `j2j.fallback.retryseconds` | `60` | On the last resort, the primary addresses are tried again this often |
| `j2j.deadseconds` | `5` | A link that has not been heard from for this long is down (2-120) |
| `j2j.rekeyminutes` | `60` | A link gets fresh keys this often, without a break (at most 60) |
| `j2j.session.lostseconds` | `10` | A session member whose server has had no route for this long is *lost* |

**What a member's state means** (the J2J page, *Members*):

| State | Meaning |
|---|---|
| *direct* | A link to it is up; *How* says on what: `TCP`, `TCP + UDP`, `TLS 443` |
| *routed* | No link of its own, but reachable through other members (*via*) |
| *waiting* | Not reachable now; the next try is shown. A member never reached is tried less and less often, up to every 5 minutes |
| *refused* | The handshake was refused: another network key or network id, or this server is not in its list |
| *waits for it* | No addresses (or `connect=false`): it has to dial this server |
| *not started* | J2J did not start here: the page says why |

- **Ports and firewalls.** Each server listens on TCP and UDP `j2j.port`. It is enough that one side of a pair can be dialled: a server behind NAT is listed without addresses and dials out. UDP is optional; without it best-effort traffic goes over TCP.
- **Who is in.** Only the members of the list who hold the key get in. Every refused attempt (wrong key, not in the list, another network) is logged as `j2j.handshake.refused`, at most once per address and minute with a count.
- **Same list everywhere.** Each server tells the others a hash of its member list; the page warns when a member's list differs (*list differs*), so the file can be rolled out one server at a time.
- **Time.** Run NTP on every server. Times on the links are UTC; the page shows each member's clock offset.
- **What is shared.** Each server tells the others its version, start time, CPU, memory and number of gateways (the member's details on the page). Nothing on one server can control another.
- **Mistakes.** A problem in the files (an unknown setting, a malformed address, `j2j.node.id` not in the list, no key) stops J2J, not the server: the start-up banner (`J2J: NOT STARTED: …`) and the page say what. *Reload member list* does not apply a list with problems while J2J runs; the running list stays.

Log events (2.7):

| Event | Level | Fields |
|---|---|---|
| `j2j.started` / `j2j.config.invalid` | NOTICE / WARNING | `network`, `node`, `members`, `port` / `problems` |
| `j2j.members.reloaded` | NOTICE | `state` |
| `j2j.link.up` / `j2j.link.down` | NOTICE / WARNING | `member`, `address`, `direction`, `channel` / `reason`, `uptimeseconds` |
| `j2j.node.reachable` / `j2j.node.unreachable` / `j2j.route.changed` | NOTICE / WARNING / NOTICE | `member`, `via`, `hops`, `cost` |
| `j2j.channel.fallback` / `j2j.channel.primary` | NOTICE | `member`, `address`, `proxy`: moved to the last resort on 443 / back to a primary address |
| `j2j.handshake.refused` | WARNING | `from`, `address`, `reason`, `side` (`ours`: we refused; `theirs`: they refused us), `count` |
| `j2j.members.mismatch` | WARNING | `member`, `theirs`, `ours`: its member list differs |
| `j2j.session.joined` / `j2j.session.left` | NOTICE | `session`, `member` (the server; none for this one's), `gateway`, `endpoint`, `model` |
| `j2j.session.memberlost` / `j2j.session.memberback` | WARNING / NOTICE | `session`, `member`, `gateway`: its server has had no route for `j2j.session.lostseconds` / is back |
| `j2j.session.modelmismatch` | WARNING | `session`, `member`, `gateway`, `theirs`, `ours`: another DIS version or exercise; no PDUs between them |
| `j2j.listen.failed` / `j2j.fallback.failed` / `j2j.udp.failed` | WARNING | `port`: it could not be opened (J2J runs without it; the page shows it) |

The protocol on the links is described in [j2j-wire-protocol.md](j2j-wire-protocol.md), the REST calls of the J2J page in [j2j-rest-api.md](j2j-rest-api.md).

### 2.9 API authentication (0.11.0)

With **API authentication** on, every call under `/api` needs an **API token**: the REST API, its live events (`/api/events`) and the map tiles (`/api/maps`). Programs send the token in a header; people using the Web UI paste it once on the login page. It is **off** by default (`api.auth.enabled=false`), and then everything works as before.

There is one access level: a valid token may do everything the API offers. The web pages themselves (HTML, scripts, pictures) are not protected; they hold no data.

**Switching it on:**

1. Make a token on the server, in the installation folder (the server need not be stopped):

   ```
   ./run.sh --new-token=ops-wall          (Linux)
   run.bat --new-token=ops-wall           (Windows)
   ```

   It prints the new token, e.g. `swt_VHuYdfV1XddBP5F2VOzsBSGNGTKyOoKLX3FGXwwC_-g`, and adds its *hash* to `etc/auth/api-tokens.settings`. **Copy it at once**: it is stored nowhere and cannot be shown again. The command makes the token and stops; it does not start the server.

   Run it as the account the server runs as, so the server can read the file: as a systemd service (1b) `sudo -u simweaver ./run.sh --new-token=ops-wall`; in Docker (1c) `docker exec simweaver-rift ./run.sh --new-token=ops-wall` (with `etc` on a volume, so the file outlives the container).
2. Set `api.auth.enabled=true` in `etc/server.root.settings` and restart. The start-up lines say `API auth: on, 1 token in …`.

Run the command once per token. A token keeps working, across restarts, until its lines are deleted from the file or its expiry date (if it has one) has passed. Give each user or program its own token, so the log tells them apart and one can be revoked without the others.

| Option | Meaning |
|---|---|
| `--new-token=<name>` | The token's name: letters, digits, `.`, `_`, `-` (at most 64), first a letter or digit. It is what the log shows. `--new-token <name>` works too |
| `--expires=2027-06-30` | Optional: the last day the token is valid (UTC). Without it, it is valid until revoked |
| `--description="Ops wall screen"` | Optional: a note in the file |

**The token file** holds only the SHA-256 of each token, so a copy of the file does not let anyone in. The command makes it readable by its owner only (on Linux); keep it that way.

```
# etc/auth/api-tokens.settings
token.ops-wall.hash=sha256:6b0ab245fdb43c633d096ee2ba83b9fdbea2ac0a18fd44646d40665f9a7adbd1
token.ops-wall.created=2026-10-29T14:18:09Z
token.ops-wall.expires=2027-06-30
token.ops-wall.description=Ops wall screen
```

**Revoking a token:** delete its lines (`token.<name>.*`) from the file. The server reads the file again within a second of a change: from then on the token is refused, and browsers signed in with it are signed out. A token that has been lost cannot be shown again either: make a new one and delete the old one's lines.

**Programs** send the token in the `Authorization` header (RFC 6750):

```
curl -H "Authorization: Bearer swt_VHuY…" https://rift:8843/api/junction/gateways
```

Without a token, or with a wrong one, the answer is `401` with `WWW-Authenticate: Bearer realm="SimWeaver"`. `GET /api/auth/whoami` tells a program which token it is using ([rest-api.md](rest-api.md), 14).

**The Web UI** opens the login page (`login.html`) when it is not signed in. The token pasted there signs the browser in for `api.auth.sessionhours` (12): the server answers with a session cookie that the page's scripts cannot read, sent only to `/api`. **Sign out** in the top bar ends it (its tooltip names the token). A restart of the server signs every browser out. With the session cookie, a change (anything but reading) is accepted only from this server's own pages, so another web site cannot make a signed-in browser do something; behind a reverse proxy with another address, add the proxy's address to `api.auth.origins`.

**Wrong tokens:** an address that sends 10 wrong tokens within a minute gets `429 Too Many Requests` for the rest of that minute, also for a correct token. Browsers already signed in from that address carry on. Each refusal is logged as `api.auth.refused`, once per address and minute, with a count of the others.

**Log events** (2.7):

| Event | Level | Fields |
|---|---|---|
| `api.auth.on` | NOTICE | `tokens`, `file`: at start, when API authentication is on |
| `api.auth.tokens.invalid` | WARNING | `problem`: a line of the token file that is ignored (a bad hash, name or date) |
| `api.auth.refused` | WARNING | `address`, `reason` (no token, a wrong token, an ended session, a change from another site), `count` |
| `api.auth.signedin` / `api.auth.signedout` | NOTICE | `token`: a browser signed in / out with it |

With authentication on, the `by=` of every event names the token as well as the address, e.g. `by="10.0.0.5 (token ops-wall)"`.

**Not covered:** J2J links (2.8) have their own network key and port; the syslog connection (2.7) is outgoing.

### 2.10 Entity type names: SISO-REF-010 (0.11.1)

The object view shows an entity type in words below its numbers, with the country's ISO code before it:

```
EntityType   1.1.205.2.1.5.0
             SWE  CV9040A · Armored Fighting Vehicle
```

The names come from **SISO-REF-010** (*Enumerations for Simulation Interoperability*), delivered as `etc/enumerations/SISO-REF-010.xml` (release v37, 2026-05-25). It is read once at start; the start-up lines say `Enumerations: SISO-REF-010-v37 (2026-05-25), 27370 entity types`. They work for HLA and DIS datasources alike, for every value of the entity type form (`EntityType`, `AlternateEntityType`, a munition type in a `WeaponFire` …), and the object search finds objects by them.

When the file does not list a type down to its last number, the nearest broader name is shown, marked with ≈: an unknown specific gives the subcategory (*≈ Combat Vehicle 90 (CV90)*), an unknown category the domain and kind (*≈ Air platform*). Hover over the name for the whole chain. Kind 0 (*Other*) is not named.

**A newer release:** SISO publishes new releases a few times a year. Put SISO's `SISO-REF-010.xml` in place of the delivered one (or set `enumerations.siso.file` to another file) and restart. The schema (`.xsd`) is not needed. Without the file, or with `enumerations.siso.file=` empty, entity types are shown as numbers only.

**Licence:** SISO-REF-010 is © SISO Inc. and distributed unchanged under the licence in its own `<copyright>` element, with the attribution *Reprinted with permission from SISO Inc.* (`etc/enumerations/NOTICE.txt`). The BSD 3-Clause License of SimWeaver does not cover it.

---

## 3. Security

Without API authentication (2.9, off by default) the REST API and the Web UI have **no login**: anyone who can reach the server's port can start and remove datasources and gateways and read everything they receive. Switch it on with `api.auth.enabled=true`, and restrict access to the port with a firewall or network segmentation either way.

With API authentication on, a token is a password: anyone who holds it can do everything the API offers. Give each user or program its own, keep them out of scripts that others can read, and revoke one by deleting its lines as soon as it may have leaked. The server keeps only their hashes. Traffic is always HTTPS, so tokens and session cookies never cross the network in clear text; with a self-signed certificate, check its fingerprint once on each client, or an attacker in between could read them.

J2J (2.8) links are encrypted and authenticated with the network key (AES-256-GCM, keys from an X25519 exchange, so recorded traffic stays secret even if the key leaks later). Whoever holds `etc/j2j/network.key` and is in the member list is trusted as a member: keep the file readable by the server's account only, and make a new key when someone leaves. The J2J port must be reachable by the other members only.

Syslog over UDP or TCP is not encrypted, and UDP messages can be lost without notice. Use `syslog.protocol=tls` when the log crosses networks you do not trust (2.7). The `by=` of an event is the client's address as the server sees it: without API authentication it says *from where*, not *who*; with it, also which token.

---

By SimWeaver - released under the BSD 3-Clause License.
