# SimWeaver Junction

SimWeaver Junction is a server for distributed simulation with HLA and DIS. It contains:

- **Rift**, for watching the data exchange in federations and networks.
- **Junction gateways**, which bridge them.
- **A web UI** in the browser, so clients install nothing.

It is written in Java 11 with no third-party libraries. The only external piece is the HLA RTI
of your choice, and only for the HLA parts.

Version 0.9.20 · BSD 3-Clause License · © 2026 Simweaver AB

---

## What it does

**Rift: look into a federation or a DIS network**

- **Datasources.** Join an HLA federation or listen to a DIS exercise, from a saved
  configuration.
- **Objects.** Browse the object instances by class, with fully decoded attribute values. RPR FOM
  v2 and v3, NETN and your own FOM modules are supported, or the FOM is taken from the
  federation itself.
- **Interactions.** Follow them live, with rates per class. Link 16 messages are decoded down to
  the network header and the J-word framing.
- **Map view.** See the entities on a map:
  - dead reckoned, with radar and jammer beams, laser designators, fire and detonation flashes;
  - over a GeoTIFF background;
  - with shapefile overlays read straight from their `.zip` packages: coastlines, borders,
    towns, airports, roads.
- **REST API and SSE events** for everything the UI shows.

**Junction: bridge federations and networks**

- **Endpoints:**
  - HLA Evolved (IEEE 1516-2010)
  - HLA 4 (IEEE 1516-2025), also over the Federate Protocol
  - DIS 6 and DIS 7
  - C-DIS (Compressed DIS, SISO-STD-023-2024), full and partial updates
  - Pitch Talk
- **RPR FOM v2.0 ⇄ v3.0**, converted by a rules table plus code for the structural
  differences: IFF modes, articulated parts and jamming.
- **DIS ⇄ RPR**, including:
  - entities, fire, detonation, collision;
  - radios and audio;
  - emitters, radar and jammer beams;
  - designators, IFF;
  - Link 16 Signal PDUs.
- **DIS 7 ⇄ C-DIS PDU relay**: all 22 C-DIS PDU types passed PDU by PDU, not through the RPR model.
- **Class filters**, instance renaming and object reference rewriting.
- **Value rules**, e.g. one entity type replaced by another in passing.
- **Pitch Talk** channel mapping, and a radio bridge to RPR/DIS.
- **FOM from the federation.** An HLA endpoint set to `dynamic` joins and reads the
  federation's FDD from the MOM, so it bridges whatever FOM the federation runs, without copies
  of the modules.
- **One process, several RTIs.** Each endpoint can load its own RTI library, so one gateway can
  bridge two vendors or versions.
- **Groups of gateways**, started together with shared variables.
- **The Config Assistant** page writes gateway configurations for you.

**Infra: what the server has contact with** (0.9.20)

- Every **DIS 6, DIS 7 and C-DIS exercise** the datasources' and gateways' sockets hear, with each
  sender's address, its site and application numbers, PDU types and activity.
- Every **HLA 1516-2010 and 1516-2025 federation** they have joined, with the federates from the
  MOM: host, type, state, what each sends and receives, and on request its publications and
  subscriptions.
- A **breakout tab** keeps watching the exercises and federations you pick.

---

## Requirements

| | |
|---|---|
| **Java** | 11 or later (the code is compiled for Java 11) |
| **HLA RTI** | Only for HLA datasources and endpoints, e.g. Pitch pRTI. DIS works without one. |
| **Browser** | A current Microsoft Edge or Google Chrome |
| **OS** | Windows or Linux. Docker and systemd examples are in `simwrift/rtdir/etc`. |

---

## Source layout

```
simwcommon/        shared library: HLA codec and RTI sessions, DIS <-> RPR, FOM model (IEM),
                   settings, REST/SSE server, JSON, logging, geo
simwjunction/      the Junction gateways: endpoints, routers, RPR conversion, Talk bridge, REST API
simwrift/          the Rift server, datasources, decoders, map, and the main class
  rtdir/           the runtime folder: what gets installed
    etc/           server settings, datasource and gateway configurations, FOMs, mappings;
                   etc/junctions.dist holds every sample gateway configuration, untouched
    webroot/       the web UI (plain HTML, CSS and JavaScript)
    doc/           user documentation
    maps/          background rasters, shapefile overlays, tile cache
    lib/           the application jar goes here
fakerti/           in-memory test RTIs (1516-2010 and 1516-2025) for the self tests
build.sh           command-line build
```

Each module has `src/`. The self tests are in `test/` next to it.

---

## Build

**In IntelliJ IDEA:** build the artifact `simweaverrift.jar`. It holds the three modules.

**From the command line** (Linux, macOS, or Git Bash on Windows):

```sh
./build.sh
```

`build.sh` compiles the three modules and the tests, then packs
`out/artifacts/simweaverrift/simweaverrift.jar`. It compiles against the HLA API jars, and only
the API classes are needed. Point it at your RTI's jars with `HLA_API_CP`. Use `:` between
files on Linux and macOS, `;` in Git Bash on Windows:

```sh
HLA_API_CP=/opt/prti1516e/lib/prti1516e.jar ./build.sh
```

The RTI is loaded by reflection at run time, so the jar itself contains no RTI.

---

## Install and run

1. Copy `simwrift/rtdir` to the installation folder.
2. Put `simweaverrift.jar` in its `lib/`.
3. Create a key store for HTTPS. The command is in `keystore-gen-line.txt`; use your own
   password, and set it in `etc/server.root.settings`.
4. Set `RTI_JAR` in `run.bat` (Windows) or `run.sh` (Linux) to your RTI's Java library. Leave it
   empty for DIS only.
5. Start it with `run.bat` or `run.sh`, and open `https://<server>:8843` in a browser.

The installation guide (`doc/simweaver-rift-install-and-config.md`) covers more: services,
Docker, maps and every setting.

---

## Documentation

All in `simwrift/rtdir/doc/`:

| File | Contents |
|---|---|
| `simweaver-rift-install-and-config.md` | Installation, server settings, datasources, maps and overlays |
| `README.md` | The web UI: datasources, objects, interactions, the map view |
| `rest-api.md` | Rift's REST API and SSE events |
| `junction.md` | Junction: gateways, endpoints, routers, value rules, Pitch Talk, groups, RPR v2 ⇄ v3, DIS |
| `junction-rest-api.md` | Junction's REST API |

---

## Self tests

The self tests need no RTI. Build first, then run each test from the top folder. Each prints
`N checks, M failures`.

```sh
sh fakerti/build-fake.sh          # the test RTIs, once
java -cp out/classes:out/test:<HLA API jars> com.simweaver.junction.BridgeSelfTest
```

`simwjunction/CLAUDE.md` lists every test and what it covers. It is also where the developer
notes on the design and its invariants are kept.

---

## License and contributing

SimWeaver Junction is open source software under the BSD 3-Clause License. See
[`LICENSE`](LICENSE). The name covers the whole source set: `simwcommon`, `simwjunction` and
`simwrift`.

Contributions are welcome. Read [`CONTRIBUTING.md`](CONTRIBUTING.md) first. Contributors accept
the [Contributor License Agreement](CLA.md) before a contribution can be merged.

The HLA RTI is not part of SimWeaver Junction. It is licensed by its vendor and installed
separately.
