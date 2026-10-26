# SimWeaver Rift — Web UI

The Web UI is the browser front end of **SimWeaver Rift**, the HLA/DIS datasource gateway.
From a browser you can:

- start a datasource (join an HLA federation) from one of the server's configurations
- browse the object instances it discovers, by class, with decoded attribute values
- follow the interactions live, with rates per class
- stop and remove the datasource again

The SimWeaver Rift server serves the Web UI itself. Clients need only a browser: there is
nothing to install, and no internet access is needed.

---

## What you need

| | |
|---|---|
| **Server** | SimWeaver Rift (Java 11), with an RTI installed for the HLA standards you use |
| **Browser** | A current Microsoft Edge or Google Chrome |
| **Network** | Access from the browser to the server's HTTPS port |

---

## 1. Put the web files in place

The server serves the Web UI from its **web folder**. This is the folder set when the server
starts; the default of the REST library is `webroot`, relative to the server's working
directory.

Unzip `simweaver-rift-web.zip` so that `index.html` sits directly in that folder:

```
webroot/
├── index.html
├── datasource.html
├── junction.html
├── config-assistant.html
├── favicon.ico
├── css/
├── js/
└── img/
```

The `docs/` and `tools/` folders in the zip are not needed at runtime. You can leave them
out.

> **After updating the web files**, reload the page once with **Ctrl+F5**, so that the
> browser does not keep using its cached scripts.

---

## 2. Add datasource configurations

Each file in `etc/datasources/` (relative to the server's working directory) is one
**datasource configuration**, which you can start from the Web UI. A file is listed only
when it contains both of these settings:

```properties
datasource.name     = HLA3-RPR2
datasource.standard = IEEE1516-2010
```

- `datasource.standard` must be `IEEE1516-2010` (HLA Evolved) or `IEEE1516-2025` (HLA 4).
  Files without these two settings are skipped silently.
- The folder is read again each time the overview page loads or you press **Reload**.
  A new or changed file shows up without restarting the server.

### Optional: which attributes the object list shows

The object list shows a few key attributes per object, for example `EntityIdentifier`,
`EntityType` and `ForceIdentifier` for RPR entities. To change them, create
`etc/listprofiles.properties`. Each line names a class and the attributes to show, in order:

```properties
rift.listprofile.HLAobjectRoot.BaseEntity = EntityIdentifier, EntityType, ForceIdentifier
rift.listprofile.EmbeddedSystem.RadioTransmitter = HostObjectIdentifier, Frequency, TransmitterOperationalStatus
```

- A setting also applies to the subclasses of that class.
- You may leave out the `HLAobjectRoot.` prefix.
- Classes without a setting show their first three attributes.
- The file is read when the server starts, so restart the server after you change it.

---

## 3. Start the server

Start SimWeaver Rift the way your installation does it, for example with its start
script or as a Windows service. When it is ready, the console shows:

```
HTTPS-server started at 8843
```

The number is the port to use in the next step.

The server uses **HTTPS** only. It needs a key store with its certificate; the REST library's
default is `keystore.jks` in the working directory.

---

## 4. Open the Web UI

Browse to the server's address and port:

```
https://<server>:<port>/          for example  https://localhost:8843/
```

If the server uses a self-signed certificate, the browser warns about it on the first visit.
Accept the warning, or have the certificate installed as trusted on the client machines.

> Links to a datasource page (`datasource.html?id=…`) can be bookmarked and shared. They work
> for as long as that datasource is running.

---

## Using the Web UI

### Top bar (all pages)

- **SimWeaver logo** and the page's name; the buttons next to it switch between **Rift datasources**,
  **Junction gateways** and the **Config assistant**.
- **Zulu clock:** the time in UTC, with the date-time group.
- **LINK lamp:** *LINK UP* while the server answers, *LINK LOST* when it cannot be reached.
  The page keeps retrying by itself.
- **Server readout:** the server version, the server process's CPU (in percent of the whole
  machine) and its memory use. Hover over it for details such as uptime, threads and Java
  version. It turns amber from 70 % and red from 90 %. It is hidden on narrow screens.
- **Day / Night:** switches between the dark and the light theme. The browser remembers the
  choice.

### Saved in this browser (page footer)

The pages remember a few choices in your browser: the theme, the background map you picked,
where each map was looking, the map layers, list or map, whether the gateway configurations are
shown, and everything filled in on the Config assistant. **Saved in this browser…** at the bottom
of every page (in the map tab: under the entity list) lists them with **Clear** for each, and
**Clear all**. This affects only your browser, never the server or other users.

Datasource configurations, gateways and maps are not stored in the browser: they always come
from the server. Map tiles the browser keeps are replaced by themselves when the server's map
file changes.

### Overview page

**01 · Available datasource configurations.** One card per configuration file.

1. Press **▶ Create datasource** on a card.
2. HLA: check the **LSD** (*RTI configuration* for HLA 4). It is the configuration's default.
   When `datasource.hla.lsd` lists several, they are offered as buttons; any other value can be
   typed, and empty is the RTI's own default. **↺ Default** puts the default back. Picking one
   rescans the federations on it.
3. Check the **federation** name. It is filled in from the configuration, and you may change
   it.
   - **Discovered federations** lists the federations the server finds for the
     configuration's RTI. Click one to use it. **⟳ Rescan** searches again.
   - **↺ Config value** puts the configured name back.
4. Press **▶ Create datasource** in the dialog. The server connects to the RTI and joins the federation, which may take
   a few seconds.
   - **On success**, the dialog closes and the new datasource is highlighted in the list.
   - **If joining fails**, the dialog stays open and shows the server's message, for example
     `ConnectionFailed: …` when the RTI cannot be reached. Correct the federation name and
     try again, or cancel.

**Configurations from Junction gateway endpoints.** Every endpoint of every gateway file in
`etc/junctions` is also a datasource configuration (chip *JUNCTION*), so you can look at what a
gateway's federation or network sees. They are made by the server from those files, not saved in
the browser, and are listed in a group of their own below your configurations, folded away
until you press **▸ Show** (the browser remembers it). They disappear when their gateway file is
removed. The server setting `junction.datasourceconfigs=false` leaves them out of the list; the
Junction page's **Inspect** still works.

**02 · Running datasources.** One row per running datasource.

- Click a row to open its datasource page.
- **✕ Remove** resigns from the federation, disconnects and removes the datasource. You are
  always asked to confirm first.

### Datasource page

The header shows the datasource name, its state (for example `JOINED`) and the federation.
Click **Config** to see the configuration it runs with. Below the header are three panels:

**Classes** (left)
- The object classes that have instances, with counts summed up the tree.
- Click a class to show only its objects, including subclasses. You can select several
  classes; **✕ All** clears the selection.
- Type in the filter box to find a class by name.

**Object instances** (middle)
- **Search** looks in the label, the instance name and the shown attributes.
- **Sort** by label, class, last update or newest.
- Tick **+ related** to add the objects that refer to the shown ones. For example, it adds
  the radios hosted on the selected aircraft.
- The list loads 200 objects at a time, and more as you scroll.
- Each row shows the label (the RPR `Marking`, when there is one), the class, the key
  attributes, and how long ago the object was last updated.
- New objects are not pushed into the list while you read it. A **▲ N new objects** banner
  appears instead; click it to reload the list.
- **Click a row** to open the object:
  - **All attributes**, decoded and updated live. Structured values open as trees.
    - Derived values (latitude, longitude, heading, speeds) are shown with the RPR `Spatial`
      attribute.
    - `DRSpatial` is the dead-reckoned position "now", updated about once per second.
  - **HEX** shows the raw bytes of an attribute.
  - **References** lists the objects this one points to, and **Referenced by** the objects
    that point to it.
  - Click any object chip to jump to that object. An object that is not in the current list
    is pinned above it.
  - **⎘ Interactions** shows only the interactions that involve this object.

**Interactions** (right)
- **Rate ticker:** one row per interaction class, with the current rate and a 60-second
  graph. Click a row to show only that class.
- **Live tape:** the newest interactions first, with UTC time to the millisecond and the time
  since the previous row.
  - Repeated interactions of the same class from the same federate are folded into one row
    (`×23`).
  - Click a row to see all its parameters, decoded.
  - Link 16 messages are labelled with their J-message, for example `J3.2 Air Track`, from HLA
    and from DIS Signal PDUs. A row shows the source track number and the NPG, and the details
    show each message's name and category, the time slot and the transmit time. The message
    contents (positions, tracks) are defined in the restricted MIL-STD-6016 and are shown as hex.
- **❚❚ Pause**, or scrolling down, holds the tape. A banner counts what arrives meanwhile;
  click it to go back to live.
- At very high rates the server sends at most 50 interactions per second to the page. The
  rates stay exact, and the tape shows *sampling*.

The server keeps the latest 10 000 interactions per datasource; older ones leave the tape's
history.

**Map** (in the object panel: **☰ List | ◉ Map**, or **⧉ Map tab** for a page of its own)
- The objects of the current filter (class tree, search, *+ related*) that have a position, on a
  map: circles coloured by force (friendly blue, opposing red, neutral green, other yellow),
  sized by zoom, with a speed line in the heading. The labels show the marking, entity id and
  class; where they would overlap, only some are shown (**LBL** switches automatic / all / off).
- Positions are the dead-reckoned `Spatial`, updated once a second and moved on in between.
  Objects without a position are counted in the status line, not drawn.
- **Carried objects** (radios, mounted or attached parts) are not drawn on their own: their host
  has a **+N** badge. Click it to fan them out, or use *Carries N* in the host's card.
- **Zoom** with the wheel, a pinch, double-click, **+ / −** or the keys `+` `-`; drag or use the arrow
  keys to pan. **⤢** shows all entities.
- **Click an entity** for its card: position, altitude, heading, speed, what it carries.
  **⌖ Center**, **◎ Follow** (keeps it in the middle until you move the map; `F` key, `Esc` stops),
  **☰ Details** (back to the list, with the object opened).
- **Go to:** type a marking, entity id or class in the box at the top right.
- In the list, an opened object has **⌖ On map** (a radio shows its host).
- The **Map tab** has the same map with a list of the entities on it (click: go to; double-click:
  follow).
- **Background map.** The server decides the default (`map.file`). When it has more than one map,
  the chooser under the zoom buttons switches. *Server default: …* follows the server, also when
  its setting changes; any other pick is remembered in this browser for every datasource, and the
  Map tab link carries it (`&map=<id>`).
- **▦ OVERLAYS** (when the server has shapefile overlays, see *2.5 Map overlays* in the
  installation guide): coastlines, borders, roads, towns, airports and the like over the
  background. The menu turns each one on or off (remembered in this browser), with the number of
  shapes and the zoom its labels start at. Labels avoid each other: where two would overlap, the
  higher ranked one (e.g. the larger town) is shown, and more appear as you zoom in.
- **✸ FLASHES** (on by default): a short flash where a weapon fires (small, yellow-orange) and
  where a detonation happens (larger, with a red ring), then nothing. It covers `WeaponFire`,
  `MunitionDetonation` (RPR v2), `Fire.*`, `Detonation.*` (RPR v3, also `Explosion` and
  `DirectedEnergyFire`) and their subclasses, from HLA and from DIS Fire and Detonation PDUs. The
  place is the interaction's firing or detonation location, else the firing (or target) entity.
  Flashes are at most a second late (the map asks once a second); events older than 4 s are not
  shown.
- **⌁ LASERS** (on by default): laser designators as a red line from the designating entity to
  the laser spot, with a pulsing ring on the spot. When the designator names a designated object
  and the spot is on it (within 50 m), the ring goes around that object and moves with it;
  otherwise the spot is the designator's spot location. A designator with output power 0 is not
  lasing and is not drawn. It covers `EmbeddedSystem.Designator` (RPR v2 and v3) and its
  subclasses; in a DIS datasource from Designator PDUs. The designator is listed under its host
  (*Carries*); its card shows the designated object, the spot, the range, code, power and
  wavelength, and the designated object's card says *Designated by*.
- **◔ Beams** (off by default): radar beams (green) and jammer beams (magenta) as sectors from
  their host: the host's heading plus the beam's azimuth centre, the azimuth sweep on either side.
  The RPR FOM has no range for a beam, so you choose the length (25 to 400 km, or a fixed length
  on the screen). The selected host's beams are drawn stronger. Beams come from
  `EmitterBeam.RadarBeam` / `JammerBeam` objects (and subclasses), found through their emitter
  system's host; in a DIS datasource from Electromagnetic Emission PDUs. A beam is also listed
  under its host (*Carries*), and its card shows the emitter, function, azimuth, elevation,
  frequency and ERP. Pitch and roll of the host are ignored.
- The layer choices are remembered in this browser for every datasource.
- The maps and their settings: see *2.4 Maps* in the installation guide. The server
  setting `map.enabled=false` removes the map.

---

## Troubleshooting

| What you see | Cause and what to do |
|---|---|
| The browser warns that the connection is not private | The server uses a self-signed certificate. Accept it, or install the certificate as trusted |
| The page looks broken, or something is missing after an update | The browser uses old cached files. Reload with **Ctrl+F5** |
| **LINK LOST** | The server cannot be reached: it is stopped, or the network is down. The page reconnects by itself when the server is back |
| No configurations are listed | `etc/datasources/` is missing or empty, or the files lack `datasource.name` / `datasource.standard`, or the standard is not `IEEE1516-2010` / `IEEE1516-2025` |
| *Federation discovery not available* in the create dialog | The server cannot list federations for this configuration. Type the federation name yourself |
| Create fails with a message such as `ConnectionFailed: …` | The RTI or its LSD/CRC cannot be reached, or the federation cannot be joined. The message comes from the RTI |
| *Datasource not found* on the datasource page | The datasource was removed, or the server was restarted (running datasources are not kept over a restart). Create it again from the overview |
| An object shows *No attribute values received yet* | The federate that owns it has not sent any attribute updates yet |
| The server readout is missing from the top bar | The window is narrower than 640 px, or the server is older and has no `/api/serverinfo` |

---

## Trying the Web UI without a federation

The `tools/` folder has a mock server that behaves like SimWeaver Rift with a simulated RPR
scenario. It needs only Python 3, and no RTI. From the folder where you unzipped the Web UI:

```
python tools/mock-server.py --port 8080 --objects 300
```

Then open `http://127.0.0.1:8080/` (plain HTTP). Use `--objects 10000` for a large scenario.

---

## Config assistant

The top bar's **Config assistant** opens `config-assistant.html`. It writes configuration files for
you; it does not change anything on the server.

- **Rift:** datasource configurations (`etc/datasources/`), the server settings
  (`etc/server.root.settings`, with a `keytool` command for the certificate) and the object list
  attributes (`etc/listprofiles.properties`).
- **Junction gateways:** gateways (`etc/junctions/*.junction.settings`), new endpoint and router kits,
  and groups with group variables.

In the Junction part, each endpoint and the router either **uses a kit** (a shared settings file in
`etc/junctions/endpoints/` or `routers/`, which many gateways can name) or has its settings **written in
the gateway file**. The page shows what a kit holds and which gateways already use it, and can save new
kits.

Every box on the left says what it is (**Gateway**, **Endpoint**, **Router**, **Datasource
configuration**, …) and, on its **Goes into** line, which file it ends up in. Each kind of file has its
own coloured label (*Gateway file*, *Endpoint kit*, *Router kit*, *Existing kit* = named, not written),
used on both sides. **show ↗** on a box jumps to its file; **Show in the form** on a file goes back to
its box.

Start from one of the suggested situations (for example *HLA ⇄ DIS 7* or *DIS 7 network*) and adjust the
fields; each field explains itself. The **Files to create** panel shows each file's path and content
as you type, with **Copy**, **Path** and **Download** buttons, and lists what to fix or check: for
example a missing FOM, a gateway whose two endpoints would join the same federation, or a file that
would replace one the server already has (the page reads the server's lists to know). Put the files on
the server at the paths shown, then press **Reload** on the Rift or Junction page.

What you filled in is remembered in this browser; **Start over** clears a section.

---

## Junction gateways

The top bar's **Junction gateways** opens `junction.html`: gateways that bridge HLA federations,
DIS networks and Talk federations, configured in `etc/junctions`. Their endpoints also show up in the
datasource list here, marked "Junction". See [junction.md](junction.md) and
[junction-rest-api.md](junction-rest-api.md).

---

## For integrators

Everything the Web UI shows comes from the server's REST API and its live event stream.
The same API is open to other tools. See [docs/rest-api.md](docs/rest-api.md).

---

By SimWeaver - released under Apache 2.0 license.
