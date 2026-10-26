# SimWeaver Junction — gateways in Rift

Junction bridges federations and networks: HLA Evolved (IEEE 1516-2010), HLA 4 (IEEE 1516-2025,
also over the Federate Protocol), DIS 6 and DIS 7, C-DIS (Compressed DIS, SISO-STD-023-2024) and Pitch Talk. It runs inside the Rift server,
has its own page (`junction.html`, linked from Rift's top bar) and REST API (`/api/junction`, see
[junction-rest-api.md](junction-rest-api.md)). Every endpoint of every gateway also appears in
Rift's datasource list, so you can **Inspect** what a federation sees.

Version 0.9.20. Java 11, no libraries apart from the HLA RTIs.

**Junction** is the product (the page, the API, Rift's `junction.*` server settings); a **junction
configuration** is one gateway (a `*.junction.settings` file in `etc/junctions`, with its `junction.*`
keys).

Up to 0.9.0 the product was spelled *Junktion*. The old spelling is still accepted where a
running system may depend on it: the API path `/api/junktion`, Rift's `junktion.enabled` and
`junktion.dir` settings, and the page address `junktion.html` (it forwards to `junction.html`).
Since 0.9.11 the Java package is `com.simweaver.junction` too.

---

## Contents

1. [Concepts](#1-concepts)
2. [The configuration folder](#2-the-configuration-folder)
3. [Gateway files](#3-gateway-files)
4. [Endpoint settings](#4-endpoint-settings)
5. [Router settings (IEM)](#5-router-settings-iem)
6. [Pitch Talk router](#6-pitch-talk-router)
7. [Groups and group variables](#7-groups-and-group-variables)
8. [RPR FOM v2.0 ⇄ v3.0](#8-rpr-fom-v20--v30)
9. [DIS endpoints](#9-dis-endpoints)
   - [C-DIS endpoints](#c-dis-endpoints)
   - [The PDU relay](#the-pdu-relay)
10. [RTI libraries per endpoint](#10-rti-libraries-per-endpoint)
11. [The page](#11-the-page)
12. [Limits and things to verify](#12-limits-and-things-to-verify)
13. [Self tests](#13-self-tests)

---

## 1. Concepts

| Term | Meaning |
|---|---|
| **Gateway** (junction) | Two or more **endpoints** and one **router**, from one `*.junction.settings` file. Started from the page or the REST API; several can run at once. |
| **Endpoint** | One side: an HLA federation (joined as a federate), a DIS or C-DIS network (UDP), or a Pitch Talk federation. Has a **target** chosen at start: federation name or DIS exercise id. |
| **Router** | Moves traffic between the endpoints. `iem`: objects and interactions by class, with class filters, instance renaming, reference rewriting and RPR v2/v3 conversion. `talk`: Pitch Talk channels by name, with an optional radio bridge to RPR/DIS. `pdu`: DIS PDUs passed as they are between DIS 6, DIS 7 and C-DIS endpoints (section 9). |
| **Kit** | A reusable endpoint (`endpoints/*.endpoint.settings`) or router (`routers/*.router.settings`) that gateway files refer to and override. |
| **Group** | Gateways with the same `junction.group`, started and stopped together, with **group variables** (`GROUPVAR1` …) filled in at the start. |

What the IEM router does for an object discovered in endpoint S:

- It creates a copy in every other endpoint D whose model has the class (or, with `promote`, its nearest superclass), under the same name, or `name@S` when the name is taken there (`router.names.policy`), or under a name from a template (`router.names.template`, see *Naming copies* below).
- It passes on the attributes D's class has. Equal encodings are passed as they are. Others are converted value by value (`transcode`), and object references (`RTIobjectId`, also inside records and arrays) are renamed to the copies' names.
- Attribute values are cached, so a late joiner or a provide request in D is answered by the gateway itself. Requested attributes the gateway has no value of yet are asked of the object's origin in S (`router.provide.forward`), and the answer goes to every copy.
- When D's connection is lost it reconnects (HLA, `endpoint.hla.reconnect`) and recreates the copies.

No time management and no ownership transfer: the gateway is a time-unaware, update-forwarding federate in each federation.

---

## 2. The configuration folder

Default `etc/junctions` under Rift's runtime folder (setting `junction.dir` in Rift's server settings; `junction.enabled=false` turns Junction off).

```
etc/junctions/
  *.junction.settings              gateways
  endpoints/*.endpoint.settings    endpoint kits
  routers/*.router.settings        router kits
  mappings/rpr2-rpr3.mapping       RPR v2.0 <-> v3.0 conversion rules
etc/junctions.dist/                every sample, as delivered: never read by the server
```

`etc/junctions.dist` holds every sample configuration, untouched; `etc/junctions` is delivered with a selection of them. To use another sample, copy it (and any kit it needs) from `junctions.dist` to the same place under `etc/junctions`. `diff -r etc/junctions.dist etc/junctions` shows what you changed, and which samples are not in use. When upgrading, replace `junctions.dist` with the new version's and keep your `etc/junctions`: the difference between the old and the new `junctions.dist` is what changed in the samples.

The folder is read again whenever the page or the API lists it; edits need no restart. The **Config assistant** page (`config-assistant.html`, Rift's top bar) writes gateway files, kits and groups for you, ready to copy to this folder. The samples delivered in `etc/junctions`. They use the kits as far as possible:

| Gateway | File | Endpoints · router |
|---|---|---|
| HLA3<->HLA3 dynamic-FOM | `hla3-hla3-dynamic-fom.junction.settings` | HLA3-dynamic-FOM (`Blue`, `Red`) · HLA3-all |
| HLA3<->DIS7 | `hla3-dis7.junction.settings` | HLA3-RPR2 + DIS7 · HLA3-all |
| HLA3<->DIS6 | `hla3-dis6.junction.settings` | HLA3-RPR2 + DIS6 · HLA3-all |
| HLA4<->DIS7 | `hla4-dis7.junction.settings` | HLA4-RPR2 + DIS7 · HLA4-all |
| HLA4<->DIS6 | `hla4-dis6.junction.settings` | HLA4-RPR2 + DIS6 · HLA4-all |
| HLA3<->CDIS | `hla3-cdis.junction.settings` | HLA3-RPR2 + CDIS · HLA3-all |
| HLA4<->CDIS | `hla4-cdis.junction.settings` | HLA4-RPR2 + CDIS · HLA4-all |
| DIS7<->CDIS | `dis7-cdis.junction.settings` | DIS7 + CDIS · PDU-relay: PDU by PDU, all 22 C-DIS PDU types |
| ASIM, ASIM-COMMS | `asim.junction.settings`, `asim-comms.junction.settings` | A group (`ASIM-Trainer`): an HLA Evolved ⇄ HLA Evolved bridge and its Pitch Talk comms, with group variables |

Only in `etc/junctions.dist`. Copy one (and any kit it needs) to `etc/junctions` to use it:

| Gateway | File | Endpoints · router |
|---|---|---|
| HLA3<->HLA3 - RPR3<->RPR2 | `hla3-hla3-rpr2-rpr3.junction.settings` | HLA3-RPR2 + HLA3-RPR3 · HLA3-all, converted with `mappings/rpr2-rpr3.mapping` |
| HLA3<->HLA3 - EntityType filter | `hla3-hla3-entitytype-filter.junction.settings` | HLA3-RPR2 (`Blue`, `Red`) · HLA3-EntityType-filter: entity types rewritten by value rules |
| HLA3<->HLA3 - class filter | `hla3-hla3-class-filter.junction.settings` | HLA3-RPR2 (`Blue`, `Red`) · HLA3-class-filter: entities, fire and detonations only |
| Exercise: HLA3<->DIS7, Exercise: Talk<->Talk | `exercise-hla3-dis7.junction.settings`, `exercise-talk-talk.junction.settings` | A group (`Exercise`) with group variables and their descriptions (section 7) |
| Talk<->Talk lobby only | `talk-talk-lobby-only.junction.settings` | Talk (two sites) · Talk-Lobby-Only |
| Talk<->Talk + DIS7 radio | `talk-talk-dis7-radio.junction.settings` | Talk (two sites) + DIS7 · Talk-Full with the radio bridge |
| HLA3<->HLA3 two RTIs | `hla3-hla3-two-rtis.junction.settings` | HLA3-RPR2 + HLA3-RPR2-own-RTI (pRTI 6 from its own jar) · HLA3-all |

The kits, in both folders (the last three in `junctions.dist` only):

| Endpoint kit | | Router kit | |
|---|---|---|---|
| `hla3-rpr2` | HLA Evolved, RPR v2 with Link 16 (`RPR_FOM_v2.0_Link16_1516-2010.xml`) | `hla3-all` | Every class both sides have |
| `hla4-rpr2` | HLA 4, the same RPR v2 with Link 16 in the HLA 4 format (`RPR_FOM_v2.0_Link16_1516-2025.xml`) | `hla4-all` | The same as `hla3-all` |
| `hla3-rpr3` | HLA Evolved, RPR v3 (folder `rpr3`) | `hla3-class-filter` | Entities, fire and detonations only |
| `hla3-dynamic-fom`, `hla4-dynamic-fom` | The FOM taken from the federation | `hla3-entitytype-filter` | Everything, entity types rewritten by value rules |
| `dis6`, `dis7` | DIS 6 / DIS 7 on UDP port 3000 | `talk-messages` | Pitch Talk messages only, channels by name |
| `cdis` | C-DIS on UDP port 3100, full updates | `pdu-relay` | DIS PDUs as they are, between DIS 6, DIS 7 and C-DIS |
| `talk` | A Pitch Talk federation | `talk-lobby-only` | Pitch Talk messages in the lobby only |
| `hla3-rpr2-own-rti` | HLA Evolved on its own RTI jar (pRTI 6) | `talk-full` | Pitch Talk full mode: clients mirrored |
| `hla4-fedpro-rpr2` | HLA 4 over the Federate Protocol | | |

**`RPR_FOM_v2.0_Link16_1516-2025.xml`** (in `etc/fom/ieee1516-2025`) is the 1516-2010 RPR FOM v2.0 with Link 16, converted to the 1516-2025 format: the namespace, `copyright` in the identification, `valueRequired` on every attribute, `noteReferences` for `notes`, and without the `conveyProducingFederate` switch and the empty `dimensions`, `transportations` and `updateRates` sections. Compared with the HLA 4 RPR v2 modules, every attribute, parameter, datatype, class and note they have in common is the same; the Link 16 classes and datatypes come on top.

A gateway file in `etc/junctions` whose name ends in something else than `.junction.settings` (e.g. `.junction.settings.off`) is not loaded: a way to keep one there without using it.

---

## 3. Gateway files

```properties
junction.name=Blue - Red
junction.description=Shown on the page
junction.endpoints=blue;red
# an endpoint from a kit ...
junction.endpoint.blue=endpoints/hla3-rpr2.endpoint.settings
# ... with overrides: junction.endpoint.<id>.<key> becomes endpoint.<key>
junction.endpoint.blue.hla.federation=Blue-2
# or completely inline
junction.endpoint.red.standard=ieee1516-2025
junction.endpoint.red.hla.foms=RPR-Base_v2.0.xml;RPR-Foundation_v2.0.xml
# the router: a kit and/or inline keys (junction.router.<key> -> router.<key>, junction.talk.<key> -> talk.<key>)
junction.router=routers/hla3-all.router.settings
junction.router.objects.block=EmbeddedSystem.*
junction.autostart=false
# groups (section 7)
junction.group=Exercise
junction.groupvar.GROUPVAR2=1
```

Endpoint ids may contain letters, digits, `-` and `_`. A gateway needs two endpoints. Two endpoints of one gateway on the same federation (or DIS network and exercise) are refused, because traffic would loop. Another *running* gateway on the same target only produces a warning.

---

## 4. Endpoint settings

| Key | Default | Meaning |
|---|---|---|
| `endpoint.name` | the id | Shown on the page |
| `endpoint.standard` | — | `ieee1516-2010`, `ieee1516-2025`, `ieee1516-2025-fedpro`, `dis6`, `dis7`, `cdis`, `talk` |
| **HLA** | | |
| `endpoint.hla.foms` | DIS: RPR v2; Talk: pitchtalk.xml + pitchtalkeffects.xml | `;`-separated files or folders; relative names are looked up in `endpoint.hla.fomrootdir`. `dynamic`: the FOM is taken from the federation (see below) |
| `endpoint.hla.fddtimeoutseconds` | `15` | `dynamic`: how long to wait for the federation's FDD after the join |
| `endpoint.hla.fddclass` / `fddattribute` | `HLAobjectRoot.HLAmanager.HLAfederation` / `HLAcurrentFDD` | `dynamic`: where the FDD is read, for an RTI that names them differently |
| `endpoint.hla.fomrootdir` | `etc/fom/<standard>` | |
| `endpoint.hla.federation` | — | Default target |
| `endpoint.hla.createfederation` | `true` | Create it when missing |
| `endpoint.hla.destroyfederation` | `false` | Try to destroy it when the gateway stops |
| `endpoint.hla.federatename` / `federatetype` | RTI's choice / `SimwJunction` | |
| `endpoint.hla.lsd` | `""` | 1516-2010: the local settings designator passed to `connect`. 1516-2025: the configuration name of the `RtiConfiguration`. Used with any RTI library, the class path or `rtijar`. A `;`-separated list offers choices at the start, the first is the default (see *LSD choices* below) |
| `endpoint.hla.rtijar` | class path | RTI library of this endpoint (section 10) |
| `endpoint.hla.rtifactory` | default | RtiFactory name, when the library has several |
| `endpoint.hla.rtiaddress` / `rtisettings` | | Connect settings for an RTI library (HLA 4: `RtiConfiguration`; FedPro server address). `\n` in `rtisettings` is a new line |
| `endpoint.hla.reconnect` / `reconnectintervalseconds` | `true` / `5` | |
| `endpoint.hla.referencetypes` | `RTIobjectId` | Datatypes that hold object instance names |
| `endpoint.talk.hlastandard` | `ieee1516-2010` | Talk endpoints: which HLA API |
| **DIS** | | see section 9 |
| **C-DIS** | | `endpoint.cdis.*`, see *C-DIS endpoints* in section 9 |
| **Filters** | | |
| `endpoint.filter.in.*` / `endpoint.filter.out.*` | all | Per-endpoint class filters (same keys as the router's `objects.allow` …): what this endpoint contributes / receives |

### LSD choices

```properties
endpoint.hla.lsd=default;crc2;crc@10.0.0.5:8989
```

- **The first entry is the default.** A gateway started from an autostart, from the API without `lsds`, or with the dialog left as it is, connects with it.
- **The others are offered at the start.** The **Start** dialog shows the LSD of every HLA endpoint (*RTI configuration* for HLA 4), prefilled with the default, with the other choices as buttons. **Start group** has the same for every HLA endpoint of the group, folded away. Any other value can be typed too; it does not have to be in the list. Empty is the RTI's own default (no designator).
- **Only a changed value is sent.** An LSD left at its default comes from the configuration, so a group variable in the setting (`endpoint.hla.lsd=GROUPVAR3`) still takes the start's value. A typed LSD may contain group variables too.
- **Discover** asks the RTI with the LSD in the dialog, so you see the federations of the RTI you are about to join.
- **Where it shows.** The configuration cards show the default (`LSD default +2`, the choices in the tooltip). A running endpoint shows the LSD it connected with, marked ✎ when it was chosen at the start. **Inspect** opens the Rift datasource with that same LSD.
- **The setting's form.** Entries are trimmed, and an entry listed twice is offered once. An empty entry (`default;;crc2`, or a leading `;`) offers the RTI's default. An LSD that itself contains `;` cannot be listed, but can be typed at the start.

Rift's datasource configurations take the same form: `datasource.hla.lsd=default;crc2`.

### FOM from the federation (`dynamic`)

```properties
# endpoints/hla3-dynamic-fom.endpoint.settings
endpoint.standard=ieee1516-2010
endpoint.hla.foms=dynamic
endpoint.hla.lsd=default
```

An HLA endpoint (`ieee1516-2010`, `ieee1516-2025`, `ieee1516-2025-fedpro`) with `endpoint.hla.foms=dynamic` brings no FOM of its own. It joins without FOM modules and reads the federation's current FDD from the MOM: attribute `HLAcurrentFDD` of the `HLAobjectRoot.HLAmanager.HLAfederation` object, the merged FOM as text. The endpoint's model is built from that text, so the gateway bridges whatever FOM the federation runs: RPR, NETN, proprietary extensions or a custom FOM, without copies of the modules.

- **The FDD's format** is read from the document itself, not from the endpoint's API. An HLA 4 RTI that also serves 1516-2010 federates (pRTI 6) reports the FDD in the 1516-2025 format to HLA Evolved federates too. The names and encodings are the same, so the HLA Evolved endpoint works with it, and its FOM source says *written in the IEEE 1516-2025 format*.

- **The federation must exist.** The endpoint never creates it (`createfederation` is ignored): it has no FOM to create it with. When it is missing the start fails with a message saying so. Start the gateway after the federation's first federate.
- **The FDD is read once, at the start.** When a federate later joins with more modules, the federation's FDD grows. The endpoint notices it and the page shows *The federation's FDD has changed …* on the endpoint, but it keeps routing with the model it started with. Restart the gateway to take in the new classes.
- **RPR v2 or v3** is recognised from the classes in the FDD (as it is for FOM files whose names do not tell), so RPR conversion (section 8) works for dynamic endpoints too. The page and the API show where the FOM came from: *from the federation: FDD 7a6e65c9531f, RPR v2 recognised from its classes*.
- On a reconnect (`endpoint.hla.reconnect`) the FDD is watched again; the model stays the one from the start.
- **Compatibility** (section 11) cannot compile routes for a dynamic endpoint before it has joined: it says so instead. Start the gateway and use **Inspect** to see the federation's classes. Inspect of a dynamic endpoint opens a Rift datasource that takes the FOM from the federation in the same way.
- DIS and Talk endpoints cannot be `dynamic`.

Samples: the kits `endpoints/hla3-dynamic-fom.endpoint.settings` (federation `Blue`) and `endpoints/hla4-dynamic-fom.endpoint.settings` (federation `Green`), and the gateway `hla3-hla3-dynamic-fom.junction.settings`.

---

## 5. Router settings (IEM)

| Key | Default | Meaning |
|---|---|---|
| `router.kind` | `iem` | `iem` or `talk` |
| `router.objects.allow` / `.block` | all | Class patterns, `;`-separated |
| `router.interactions.allow` / `.block` | all | |
| `router.promote` | `true` | A class the destination lacks goes as its nearest superclass there |
| `router.names.policy` | `same-or-suffix` | `same-or-suffix` (name, then `name@origin`, `#2` …), `prefix:<text>`, `same-only`, `rti` (the RTI names it) |
| `router.names.template[.<endpoint>]` | | Names of the copies from a template, e.g. `RED-{name}-{hexguid}`; with `.<endpoint>` only for copies in that endpoint (below) |
| `router.names.maxlength[.<endpoint>]` | `0` (no limit) | Longest name of a copy, in characters (below) |
| `router.queue.capacity` | `50000` | Events waiting for the router; updates are dropped (and counted) when full, the rest waits |
| `router.rpr.mapping` | `mappings/rpr2-rpr3.mapping` | RPR conversion table |
| `router.deleteobjectsonstop` | `true` | Delete the copies when the gateway stops |
| `router.provide.forward` | `true` | A provide request for attributes the gateway has no value of yet is passed on to the object's origin as an attribute update request (below) |
| `router.provide.forwardintervalms` | `1000` | The same attribute of an object is asked of the origin at most this often (0 – 3600000 ms) |
| `router.group` | | Group of every gateway using this kit (section 7) |
| `router.valuemap.<datatype>[.<label>]` | | Value rules: `A -> B` replaces every value A of the datatype by B (below) |

**Class patterns**, written without the root (`HLAobjectRoot.` / `HLAinteractionRoot.`):

| Pattern | Matches |
|---|---|
| `BaseEntity.PhysicalEntity.Platform` | that class only |
| `BaseEntity.PhysicalEntity.Platform.*` | the class and its subclasses |
| `*`, `**` | everything |
| `BaseEntity.*.Aircraft`, `**.Aircraft`, `Platform.*Vehicle` | segment wildcards; `**` spans levels |

The most specific matching rule wins; on a tie, block wins. The roots and the MOM (`HLAmanager`) are never bridged. Attributes and parameters are not filtered: a class passes with all the members both sides have.

### Value rules

A value rule replaces one value of a datatype by another as it passes through the gateway. The typical use is an entity type that one side knows under another number:

```properties
# in a router kit, or inline in the gateway file as junction.router.valuemap...
router.valuemap.EntityType.f16  = 1.2.225.1.3.0.0 -> 1.2.222.1.3.0.0
router.valuemap.EntityType.tank = 1.1.225.1.1.3.0 -> 1.1.225.1.1.4.0; 1.1.225.1.1.5.0 -> 1.1.225.1.1.4.0
```

- **The key** names the datatype: `EntityType` is short for `EntityTypeStruct` (RPR v2 and v3). Other datatypes are written by their FOM name, e.g. `router.valuemap.ForceIdentifierEnum8.1 = 1 -> 2`. The last part (`f16`, `tank`) is a free label, so a kit and a gateway file can each add rules; the same key in the gateway file replaces the kit's.
- **The values** are written in dot notation, one number per field of the datatype in FOM order: for an entity type *kind.domain.country.category.subcategory.specific.extra*, all seven. A single number is used for an integer or enumerated datatype. One key can hold several pairs, separated by `;`. `>` works as well as `->`.
- **Exact match**, no wildcards: only a value equal to A in every field is replaced.
- **Every occurrence:** the datatype is replaced wherever it appears in a routed attribute or parameter. That covers an entity's `EntityType` and `AlternateEntityType`, the `MunitionType` of fire and detonation interactions, and types inside records, arrays and variants. Examples are the entity types of attached parts in `ArticulatedParametersArray`, and the silent entities of an aggregate.
- **All directions:** a rule applies to what every endpoint sends, whichever endpoint it goes to.
- **One way per value:** A becomes B, and B is left alone unless another rule says otherwise. Each value is looked at once, so `A -> B` together with `B -> A` swaps the two, and `A -> B` together with `B -> C` turns A into B, not C.
- **DIS endpoints** take part like any other endpoint: a rule changes the entity type in Entity State PDUs and the munition type in Fire and Detonation PDUs, in both directions.
- **RPR v2 ⇄ v3:** the value is replaced in the sender's model before it is converted, so one rule covers both FOM versions.
- **Late joiners and provide requests** get the replaced values too. The gateway keeps the values as they were received and replaces them on the way out.
- **Mistakes stop the gateway from starting**, with the reason on the page: a value that is not in dot notation, A and B with different field counts, the same A with two different B, a value with the wrong number of fields for the datatype, a number too large for its field, or a datatype that is not an integer, enumeration or record of them. A datatype that an endpoint's FOM does not have is skipped for that endpoint.
- **On the page:** the compatibility view lists the members the rules apply to (*VALUE RULES*), and a running gateway counts the values it replaced (*VALUES REPLACED*). The server log lists the rules for each endpoint when the gateway starts.
- **Cost:** members that can hold such a value are decoded to compare them. Members without one still pass as octets.

### Naming copies

By default a copy has the name of its original (`router.names.policy`). A template gives the copies other names instead, for every destination or for one only:

```properties
# copies in endpoint ext only: RED-<name>-<GUID>, at most 32 characters
router.names.template.ext=RED-{name}-{hexguid}
router.names.maxlength.ext=32
# or every destination
router.names.template=GW-{name}
```

- **Tokens:**

  | Token | Becomes |
  |---|---|
  | `{name}` | The object's instance name where it comes from |
  | `{hexguid}` | A GUID of the object as 32 lower-case hex digits |
  | `{HEXGUID}` | The same in upper case |
  | `{guid}` | The same GUID as `8-4-4-4-12` |
  | `{origin}` | The id of the endpoint the object comes from |

  Everything else in the template is kept as it is. An unknown token, or a `{` without its `}`, is an error and the gateway does not start. A template with neither `{name}` nor a GUID token gives every copy the same name, so it only warns.
- **One direction.** `router.names.template.<endpoint>` applies to the copies registered *in* that endpoint, and wins over `router.names.template`. A destination without one keeps the name policy. An empty value (`router.names.template.int=`) cancels the general template for that endpoint. In a gateway file the keys are written `junction.router.names.template.<endpoint>`.
- **The GUID** is derived from the gateway (its id and instance), the origin endpoint and the object's name. So a copy gets the same name again after a reconnect, or after the gateway is stopped and started, and references held on the other side stay valid. Two instances of one gateway give different GUIDs.
- **Maximum length.** `router.names.maxlength` cuts the final name to that many characters. When a name is taken, `#2`, `#3` and `#4` are tried, and they fit inside the limit: the name is cut shorter to make room. The limit also applies to the names of the name policy (`name@origin#2` keeps its `@origin#2`). `0` means no limit, and `.<endpoint>` sets it for one destination.
- **Names starting with `HLA`** are reserved for the RTI. Such a result gets the origin endpoint's id in front (`ext.HLA…`).
- **References follow automatically, both ways**, with no extra settings. Every `RTIobjectId` in a routed attribute or parameter is rewritten to the name the object has at the receiver: simple values, fields of records, elements of arrays and variant records, at any depth.
  - A Detonation in `ext` aimed at `RED-Tank-12-…` reaches `int` as `Tank-12`.
  - Fire from `Tank-12` in `int` reaches `ext` with `RED-Tank-12-…` as the shooter.
  - A name the gateway has not seen (an object that is not bridged) passes unchanged.
- **Not used for DIS endpoints**, which name their objects by entity id. The gateway warns when a template is keyed to one, or to an endpoint the gateway does not have.
- `router.names.policy=rti` lets the RTI choose names; a template applies instead where it is set.

### Provide requests

A federate that asks for attribute values of a copy (`requestAttributeValueUpdate`) gets them from the gateway, which keeps the last value of every attribute of every bridged object:

- **Cached attributes** are answered at once, by the gateway, without asking the origin.
- **Attributes the gateway has no value of yet** (the origin has not sent them since the gateway discovered the object) are asked of the origin, as an attribute update request in the origin's federation (0.9.19, `router.provide.forward=true`). Only those attributes are asked for. The origin's answer is an ordinary update, so it reaches the requester and every other copy.
- **The same attribute of an object is asked for at most every `router.provide.forwardintervalms`** (1000 ms), so many federates asking at once, or a federate asking again and again, give the origin one request.
- **DIS origins are not asked**: DIS has no such request; the entity's heartbeat brings the values.
- On discovery the gateway already asks the origin for every attribute it subscribes to, so a request that has to be forwarded is mostly one for an attribute the origin does not send on its own, or one asked before the origin answered.
- The gateway card and `routerstats` count the requests passed on (`providesforwarded`, shown as **ASKED ORIGIN**) next to those answered (`providesanswered`, **PROVIDED**).

---

## 6. Pitch Talk router

`router.kind=talk` bridges Pitch Talk federations. The `PitchTalk.Client`, `User` and `Equipment` objects are mirrored (UUIDs unchanged, `talk.mirrorclients`), and the `PitchTalk.Message` interactions (audio, radio, text, image) are routed. Channels are **not** mirrored: each federation keeps its own, and `ChannelUUID` in every message is rewritten to the receiving federation's channel of the same name.

| Key | Default | Meaning |
|---|---|---|
| `talk.mode` | `full` | `full`: channel tables, client mirroring (below); `messages`: messages only, channels watched (see *Messages-only mode*) |
| `talk.messages` | full: `audio;radio;text;image`, messages: `audio;radio;text` | The `PitchTalk.Message` kinds bridged |
| `talk.match` | `name` | Match channels by `name` or `description` (case is ignored) |
| `talk.channels` | `*` | Channels bridged (names; `*`, `?`). `none`: the lobby only (see *The lobby only* below) |
| `talk.channelmap` | | `A>B; C>D`: channel A on one side is channel B on the other (both ways) |
| `talk.channelmap.<endpoint>` | | `A>B`: when sending into that endpoint, channel A is its channel B (and B is A on the way out) |
| `talk.createmissingchannels` | `false` | Create any channel the receiver lacks, on its first message (else its messages are dropped and counted) |
| `talk.createmappedchannels` | `true` | Register a channel that `talk.channelmap` maps to a name the receiver lacks, under that name (below) |
| `talk.createwaitms` | `3000` | How long the gateway watches a Talk federation for its own channels, after joining, before it creates one there |
| `talk.mirrorclients` | `true` | |
| **Radio bridge** | | |
| `talk.radio.endpoint` | | An RPR endpoint of the gateway (HLA RPR v2/v3 or DIS) |
| `talk.radio.map.<channel>` | | Frequency in Hz of a Talk channel: its common name (before `channelmap.<endpoint>`; a space is written `\ `) or its UUID |
| `talk.radio.entityid` | `900-99-1` | DIS entity hosting the gateway's radios (one per Talk sender) |
| `talk.radio.hangtimems` | `500` | Transmitter stays "transmitting" this long after the last frame |
| `talk.audio.samplerate` | `16000` | Sample rate of Talk PCM16 |
| `talk.audio.pcmbyteorder` | `little` | Byte order of Talk PCM16 |
| `talk.audio.rprencoding` | `pcm16be` | Talk audio sent as RPR: `pcm16be` (DIS 4), `pcm16le` (100), `mulaw` (1) |
| `talk.audio.rprsamplerate` | Talk's | Resampled to this rate when sent as RPR |

**Mapped channels the receiver lacks.** When `talk.channelmap` maps a channel to a name the receiving federation does not have, the gateway registers a channel with that name there (`talk.createmappedchannels=true`, the default):

```
talk.channelmap.c2=Alpha>Bravo; Chat1>Chat2    # c2 has neither Bravo nor Chat2: both are registered in c2
```

- The new channel has the class of the source channel (voice, radio or chat), its frequency, and an instance name `Junction-<gateway>-<name>`.
- It is registered as soon as the source channel is known and the receiver has been watched for `talk.createwaitms` (3 s). The wait lets the federation's own channels be discovered first, so no duplicate is made. Messages before that are dropped (radio channels pass, they tune by frequency).
- A mapping works both ways, so a channel the *other* side lacks is created the same way.
- Only mapped channels are created, and only those `talk.channels` lets through. Unmapped channels with no counterpart are still dropped, unless `talk.createmissingchannels` is on.
- If the federation later gets a channel of that name of its own, the gateway deletes the one it registered, and messages go to the federation's own channel.
- The gateway's channels are deleted when it stops (`router.deleteobjectsonstop`), and registered again after a reconnect.
- The page counts them (*channels created*), and the log names each one.

Rules for messages:

- The lobby (all-zero `ChannelUUID`) stays the lobby.
- A radio channel without a counterpart keeps its UUID, so clients can tune by frequency.
- Messages in channels the gateway has not seen yet are dropped (counted as "unknown channel").

**Radio bridge.** Every Talk sender on a mapped channel becomes a `RadioTransmitter` on the radio endpoint (host `talk.radio.entityid`, a radio number each, `TransmitterOperationalStatus` 2 while it talks, then 1). Its audio becomes `EncodedAudioRadioSignal`s. RPR audio on a mapped frequency is sent into the channel from a `Client` object the gateway registers in each Talk federation (`Message.Audio`, or `Message.Audio.Radio` with the frequency for radio channels). PCM16 (either byte order) and G.711 mu-law are converted, including the sample rate (linear interpolation). Opus is passed through when the RPR FOM has an Opus encoding (RPR v3: 11); otherwise those frames are dropped and counted.

The page shows each Talk federation's channels: which are matched (and under which name), which have no counterpart, and their radio frequency.

**The lobby only** (`talk.channels=none`, kit `routers/talk-lobby-only.router.settings`). Only messages in the lobby (the all-zero `ChannelUUID`) pass. A message in any other channel is dropped and counted as *filtered*, whether the gateway has seen that channel or not, in full and in messages mode. No channel is bridged or created, and `talk.channelmap`, `talk.channeluuidmap` and `talk.radio.map` are not used (the gateway warns when they are set). With messages mode only the `PitchTalk.Message` interactions pass:

```properties
router.kind=talk
talk.mode=messages
talk.messages=audio;text;image
talk.channels=none
```

**Messages-only mode** (`talk.mode=messages`, kit `routers/talk-messages.router.settings`). The gateway exchanges nothing but the `PitchTalk.Message` interactions of `talk.messages` (by default audio, radio and text). It mirrors no Talk object, and leaves MasterConfiguration, clients, users, equipment and permissions alone. The only objects it registers are the channels `talk.channelmap` maps to a name a federation lacks (`talk.createmappedchannels`, as in full mode). Set `talk.createmappedchannels=false` to register nothing at all.

The usual need is still "channel X there is channel Y here". Messages carry only a channel UUID, so the gateway has to know what each UUID means on each side. With `talk.trackchannels=true` (the default) it watches the channel objects of each Talk federation (UUID, name, description, frequency only). Nothing is mirrored, and nothing is created except mapped channels a federation lacks. That gives it the same channel table as in full mode, so `talk.channelmap` works by name:

```
talk.mode=messages
talk.channelmap.home=Net Alpha>Bravo Net     # Net Alpha out there is Bravo Net at home (and back)
```

| Key | Default | Meaning |
|---|---|---|
| `talk.trackchannels` | `true` | Watch the channel objects and match channels by name. `false`: no channel objects at all, UUIDs only |
| `talk.channeluuidmap` | | `<uuid>><uuid>; …`: a channel UUID on one side is this UUID on the other, both ways. UUIDs are 32 hex digits or `8-4-4-4-12`. |
| `talk.channeluuidmap.<endpoint>` | | The same, only into (and back out of) that endpoint |

How `ChannelUUID` is handled in this mode, in order:

1. The lobby stays the lobby.
2. A `talk.channeluuidmap` pair, if one matches.
3. With `talk.trackchannels`: the channel's name (or description, `talk.match`), after `talk.channelmap`, is looked up among the receiver's channels, as in full mode. `talk.channels` filters.
   - No counterpart: the message is dropped and counted ("no channel"). Radio channels keep their UUID; clients tune those by frequency.
   - The gateway cannot create the missing channel: `talk.createmissingchannels` does not apply.
4. A channel the gateway has not seen (yet), or any channel with `talk.trackchannels=false`: the UUID passes unchanged. With tracking these are counted ("unseen channel").

`talk.mirrorclients` does not apply.

The radio bridge works in this mode too:

- With `talk.trackchannels`, key `talk.radio.map` by channel name as in full mode. A key can always be a channel UUID instead (`talk.radio.map.0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0=251000000`); without tracking, it must be. Talk radio messages also match by their `Frequency`.
- RPR audio on a mapped frequency is sent into the channel (`Message.Audio.Radio` with the frequency for radio channels and for UUID keys of channels not seen, otherwise `Message.Audio`).
  - For a UUID key, the UUID in endpoint E is first mapped by `talk.channeluuidmap.E`.
  - The global pairs are not used in this direction, so prefer the per-endpoint form when you combine the two.
- The gateway sends with a sender UUID of its own, without a Client object behind it. Check that your Talk version plays audio from an unknown sender.

The page shows "MESSAGES · BY NAME" (with the channel tables) or "MESSAGES · BY UUID".

---

## 7. Groups and group variables

Gateways with the same `junction.group=<name>` (or a router kit with `router.group=<name>`) form a group. The page shows a group as one block with **Start group** / **Stop group**, and the API has `POST /api/junction/groups/{group}/start` and `/stop`.

**Variables.** The tokens `GROUPVAR1`, `GROUPVAR2`, … can stand in *any* setting value of a gateway file, its endpoint kits and its router kit:

```properties
# exercise-hla3-dis7.junction.settings (in etc/junctions.dist)
junction.group=Exercise
junction.endpoint.hla.hla.federation=GROUPVAR1
junction.endpoint.dis.dis.exid=GROUPVAR2
junction.groupvar.GROUPVAR2=1                 # a default

# exercise-talk-talk.junction.settings (in etc/junctions.dist)
junction.group=Exercise
junction.endpoint.exercise.hla.federation=GROUPVAR1-Talk
junction.talk.channelmap.exercise=Net1>GROUPVAR3
junction.groupvar.GROUPVAR3=Battalion
```

With both files in use, starting the group with `{"vars":{"GROUPVAR1":"FederationX"}}` starts both gateways. The first joins `FederationX` and DIS exercise 1. The second joins `FederationX-Talk` and maps the site's `Net1` to the exercise channel `Battalion`.

- A token is replaced wherever it stands, and the text around it stays: `radio_GROUPVAR1` becomes `radio_FederationX`, `GROUPVAR1-Talk` becomes `FederationX-Talk`. There is no other syntax (no `${...}`, no quotes, no `+`).
- Setting names are replaced too, where a name holds a channel: `junction.talk.radio.map.radio_GROUPVAR1=251000000`. The defaults `junction.groupvar.GROUPVARn` keep their names.
- The whole number counts: `GROUPVAR10` is variable 10, not `GROUPVAR1` followed by `0`.
- Values are replaced before the settings are read, so they work for federations, FOM file names, kit file names, channel names, DIS ports and anything else.
- A variable without a value (given at the start, or a `junction.groupvar.*` default) stops the start with `VARIABLES_REQUIRED`. The page asks for exactly the variables the group uses.
- Single gateways can use variables too: their Start dialog asks for them.
- A target typed in the Start dialog may also contain a token.
- Gateways of a group start one after the other. One that fails does not stop the others: the result lists each gateway.
- A running gateway shows its group, its instance and its values.

**Descriptions.** A variable can be given a meaning for the people who start the group:

```properties
junction.groupvar.GROUPVAR1.desc=Exercise federation
junction.groupvar.GROUPVAR2.desc=DIS exercise id
```

- The description is the label wherever a value is asked for or shown: the **Start group** and **Start** dialogs (with `GROUPVAR1` beside it), the group header, the running instances, and the running gateways (`Exercise federation=FederationX`). The tooltips keep the variable names.
- It changes nothing else. The settings still say `GROUPVAR1`, the API still takes `{"vars":{"GROUPVAR1":"…"}}`, and the description is never substituted into anything. A variable named in a description's text (`…desc=Not GROUPVAR4`) is not "used" by it.
- **In a group,** any gateway file of the group may describe any of the group's variables, also one that only another gateway uses. Describe them once, e.g. in the group's main gateway. When two files describe the same variable differently, the first gateway of the group (by file name) wins.
- A gateway outside a group that describes a variable its settings do not use gets a warning, and so does a description key whose name is not `GROUPVARn`. `.desc` may be written in any case.
- The **Config assistant** writes the *Meaning* column of its Group panel as these keys.

**Discover a value.** The **⟳** button next to a variable in the Start group and Start dialogs asks the RTIs for their federations (and DIS networks for their exercises), using the LSDs in the dialog:

- For each endpoint whose federation (or exercise) setting uses the variable, it asks that endpoint's RTI, each RTI once, and takes the variable's value out of every federation name by the setting as written. With `GROUPVAR2-COMMS`, a federation `TheOtherOnes-COMMS` offers **TheOtherOnes**. A value that fits several endpoints comes first.
- The whole federation names are offered too, under *All federations*, so any name can be taken as it is.
- For a variable that is in no federation setting (a channel name, say), it lists the federations of every HLA endpoint's RTI.
- Clicking one fills the field; you can still edit it.

**Instances.** A group can run several times at once, usually with other variable values: one exercise per instance, say. Each start of the group is an **instance** with a name of its own:

- **The name** is given at the start (*Instance* in the Start group dialog, `instance` in the API). Left empty, it is the lowest free number: `1`, then `2`, and so on. A name has at most 40 letters, digits, spaces, `_`, `-` or `.`; `Övning A` is fine.
- **One name runs once.** Starting an instance of the group that is already running is refused (`INSTANCE_RUNNING`), and nothing is started. The dialog says so before you press Start.
- **Stop one instance or all.** Each running instance has a row in the group with its name, its values, how many of its gateways run, and its own **■ Stop**. **■ Stop all** (shown as **■ Stop group** while only one instance runs) stops every instance.
- **An incomplete instance** (one of its gateways failed or was removed) is marked in red, `1/2 RUNNING`. To complete it, start the missing gateway on its own with the same instance name and values: a single start refuses only a running instance of *that* configuration.
- **What changes per instance:** the objects the gateway registers itself are named after the instance, except for instance `1`. Pitch Talk channels, clients and radios become `Junction-<gateway>-<instance>-…`, so two instances in one Talk federation do not collide. Log lines and thread names include the instance too.
- **What does not change:** the files. Two instances with the *same* values bridge the same targets, and traffic is duplicated or loops; the start only warns about that.

**Single gateways** have instances the same way: starting a gateway that already runs makes instance `2`, and its Start dialog has the *Instance* field too. The running gateways show *INSTANCE n* whenever a gateway is in a group, has another name than `1`, or runs more than once.

---

## 8. RPR FOM v2.0 ⇄ v3.0

Endpoints are recognised as RPR v2 or v3 by their FOM. DIS endpoints are RPR v2. Between a v2 and a v3 endpoint the router converts with `mappings/rpr2-rpr3.mapping`. The file has v2 names on the left and v3 names on the right, and one file serves both directions:

```properties
class.HLAinteractionRoot.WeaponFire = HLAinteractionRoot.Fire.WeaponFire
classdown.HLAinteractionRoot.Detonation.Explosion = HLAinteractionRoot.MunitionDetonation   # v3 -> v2 only
classup.HLAobjectRoot.EmbeddedSystem.IFF.NatoIFF = HLAobjectRoot.EmbeddedSystem.IFFSystem   # v2 -> v3 only
attribute.HLAobjectRoot.BaseEntity.PhysicalEntity.DamageState = Damage
attribute.HLAobjectRoot.BaseEntity.PhysicalEntity.Lifeform.DamageState = Health             # subclass rule wins
parameter.HLAinteractionRoot.MunitionDetonation.ArticulatedPartData = VariableParametersArray
field.SpatialFPStruct.WorldLocation = SpatialFPStruct.Location
default.SpatialFPStruct.OtherParameters =                     # v3 field without a v2 source
enum.CamouflageEnum32.1 = AppearanceCamouflageTypeEnum8.0
```

Without a rule, names stay the same. Enumerators are matched by name, then by value (v2 and v3 mostly differ in underscores, with equal values). Record fields named `Padding*` are zero-filled without a rule.

Some differences are structural and are converted in code (`RprStructuralAdapters`):

- **Articulated parameters.** v2 records ⇄ v3 variable parameter variants (articulated and attached parts; the other v3 record kinds have no place in v2).
- **Radio type.** The DIS 6 layout (nomenclature version and nomenclature) ⇄ the DIS 7 layout (subcategory, specific, extra), octet for octet.
- **Silent entities.** The v2 appearance record count is set from the v3 array.
- **Jamming technique.** v2 `JammingModeSequence` (32 bits) ⇄ v3 `JammingTechniqueStruct` (kind, category, subcategory, specific).

**IFF modes** are converted in code by the router (`RprIffBridge`). They differ in structure: v2 keeps the modes as attributes of the IFF system (`Mode3AIsOn`, `Mode3ACode`, …), v3 as `IFFData` objects that name their system (`IFFSystemIdentifier`). The systems themselves are bridged by the table (`NatoIFFTransponder` ⇄ `IFFSystem.Transponder`, `NatoIFFInterrogator` ⇄ `IFFSystem.Interrogator`, `RRB` → `IFFSystem`).

- **v2 → v3.** For every v2 NatoIFF or RRB system copied into a v3 federation, the gateway registers one IFFData object per *enabled* mode next to the copy. The objects are `Mode1`, `Mode2`, `Mode3A`, `Mode4`, `ModeC` (v2 parameter 5, "Mode5C") and `ModeS`, of the transponder or interrogator subclass, plus `RRB`. They are named `<copy>/mode3a` and so on, follow every change (on, damaged, malfunctioning, codes, Mode 4 pseudo crypto and alternate Mode 4, Mode C altitude), and go when the mode is disabled or the system is removed.
- **v3 → v2.** The IFFData objects of a v3 system are folded into the v2 copy of that system. A mode is *enabled* when its IFFData object exists, and its attributes come from that object. The values are kept, so a late joiner and a reconnected endpoint get them too.
- DIS endpoints are RPR v2, so this also covers DIS IFF PDUs ⇄ an RPR v3 federation.
- Not converted: v3 Mode 5, `TCASData` and the Mode S details (v2 has no attributes for them); the v2 Soviet IFF parameters (v3 `Soviet` has none); the v2 STI and TCAS I/II flags. The lint tool lists the v2 mode attributes as dropped, because the table does not carry them; `RprIffBridge` does.

The delivered table covers:

- the class moves (`Fire` / `Detonation` bases, IFF);
- the entity appearance attributes that moved to the v3 subclasses (lights, hatches, damage / health, posture, weapons, capabilities, trailing effects);
- Spatial (`Location`, `OtherParameters`);
- the environment objects and transactions;
- radio power;
- the counted arrays that v2 names after their count.

**Not converted** (dropped, as the compatibility report lists):

- v3-only attributes: `LVCIndicator`, `ExtendedDamageStatus`, `PaintScheme`, the aircraft details, IED and sling-load capabilities.
- `IFFEmission` interactions; the v2 IFF `SecondaryOperationalDataParameter1/2` (v3 has no place for them).
- `DirectedEnergyFire`, `IOinteraction`.
- Minefield `RequestingIdentifier` (an RTIobjectId in v2, a federate id in v3).
- Gridded data axis details, attribute-change values (handle vs. name).

Check a table with the lint tool. It lists every class, member, record field, enumerator and variant alternative without a counterpart:

```
java -cp out/classes:out/test com.simweaver.junction.router.RprMappingLint <fom root> etc/junctions/mappings
```

The page's **Compatibility** button shows the same per gateway, from the FOMs (no connection needed).

---

## 9. DIS endpoints

A DIS endpoint is a DIS 6, DIS 7 or C-DIS network seen as an RPR v2 federation.

| Key | Default | Meaning |
|---|---|---|
| `endpoint.dis.interface` | all | Address or interface name; a multicast group `239.1.2.3` or `239.1.2.3@eth0` |
| `endpoint.dis.port` | `3000` | |
| `endpoint.dis.exid` | `1` | Default exercise; `0` receives every exercise (PDUs are then sent as exercise 1) |
| `endpoint.dis.sendaddress` | group, else broadcast of the interface | Unicast, broadcast or multicast; `host:port` also |
| `endpoint.dis.ttl` | `8` | Multicast TTL |
| `endpoint.dis.siteid` / `appid` | `900` / `1` | The gateway's own DIS ids |
| `endpoint.dis.entityids` | `keep` | `keep`: an HLA entity is sent with its `EntityIdentifier` when that id is free on the network; `assign`: always `siteid-appid-n` |
| `endpoint.dis.heartbeatsecs` | `5` | Entity State / Transmitter / Receiver / Electromagnetic Emission heartbeat |
| `endpoint.dis.entitytimeoutsecs` | `12` | A DIS entity not heard from this long is removed |

**Receiving.** As Rift's DIS datasource:

- Entities are named `site-app-entity`, radios `<host>/radio-<n>` and `<host>/receiver-<n>`.
- Entities, radios and IFF systems that are already sending when the gateway starts are bridged as soon as the router is ready (0.9.20 fix: those heard while the other endpoints were still joining stayed out until they went quiet).
- **Link 16** (SISO-STD-002-2021). A raw binary Signal PDU with TDL type 100 (or 113) becomes the `Link16RadioSignal` subclass of its Message Type Identifier: `JTIDSMessageRadioSignal`, `RTTABRadioSignal`, `RTTReplyRadioSignal`, the three JTIDS voice classes, `JTIDSLETRadioSignal` or `VMFRadioSignal`.
  - The 20-octet simulation network header gives `NPGNumber`, `NetNumber`, `TSEC_CVLL`, `MSEC_CVLL`, `TimeSlotID` and `PerceivedTransmitTime`, and `SISOSTD002Version` / `Link16Version` where the FOM has them.
  - The header word and the J-words (or voice data) are copied octet for octet. Both the 2006 (byte swapped) and the 2021 (bit stream) form therefore pass unchanged.
  - The way back builds the same PDU: encoding scheme raw binary with the number of words, TDL type 100 (113 kept), sample rate from `DataRate`. Parameters the interaction lacks are sent as *no statement*: CVLLs 255, time slot and transmit time all ones. `SISOSTD002Version` is 0 when the FOM has none, as in the 2006 BOM.
  - Classes are found by their simple name, so FOM versions that place or spell them differently work. A DIS endpoint whose FOM lacks the Link-16 module gets `RawBinaryRadioSignal`, as before.
- **Designators.** A Designator PDU gives an `EmbeddedSystem.Designator` object `<host>/designator`, with the designated entity, code name, code, power, wavelength, spot location (world and relative), dead reckoning algorithm and spot acceleration.
- **IFF.** An IFF PDU (DIS 6: IFF/ATC/NAVAIDS) gives an IFF object `<host>/iff-<system type>` (DIS 7, when the system designator is not 0: `<host>/iff-<type>-<designator>`). Its class follows the system type:
  - Mark X/XII, Mark XIIA, Mode 5 and Mode S transponders are `NatoIFFTransponder`, and the matching interrogators `NatoIFFInterrogator`;
  - Soviet transponder / interrogator, RRB transponder;
  - combined interrogator/transponders and TCAS are `NatoIFF`; others `IFF`.
  - Layer 1 gives system type, name and mode, on and operational, and per parameter (Mode 1, 2, 3/A, 4, C, S) capable (`…Enabled`), on, damaged, malfunctioning. Transponders also get the Mode 1/2/3/A codes, the Mode C altitude (hundreds of feet, signed; *not available* when all twelve bits are set), emergency, ident/squawk flash and STI.
  - Mode 4 gives its pseudo crypto value and the alternate Mode 4.
  - Layer 2 gives the beam data, the secondary operational data and the fundamental parameter data. DIS 7 layers 3 and up (Mode 5, Mode S details) have no RPR v2 attributes and are skipped.
  - IFF PDUs have a 10 s heartbeat, so IFF objects are kept for at least 24 s without one.
- **Emitters.** An Electromagnetic Emission PDU gives an `EmitterSystem` object per emitter
  (`<host>/emitter-<n>`, hosted by the entity) and a `RadarBeam` per beam, or a `JammerBeam`
  for beam function *Jamming* (`<host>/emitter-<n>/beam-<m>`, with `EmitterSystemIdentifier`).
  Track and jam targets become `TrackObjectIdentifiers` / `JammedObjectIdentifiers`.
  - A beam that a later PDU no longer lists for its system is removed (switched off).
  - A heartbeat PDU (state update indicator 0) also removes the systems it leaves out.
  - Otherwise emitters time out like entities. A DIS 7 beam marked deactivated is left out.
- Only changed attributes are passed on.
- As with an RTI, only subscribed classes are discovered: each object and interaction is handed over as the nearest class the router asked for.
- DIS 7 accepts DIS 6 and 7, DIS 6 accepts 5 and 6.

**Sending.** HLA objects become:

- Entity State PDUs, on every update and as a heartbeat, deactivated on delete;
- Transmitter and Receiver PDUs;
- **Designator PDUs** from `Designator` objects (host: `HostObjectIdentifier`), on every update and as a heartbeat. A deleted designator is sent once with power 0.
- **IFF PDUs** from IFF objects (`NatoIFF…`, `SovietIFF…`, `RRB`; host: `HostObjectIdentifier`): layer 1, and layer 2 when `Layer2DataAvailable`. They go out on every update and as a heartbeat, and once as *off* when the object is deleted. The system type is `SystemType`, else the class's; a second system of the same type on one entity gets DIS 7 system designator 1, 2, ….
- **Electromagnetic Emission PDUs** from `EmitterSystem` objects and their `EmitterBeam`
  objects (`RadarBeam`, `JammerBeam`, subclasses). The beams are found through
  `EmitterSystemIdentifier`, the systems through `HostObjectIdentifier`. Each host entity gets
  one PDU with all its systems and beams:
  - on every change (state update indicator 1) and as a heartbeat (0);
  - without a beam or system that was deleted. When the last system of an entity is deleted, or
    the gateway stops, the PDU lists no systems.
  - The emitter id number is `EmitterIndex` and the beam id number `BeamIdentifier`, else a free
    one. A jammer's `JammingModeSequence` is the DIS jamming technique (both are 32 bits).
  - Track/jam targets are written when they resolve to DIS entities. Their emitter and beam
    numbers are 0, because RPR does not carry them.

Interactions become:

- Fire, Detonation and Collision PDUs;
- Signal PDUs: encoded audio found by its `StreamTag`, raw binary / application specific / database index by `HostRadioIndex`; the Link 16 interactions (`Link16RadioSignal` subclasses) as Link 16 Signal PDUs (below);
- Start/Resume, Stop/Freeze and Acknowledge PDUs.

Appearance and capabilities come from the RPR attributes.

A copied entity starts with a provisional id of the gateway and takes its `EntityIdentifier` when it arrives. A radio becomes `<host>/radio-<RadioIndex>` once its host is known, an emitter system `<host>/emitter-<n>`, a beam `<system>/beam-<m>`. References to the old names keep working.

Between an RPR v3 federation and DIS, emitters go through the v2 ⇄ v3 conversion (section 8). Every shared attribute converts, the jamming technique (v2 32 bits, v3 `JammingTechniqueStruct`) in code. The v3-only `BlankingSectors`, `AngleDeceptionData` and `FalseTargetsData` have no place in v2 or DIS and are dropped.

PDUs are sent from a socket of their own, so the endpoint recognises and drops its own broadcasts and multicasts. Inspect uses a Rift DIS datasource on the same port (address reuse): fine for broadcast and multicast, not for unicast.

### C-DIS endpoints

`endpoint.standard=cdis` (0.9.18) is Compressed DIS, SISO-STD-023-2024, on the network, and a DIS 7 endpoint inside: everything above applies, and PDUs are encoded to C-DIS when sent and decoded to DIS 7 when heard. All 22 PDU types C-DIS has are converted: Entity State, Fire, Detonation, Collision, the twelve simulation management PDUs (Create Entity to Comment), Electromagnetic Emission, Designator, Transmitter, Signal, Receiver and IFF (layers 1 to 5). A PDU of another type (e.g. Entity State Update) is not sent; the page counts it. The `endpoint.dis.*` keys work as for DIS; the C-DIS samples use port 3100, so a DIS network on 3000 and a C-DIS one can run side by side.

| Key | Default | Meaning |
|---|---|---|
| `endpoint.cdis.updatemode` | `full` | `full`: every PDU complete. `partial`: Entity State, EE, Designator, Transmitter and IFF PDUs carry only the fields that changed, with a full update for an object's first and last update and at least every full update period. Partial updates are a federation agreement; the decoder takes both in either mode |
| `endpoint.cdis.fullupdatemultiplier` | `2.4` | HBT_CDIS_FULL_UPDATE_MPLIER: full update period = multiplier × heartbeat (partial mode) |
| `endpoint.cdis.timeoutmultiplier` | `2.4` | The decoder forgets an object after multiplier × heartbeat without updates |
| `endpoint.cdis.heartbeat.entitystate` / `.emission` / `.designator` / `.transmitter` / `.iff` | `5` / `10` / `5` / `2` / `10` | The DIS heartbeats agreed in the federation, seconds |

How the codec works (decisions where the standard leaves room):

- **One PDU per datagram**, zero padded to whole octets. The decoder also reads several PDUs packed bit by bit one after the other (as other implementations send them). A PDU of another C-DIS version or an unknown type is skipped by its length; a version of 0 (DIS) or fewer bits than a header end the datagram.
- **Altitude** is the height above the WGS84 ellipsoid (no geoid), in centimetres, or dekametres above 83.9 km; the special value -8 388 608 is the ECEF origin.
- **Precision.** Locations to about 1 cm, velocities 0.1 m/s, accelerations 0.1 m/s², angles 0.044°, angular velocities 0.35°/s. Values beyond a field's range are clamped; angles are reduced to the range first. Custom floats (frequencies, pulse widths, articulation values) keep the largest mantissa that fits, rounded.
- **Fields the standard does not carry.** Velocity, acceleration and angular velocity are sent only when the dead reckoning algorithm uses them (they arrive as 0 otherwise). A zero alternate entity type, capabilities, DR "other" parameters and zero optional fields are left out (they arrive as 0). The Detonation PDU's Explosion descriptor (explosive material and force) has no place in C-DIS 2024: the force is lost. Beam antenna pattern EZ/EX are whole numbers in C-DIS. The Designator wavelength is micrometres in DIS, nanometres in C-DIS.
- **Variable parameter records** are compressed when their values fit the C-DIS fields, else sent as the 128 DIS bits (the standard allows both).
- **Partial updates.** The decoder keeps every object's last state and drops partial updates of an object it has no full update of yet (counted as *waiting for a full update*). An Entity State with the *deactivated* appearance bit is the entity's last update. EE beams and systems are remembered by emitter number and beam id. Only IFF layer 1 is partial; layers 2 to 5 are sent whenever the DIS PDU has them. With layer 3 or 4 the system id is always sent, because those layers are read by the system type (interrogators: types 2, 4, 6, 7, 8; every other type, combined interrogator/transponders and TCAS too, as transponders).
- **Where the standard contradicts itself**, its tables were followed: the Entity State VP record count is a UVINT8 (the text says 8 bits); the Beam Sweep Sync is 10 bits unsigned (one table says SVINT13; the text and the EE PDU table say 10 bits).

The gateway page shows the update mode and how much of the DIS volume was sent (hover for the full counters); `GET /api/junction/gateways/{gid}` has them under the endpoint's `dis.cdis`.

### The PDU relay

`router.kind=pdu` (router kit `pdu-relay`, sample gateway `DIS7<->CDIS`) passes DIS PDUs between DIS 6, DIS 7 and C-DIS endpoints as they are, not through the RPR model: every PDU one endpoint hears goes to the others with their protocol version and exercise (DIS 7 → DIS 6 also clears the PDU status octet, which is padding in DIS 6). So DIS 7 ⇄ C-DIS carries every PDU type C-DIS has, also those the RPR model does not (e.g. simulation management, IFF layers 3 to 5).

| Key | Default | Meaning |
|---|---|---|
| `router.kind` | `iem` | `pdu` for the relay |
| `router.pdu.types` | all | `;`-separated PDU type numbers to pass, e.g. `1;2;3;25;26;27` |

- Only DIS 6, DIS 7 and C-DIS endpoints (a configuration with others is invalid).
- An endpoint receives only its exercise (`0`: all), as always, and drops what its own sender sent, so relayed PDUs do not come back.
- The class filters, names, value rules and RPR conversion of the IEM router do not apply.
- The page shows the PDUs relayed; the status has them per direction and per PDU type.

---

## 10. RTI libraries per endpoint

`endpoint.hla.rtijar` loads an RTI in isolation: its own class loader, with the HLA API from the jar (or next to it). One gateway can then bridge two RTI vendors or versions, e.g. pRTI 5 on the class path and pRTI 6 from `lib/rti/prti6/prti1516e.jar`.

- Several jars are separated by `;`. Relative paths are relative to Rift's runtime folder. The jar manifest's `Class-Path` is followed.
- Connect settings come from `endpoint.hla.rtiaddress` / `rtisettings` (and `rtifactory` to pick a factory).
- The Federate Protocol (`ieee1516-2025-fedpro`) works the same way, with the vendor's FedPro client jar and the server in `rtiaddress`.
- A missing library is reported on the page ("RTI MISSING") and by the API (`RTI_NOT_AVAILABLE`). Rift itself is not affected.
- **One connect or join at a time per library.** A gateway opens its endpoints in parallel, but the connect, create and join calls on one RTI library (the class path RTI, or one `rtijar`) take turns, also with other gateways and Rift's datasources. An RTI's local library may not take two at once: pRTI failed with a `ConcurrentModificationException` when the two Talk endpoints of a gateway joined at the same moment. Everything after the join stays parallel.

---

## 11. The page

`junction.html` (top bar: **Junction gateways**) has three panels:

1. **Running gateways**, grouped ones first, by group and instance. Each gateway is drawn as a patch board: the router as a bus, each endpoint as a port with traffic in and out and its state, rates and counters. Each gateway has buttons for **Compatibility** (its route tables), **Remove** (stop) and, per endpoint, **Inspect**. Inspect opens a Rift datasource on that endpoint's target in a new tab. Talk gateways also list their channels. Updates arrive live (SSE).
2. **Gateway configurations**, grouped, with **Start group** / **Stop group**. A group lists its running instances, each with its own **■ Stop** (section 7). The Start group dialog and a gateway's **Start** dialog ask for the instance name, prefilled with the next free number. A gateway's **Start** dialog asks for each endpoint's target and offers:
   - federations the RTI knows (**Discover**);
   - exercises heard on the DIS network (**Listen**);
   - Talk channels;
   - group variables, when the gateway uses any;
   - the LSD of each HLA endpoint (section 4, *LSD choices*).
3. **Kits**: the endpoint and router kits.

**Infra** in the top bar (0.9.20) lists every DIS / C-DIS exercise the running gateways' DIS endpoints hear and every federation their HLA endpoints have joined, with the senders and federates in them (see the Rift guide, section 2.6). A DIS endpoint does not list its own PDUs there; each HLA endpoint's federate asks the MOM, nothing more.

---

## 12. Limits and things to verify

- **Pitch Talk** behaviour with mirrored clients and foreign `SenderUUID`s, the PCM16 byte order and the sample rate are not documented in the FOM. Verify them against your Talk version (`talk.audio.*`).
- **Opus** is passed through, not decoded.
- No time management, no ownership transfer, no DDM regions.
- DIS 7-only data that RPR v2 has no place for (e.g. variable parameter separation records) is not passed on (the PDU relay passes it).
- **C-DIS:** the codec follows SISO-STD-023-2024 and was checked against bit vectors of the dis-rs implementation, but not yet against another C-DIS product on a network. Known differences of dis-rs 0.4 from the standard (so PDUs of these types will not decode between the two): its Detonation PDU has a fourth field present flag (explosion descriptor), and its EE PDU writes the site/application pair count and index in 5 and 4 bits (the standard: 6 and 6).
- The RPR v2/v3 table is lossy where the FOMs differ in substance (section 8).
- **IFF bit layouts** (IEEE 1278.1, SISO-REF-010) to confirm against your systems:
  - Mode C altitude: bit 0 the sign, bits 1-11 hundreds of feet, all ones *not available*;
  - Mode 4: bit 12 = alternate Mode 4 in use;
  - RRB: code in bits 0-4, power reduction bit 9, radar enhancement bit 10;
  - Mode S: bit 0 = TCAS II.
- **IFF and RPR v3:** Mode 5, TCAS and the Mode S details are not passed between DIS / RPR v2 and v3 (see section 8).
- **Link 16:** the J-message contents are defined in the restricted MIL-STD-6016; the gateway copies them, and Rift shows them as hex. The RPR v3 FOMs here have no Link-16 module, so between RPR v2 and v3 a Link 16 interaction goes as its nearest v3 superclass (`RawBinaryRadioSignal`, without the message data), unless both FOMs include the module. Check against your Link 16 simulators which `SISOSTD002Version` they send.
- **FOM from the federation** (section 4) relies on the RTI providing `HLAcurrentFDD` as an `HLAunicodeString` with the merged FDD, and updating it when modules are added. The test RTIs do (`-Dfakerti.fddformat=2025` makes them report it in the 1516-2025 format, as pRTI 6 does); verify it with your RTI (pRTI 5 / 6), including the *FDD has changed* notice. `endpoint.hla.fddclass` / `fddattribute` cover an RTI that names them differently.
- **Name templates** (section 5) apply to the copies the router registers. A reference to an object the gateway does not bridge passes with its original name.
- **Value rules** (section 5) have no wildcards and no per-direction scope yet. They do not change values that the gateway builds itself: the IFF mode objects of `RprIffBridge` and Pitch Talk channels.

---

## 13. Self tests

Built with `build.sh`; each prints `N checks, 0 failures`. The test RTIs (`fakerti/`) are in-memory RTIs for 1516-2010 and 1516-2025.

| Test | Covers |
|---|---|
| `com.simweaver.common.hla.codec.HlaCodecSelfTest` | HLA encoding round trips over the RPR v2/v3 and NETN models |
| `com.simweaver.common.hla.session.RtiSessionSelfTest` | RTI loading without an RTI on the class path |
| `com.simweaver.common.dis.DisRoundTripSelfTest` | DIS → RPR → DIS for every PDU type, DIS 6 and 7, byte for byte: Electromagnetic Emission, Designator, IFF (transponder with layer 2, interrogator, Soviet, RRB) and Link 16 Signal PDUs (all eight message types, TDL 100 and 113) included; the Link 16 decoder's names, LET header, time slot and NTP time |
| `com.simweaver.junction.BridgeSelfTest` | Filters; HLA3 ⇄ HLA4 on two isolated RTIs: renaming, references, provide, late joiner, reconnect |
| `com.simweaver.common.infra.InfraSelfTest` | Infra without network or RTI: the MOM datatypes, the activity meter, a DIS tap (bundled and padded PDUs, versions, exercises, senders, site and application numbers, Live Entity ids, broken datagrams, C-DIS), forgetting, stable keys, the REST API's errors |
| `com.simweaver.rift.InfraBridgeSelfTest` | Infra end to end (class path test RTI, `fakerti-1516e.jar` on the class path): a gateway with HLA 1516-2010, 1516-2025 and DIS 7 endpoints and a Rift datasource; the items, the MOM's federates, rates, traffic per producer, publications and subscriptions, a DIS exercise's senders, a DIS entity already sending when the gateway starts, items going when their sources stop |
| `com.simweaver.junction.ProvideForwardSelfTest` | Provide requests the cache cannot answer: only the missing attributes asked of the origin, its answer reaching the requester, cached attributes not asked again, the interval, `router.provide.forward=false`, the counter |
| `com.simweaver.junction.DisBridgeSelfTest` | HLA ⇄ DIS 7 over UDP: entities, ids, heartbeat, radio and audio, fire both ways, timeout, own-echo suppression; emitter systems, radar and jammer beams both ways (names, references, values, beams switched off, systems removed, heartbeats); IFF transponders and designators both ways; Link 16 (`JTIDSMessageRadioSignal`) both ways; a DIS entity whose name is taken in HLA (`1-2-3@dis`), with fire references following it both ways |
| `com.simweaver.junction.TalkBridgeSelfTest` | Two Talk federations + DIS radio: channel remapping and renaming, client mirroring, lobby, radio bridge both ways with resampling; messages-only mode (no objects, UUID map, radio by UUID); the lobby only (`talk.channels=none`: lobby audio, text and images pass, a known and an unseen channel do not) |
| `com.simweaver.junction.GroupSelfTest` | Groups and group variables; instances of a group and of a single gateway: names, refusals, listings, stop per instance, completing an instance; variable descriptions (labels only, merged per group, warnings) |
| `com.simweaver.junction.router.RprConversionSelfTest` | RPR v2 → v3 → v2 with the delivered table, emitter systems and beams, designators and IFF systems included |
| `com.simweaver.junction.IffBridgeSelfTest` | IFF modes RPR v2 ⇄ v3 (attributes ⇄ IFFData objects) both ways, and DIS IFF PDUs ⇄ an RPR v3 federation |
| `com.simweaver.junction.NamingSelfTest` | Name templates: tokens, cutting to a length, the settings and their mistakes; an HLA ⇄ HLA gateway renaming in one direction, references in a simple attribute, an array, a record and interaction parameters both ways; the same names after a restart, also for objects that existed before the gateway started |
| `com.simweaver.junction.DynamicFomSelfTest` | FOM from the federation: decoding `HLAunicodeString`, building models from a 1516-2010 and a merged 1516-2025 FDD, RPR v2 recognised from the classes, the settings and their mistakes; the FDD read in its own format (a 1516-2025 FDD for a 1516-2010 federate); HLA 3 and HLA 4 gateways with both endpoints `dynamic`, and an HLA 3 gateway on an RTI that reports 1516-2025 FDDs: bridging, the FOM source in the API, the notice when a federate joins with more modules, a missing federation |
| `com.simweaver.junction.LsdSelfTest` | LSD choices: the setting's form, group variables in it, the default, another choice, a typed value, the RTI default, mistakes; the test RTI refuses one LSD to show which one an endpoint connects with; group starts with LSDs per gateway; Inspect with the endpoint's LSD |
| `com.simweaver.common.dis.cdis.CdisSelfTest` | C-DIS (SISO-STD-023-2024): bit packing, every VARINT size and its boundaries, custom floats, the header bit by bit, markings, world coordinates; all 22 PDU types DIS 7 → C-DIS → DIS 7 byte for byte at C-DIS precision (EE with shared lists, IFF layers 1 to 5, transmitter with a beam antenna pattern, variable datums, VP records); arbitrary entity states within precision, clamping; partial updates of all five stateful PDUs (unchanged, changed, full update period, last update, a field falling out of use, waiting for a full update, timeouts); datagrams with several bit-packed PDUs and unknown ones; errors; bit vectors of the independent dis-rs implementation |
| `com.simweaver.junction.CdisBridgeSelfTest` | C-DIS endpoints: the settings and their mistakes; HLA ⇄ C-DIS over UDP with full and with partial updates (location within C-DIS precision, heartbeats as partial updates, C-DIS entities discovered in HLA, the counters); the PDU relay DIS 7 ⇄ C-DIS ⇄ DIS 6 (PDUs the same after relaying both ways, version rewrite, exercise filter, a type C-DIS does not have) |
| `com.simweaver.rift.datasources.CdisDatasourceSelfTest` | The Rift C-DIS datasource: settings, a full update and partial updates over UDP into the database, another exercise, a broken datagram |
| `com.simweaver.junction.router.ValueMapSelfTest` | Value rules: the settings and their mistakes, RPR v2 and v3 models, nested values, enumerations, routes as is and v2 ⇄ v3, interaction parameters, and a DIS ⇄ HLA gateway that replaces entity and munition types both ways |
