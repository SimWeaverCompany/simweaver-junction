# SimWeaver Junction 0.9.18

This folder holds the new and changed files compared with the tree you sent on 2026-10-24, with
your `etc` folder from 2026-10-25. It also contains everything from 0.9.17 back to 0.9.12,
described further down, so it replaces those deliveries.

**Files to delete in your tree** (from 0.9.17, if you haven't applied it yet). This folder only
adds and replaces files, so the old sample files have to go by hand:

- Easiest: replace `simwrift/rtdir/etc/junctions`, `etc/junctions.dist` and `etc/datasources`
  as a whole with the ones in this folder. They're complete, your ASIM files and the mapping
  included.
- Otherwise delete these, in both junction folders:
  - `hla-dis6`, `hla-dis7`, `hla-hla-dynamic`, `rpr2-rpr3`, `exercise-hla-dis`, `exercise-talk`,
    `prti5-prti6` and `talk-radio` (`.junction.settings`);
  - the endpoint kits `hla-evolved-rpr2`, `hla-evolved-dynamic`, `hla4-dynamic`, `hla4-fedpro`,
    `prti6-rpr2`, `rpr3` and `pitchtalk`;
  - the router kits `rpr-all`, `entities-and-warfare`, `pitchtalk`, `pitchtalk-messages` and
    `pitchtalk-lobby`.
- In `etc/datasources`, delete `sample1`, `sample2`, `sample3` and `dissample`.
- The ASIM files and `mappings/` are unchanged.

## Version 0.9.18

Rift (`rift/Version`, revision 18) and Junction (`junction/Version`) are both 0.9.18, and so are
`README.md`, the docs and the developer notes.

## C-DIS (SISO-STD-023-2024)

Compressed DIS, from the 2024 standard you sent, in three places:

- a **C-DIS endpoint** in Junction (`endpoint.standard=cdis`);
- a **PDU relay** router (`router.kind=pdu`): DIS 7 ⇄ C-DIS PDU by PDU, not through the RPR
  model;
- a **C-DIS datasource** in Rift (`datasource.standard=cdis`).

They all use one codec in `simwcommon` (`com.simweaver.common.dis.cdis`), with no libraries.

### The codec

- **All 22 PDU types** C-DIS has, both ways: Entity State, Fire, Detonation, Collision, the
  twelve simulation management PDUs, Electromagnetic Emission, Designator, Transmitter, Signal,
  Receiver and IFF (layers 1 to 5).
- **Full updates by default.** Every PDU is complete; the codec keeps no state for sending.
- **Partial updates** are a setting (`endpoint.cdis.updatemode=partial`). Entity State, EE,
  Designator, Transmitter and IFF PDUs then carry only the fields that changed. A full update
  goes out for an object's first and last update, and at least every
  `fullupdatemultiplier` (2.4) × heartbeat.
- **Decoding takes both**, whatever the setting (the standard requires that):
  - the decoder keeps every object's last state and fills in what a partial update leaves out;
  - it drops partial updates of an object it has no full update of yet;
  - it forgets an object after `timeoutmultiplier` (2.4) × its heartbeat without updates.
- **Heartbeats** are settings too (`endpoint.cdis.heartbeat.*`, DIS 7 defaults 5 / 10 / 5 / 2 /
  10 s), as the standard asks for federation agreements.
- **Altitude** is the height above the WGS84 ellipsoid, as you chose. The standard says "MSL",
  but gives no geoid. Centimetres, or dekametres above 83.9 km; -8 388 608 is the ECEF origin.
- **One PDU per datagram**, zero padded to whole octets. The standard has no rule for this. The
  decoder also reads several PDUs packed bit by bit, as dis-rs sends them. It skips unknown
  types and other C-DIS versions by their length, and stops at version 0 (DIS).

**Precision** is the standard's: locations about 1 cm, velocities 0.1 m/s, angles 0.044°. A
value beyond its field is clamped; an angle is reduced to the range first.

**Decisions where the standard leaves room or contradicts itself.** These are in `junction.md`
section 9 too:

- **ES VP record count:** the text says 8 bits, the table UVINT8. I followed the table.
- **Beam Sweep Sync:** one table says SVINT13, the text and the EE table 10 bits unsigned. I
  used 10 bits.
- **Explosion descriptor:** the Detonation PDU's Explosion descriptor (material, force) has no
  C-DIS 2024 fields. The force is lost.
- **Designator wavelength:** micrometres in DIS, nanometres in C-DIS, so it's converted.
- **Beam antenna pattern:** EZ/EX are whole numbers in C-DIS.
- **VP records** are compressed when the values fit, else sent as the raw 128 DIS bits (both are
  allowed).
- **Fields left out:** velocity, acceleration and rotation go only when the DR algorithm uses
  them. Zero optional fields are left out. Both arrive as 0.
- **IFF layers 3 and 4** depend on the system type, so the system id always goes with them.
  Interrogators are types 2, 4, 6, 7 and 8; everything else is read as a transponder. Only layer
  1 is partial.
- The standard's own **typos** (cm = m/100, the 2023 version string) did not change anything.

### The C-DIS endpoint

- It's a DIS 7 endpoint with the codec around its socket. Everything DIS endpoints do applies:
  RPR model, ids, heartbeats, Inspect.
- A PDU of a type C-DIS doesn't have (e.g. Entity State Update) is not sent, and is counted.
- **New kit** `endpoints/cdis.endpoint.settings`, on UDP port 3100. A DIS network on 3000 and a
  C-DIS one can then run side by side.
- **New gateways** `hla3-cdis` (HLA3<->CDIS) and `hla4-cdis` (HLA4<->CDIS), in both junction
  folders.

### The PDU relay

- `router.kind=pdu` passes DIS PDUs between DIS 6, DIS 7 and C-DIS endpoints as they are. Each
  PDU heard on one endpoint goes to the others with their version and exercise; DIS 7 → DIS 6
  also clears the status octet.
- **DIS 7 ⇄ C-DIS** therefore carries every C-DIS PDU type, including those the RPR model has no
  place for: simulation management, IFF layers 3 to 5, separation records.
- `router.pdu.types` limits the types passed.
- A configuration with an HLA or Talk endpoint is invalid.
- **New router kit** `routers/pdu-relay.router.settings`, and gateway `dis7-cdis`
  (DIS7<->CDIS), in both folders.

### Rift

- `datasource.standard=cdis`: a DIS 7 datasource that decodes C-DIS first. Its decoder settings
  are `datasource.cdis.*`.
- New sample `etc/datasources/cdis.datasource.settings`.
- Inspect on a C-DIS endpoint opens a C-DIS datasource.

### The pages

- **Gateway page:** a C-DIS endpoint shows its update mode and how much of the DIS volume it sent
  (e.g. *C-DIS FULL · SENT AT 33 % OF DIS*). Hover it for all the counters. A PDU relay shows the
  PDUs relayed, and its card *PASSES* instead of filter and names.
- **Gateway status:** endpoints of DIS and C-DIS gateways now have their DIS diagnostics (`dis`),
  with the C-DIS counters.
- **Config assistant:**
  - Junction: the C-DIS standard, with update mode, multiplier and heartbeats; the `cdis` and
    `pdu-relay` kits; the PDU relay as a router kind (PDU types, checks); and two templates,
    *HLA ⇄ C-DIS* and *DIS 7 ⇄ C-DIS relay*.
  - Rift: a C-DIS datasource template.
- Standard badges and Rift's config cards know C-DIS.

### Measured

On the exact round-trip test set (all 22 types), C-DIS takes 52 % of the DIS octets. A static
entity is 39 instead of 144 octets. A moving one in partial mode is about 14 octets per heartbeat.

### Checked against another implementation

dis-rs (MIT; its author is in the SISO ballot group) has C-DIS 2024. Five of its unit-test bit
vectors decode correctly here: the header, two markings, Collision and Fire.

I couldn't build dis-rs for a live test: crates.io isn't reachable from my environment.

dis-rs 0.4 differs from the standard in two places, so these will not pass between it and us:

- its **Detonation** has a fourth field present flag (explosion descriptor);
- its **EE PDU** writes the site/application pair count and index in 5 and 4 bits (the
  standard: 6 and 6).

I followed the standard. A test against a C-DIS product on a real network is still to do.

## Files (0.9.18)

- New: `simwcommon/src/com/simweaver/common/dis/cdis/` (the codec, 19 classes),
  `simwjunction/.../endpoint/PduPort.java`, `.../router/PduRelayRouter.java`,
  `simwrift/.../datasources/RiftCdisConnection.java`.
- Changed:
  - `EndpointType`, `EndpointConfig`, `RouterConfig`, `ConfigLoader`, `Gateway`, `DisEndpoint`;
  - `DisWriter` (`entityId(site, app, number)`), `FederationProbeFactory`;
  - Rift `DatasourceConfig`, `DatasourceManager`, `DatasourceFactory`, `RiftDisConnection`,
    `RiftJunktionHost`.
- Samples (both junction folders): `endpoints/cdis`, `routers/pdu-relay`, `hla3-cdis`,
  `hla4-cdis`, `dis7-cdis`; `etc/datasources/cdis`.
- Web: `junction.js`, `junction-start.js`, `config-card.js`, `config-assistant-junction.js`,
  `config-assistant-rift.js`, `css/junction.css`.
- Tests: `CdisSelfTest` (111 checks), `CdisBridgeSelfTest` (43), `CdisDatasourceSelfTest` (9).
- Version: `rift/Version.java`, `junction/Version.java`, `README.md`.
- Docs: `junction.md` (+ `junction-config.md`), `junction-rest-api.md`, `rest-api.md` (+
  `rift-rest-api.md`), `simweaver-rift-install-and-config.md` (+ `rift-install-and-config.md`),
  `CLAUDE.md`.

## Checked (0.9.18)

- Your tree with this folder applied builds with `build.sh`, with no `junctions.dist` warning.
- All self tests pass: the 19 suites as before, plus the three new ones.
- **Live, on the test RTIs:**
  - `hla3-cdis`, `hla4-cdis` and `dis7-cdis` all start with no configuration errors or warnings;
  - C-DIS entities sent with partial updates were bridged into HLA, and seen in Rift through
    Inspect;
  - DIS 7 entities were relayed to C-DIS at 33 % of the octets.
- The config assistant writes valid C-DIS endpoints and relay gateways.

---

## Version 0.9.17

Rift (`rift/Version`, revision 17) and Junction (`junction/Version`) are both 0.9.17, and so are
`README.md`, the docs and the developer notes.

## Junction samples, reorganised (0.9.17)

File names are lowercase, as before; the names you gave are the kits' `endpoint.name` and the
gateways' `junction.name`. Every gateway uses kits.

**Endpoint kits** (both folders):

| Kit | Name | What |
|---|---|---|
| `hla3-dynamic-fom` | HLA3-dynamic-FOM | HLA Evolved, the FOM from the federation |
| `hla4-dynamic-fom` | HLA4-dynamic-FOM | HLA 4, the FOM from the federation |
| `hla3-rpr2` | HLA3-RPR2 | HLA Evolved, RPR v2 with Link 16 |
| `hla4-rpr2` | HLA4-RPR2 | HLA 4, the full RPR v2 with Link 16, converted (below) |
| `hla3-rpr3` | HLA3-RPR3 | HLA Evolved, RPR v3 |
| `dis6`, `dis7` | DIS6, DIS7 | UDP port 3000 |
| `talk` | Talk | A Pitch Talk federation |

**C-DIS** has no kit yet: see the end of this section.

**Router kits** (both folders):

| Kit | Name | What |
|---|---|---|
| `hla3-all`, `hla4-all` | HLA3-all, HLA4-all | Every class both sides have |
| `hla3-class-filter` | HLA3-class-filter | Entities, fire and detonations only |
| `hla3-entitytype-filter` | HLA3-EntityType-filter | Everything, entity types rewritten by value rules |
| `talk-lobby-only` | Talk-Lobby-Only | Pitch Talk messages in the lobby only |
| `talk-messages` | Talk-Messages | Pitch Talk messages only, all channels by name |

`hla3-all` and `hla4-all` are identical, because routers work with any endpoint. They're kept
apart, as you listed them, so a gateway's kit says what it's used with.

**Gateways:**

| Gateway | File | Where |
|---|---|---|
| HLA3<->HLA3 dynamic-FOM | `hla3-hla3-dynamic-fom` | `etc/junctions` and dist |
| HLA3<->DIS7 | `hla3-dis7` | `etc/junctions` and dist |
| HLA3<->DIS6 | `hla3-dis6` | `etc/junctions` and dist |
| HLA4<->DIS7 | `hla4-dis7` | `etc/junctions` and dist |
| HLA4<->DIS6 | `hla4-dis6` | `etc/junctions` and dist |
| HLA3<->HLA3 - RPR3<->RPR2 | `hla3-hla3-rpr2-rpr3` | dist only |
| HLA3<->HLA3 - EntityType filter | `hla3-hla3-entitytype-filter` | dist only |
| HLA3<->HLA3 - class filter | `hla3-hla3-class-filter` | dist only |

**Suggestions, in `junctions.dist` only.** These are the old samples your list doesn't have,
renamed and moved onto the new kits. Delete any you don't want:

- `exercise-hla3-dis7` + `exercise-talk-talk`: the only group sample, with group variables and
  their descriptions (the Talk part now uses Talk-Messages).
- `talk-talk-lobby-only`: Talk-Lobby-Only in use.
- `talk-talk-dis7-radio`: the Talk ⇄ DIS radio bridge. It needs full mode, so it comes with its
  own router kit `talk-full`.
- `hla3-hla3-two-rtis`: two RTI versions in one process, with the kit `hla3-rpr2-own-rti`.
- The kit `hla4-fedpro-rpr2`: the Federate Protocol.

**Samples you may be missing:**

- A **NETN** endpoint: the NETN 4 modules are in `etc/fom/ieee1516-2010/netn4-hla3`, but no
  kit uses them.
- An **HLA3<->HLA4** gateway (RPR v2 on both, two RTI APIs).
- An **HLA3<->HLA3 name template** gateway (`router.names.template`).
- A **Talk<->Talk with a channel map** (`talk.channelmap`).

**C-DIS** isn't supported yet. A `cdis` kit and gateways come with it, once the SISO-STD-023
document is here.

## New FOM: the full RPR v2 with Link 16 in the HLA 4 format (0.9.17)

`etc/fom/ieee1516-2025/RPR_FOM_v2.0_Link16_1516-2025.xml` is the 1516-2010
`RPR_FOM_v2.0_Link16_1516-2010.xml`, converted to 1516-2025. The rules were read from how the HLA
4 RPR v2 modules (in your reference zip) differ from the 1516-2010 originals:

- the namespace and schema location;
- `<copyright/>` in the model identification;
- `<valueRequired>false</valueRequired>` in every attribute;
- the XML attribute `notes="…"` becomes `noteReferences="…"`;
- the `conveyProducingFederate` switch and the empty `dimensions`, `transportations` and
  `updateRates` sections are left out, as HLA 4 has them no more.

**Checked:**

- Against the HLA 4 RPR v2 modules, everything the two have in common is identical: every
  attribute (all variants of names that occur in several classes), parameter, datatype, class
  definition, switch and note.
- The Link 16 classes and datatypes come on top.
- Junction's models of the 1516-2010 and the 1516-2025 file are equal: 64 object classes, 89
  interaction classes, 2078 members, 0 differences.
- RPR v2 is recognised.

I couldn't validate it against the IEEE 1516-2025 XSD (not available here). pRTI 6 is the real
test.

## Rift samples, renamed (0.9.17)

| Was | Now (`etc/datasources`) | Name |
|---|---|---|
| `sample1` | `hla3-rpr2.datasource.settings` | HLA3-RPR2 |
| `sample2` | `hla4-rpr2.datasource.settings` | HLA4-RPR2. Now with the full RPR v2 + Link 16 in the HLA 4 format (was four RPR modules) |
| `sample3` | `hla3-rpr2-talk.datasource.settings` | HLA3-RPR2-Talk |
| `dissample` | `dis6.datasource.settings` | DIS6 |

- Each file has a comment line saying what it is.
- Your LSD and federation values are unchanged (`test1`, `test2`, `MumboJumbo`,
  `PitchTalkDeafault` — the last one looks like a typo of *Default*, so check it).
- A configuration's id is derived from its file name, so a page bookmarked on an old sample's id
  won't find it.
- **Suggested additions:** DIS7, HLA3-dynamic-FOM, HLA4-dynamic-FOM, HLA3-RPR3 and HLA3-NETN4
  datasources. Also an `etc/datasources.dist` like `junctions.dist`, so that samples can be kept
  without being listed.

## Also (0.9.17)

- The **Config assistant** knows the new kits, and marks the dist-only kits so. Its templates use
  them.
- **Docs:**
  - `junction.md` section 2 lists the new samples, kits and the FOM conversion; sections 3, 4, 6
    and 7 use the new names.
  - The install guide lists the Rift samples.
  - The REST docs and the UI guide use the new names.

## Files (0.9.17)

- `simwrift/rtdir/etc/junctions/`, `etc/junctions.dist/` (new sample set, README),
  `etc/datasources/` (renamed), `etc/fom/ieee1516-2025/RPR_FOM_v2.0_Link16_1516-2025.xml` (new)
- `simwrift/rtdir/webroot/js/config-assistant-junction.js`
- Version: `rift/Version.java`, `junction/Version.java`, `README.md`
- Docs: `junction.md` (+ `junction-config.md`), `junction-rest-api.md`, `rest-api.md` (+
  `rift-rest-api.md`), `simweaver-rift-install-and-config.md` (+ `rift-install-and-config.md`),
  `doc/README.md`, `CLAUDE.md`

## Checked (0.9.17)

- Your tree with this folder applied builds with `build.sh`, with no `junctions.dist` warning.
- All self tests pass (the same 19 suites and counts as 0.9.16).
- **Every sample gateway was started** on the test RTIs, with no configuration errors or warnings:
  - `hla3-dis6`, `hla3-dis7`, `hla4-dis6`, `hla4-dis7` (on the new HLA 4 FOM),
    `hla3-hla3-rpr2-rpr3`, `hla3-hla3-entitytype-filter` (value rules compiled) and
    `hla3-hla3-class-filter`;
  - `exercise-hla3-dis7`, `exercise-talk-talk`, `talk-talk-lobby-only` and
    `talk-talk-dis7-radio`.
- Not started:
  - `hla3-hla3-dynamic-fom` needs running federations (checked in 0.9.14);
  - `hla3-hla3-two-rtis` needs the pRTI 6 jar;
  - the ASIM gateways were left alone.

---

## Lobby only, the join fix, group variable descriptions and Discover (0.9.16)

Rift (`rift/Version`, revision 16) and Junction (`junction/Version`) are both 0.9.16, and so are
`README.md`, the docs and the developer notes.

### New: Pitch Talk, the lobby only (`talk.channels=none`)

```properties
router.kind=talk
talk.mode=messages
talk.messages=audio;text;image
talk.channels=none
```

- **Only the lobby's messages pass** (the all-zero `ChannelUUID`). A message in any other channel
  is dropped and counted as *filtered*, whether the gateway has seen that channel or not.
  - That last part is the point: in messages mode, a message for a channel the gateway hasn't
    seen passes with its UUID unchanged. A filter that matches no name would leak those.
    `none` doesn't.
- No channel is bridged or created. `talk.channelmap`, `talk.channeluuidmap` and
  `talk.radio.map` aren't used, and the gateway warns when they're set.
- It works in full mode too. There, clients and users are still mirrored.
- **New kit** `routers/pitchtalk-lobby.router.settings` (in `etc/junctions` and `junctions.dist`):
  messages mode, the lobby only, audio, text and images. Point a Talk gateway at it with
  `junction.router=routers/pitchtalk-lobby.router.settings`.
- **ASIM-COMMS** (`routers/ASIM-COMMS.router.settings`, both folders) now runs in messages mode, so
  only `PitchTalk.Message` interactions get through:
  - audio, radio, text and images;
  - channels matched by name, and its `voice_/radio_GROUPVAR1>…GROUPVAR2` map kept;
  - no Client, User or Equipment mirroring any more.
  - `talk.mirrorclients` and `talk.createmissingchannels` are gone, as they don't apply in
    messages mode.
  - A mapped channel the other side lacks is still registered there (`talk.createmappedchannels`,
    on by default). Set it to `false` if nothing at all may be registered.
- **Tested:** TalkBridgeSelfTest now has 87 checks (12 new):
  - lobby audio, text and images pass both ways;
  - a channel both federations have and a channel never seen are dropped and counted;
  - nothing is mirrored or registered;
  - the warnings.
- Documented in `junction.md` section 6, *The lobby only*.

### Fixed: ConcurrentModificationException when the endpoints of a gateway join

*CONNECT_FAILED: endpoint ASIM-EXT-Comms (FEDERATION9-COMMS): ConcurrentModificationException* came
from Junction's side, not from your RTI's setup.

- **Cause.**
  - A gateway opens its endpoints in parallel. Here the two Talk endpoints of ASIM-COMMS joined
    `FEDERATION9-COMMS` and `INTERNALCOMMS` at the same moment, through the same RTI library
    in one process.
  - pRTI's local library didn't take two joins at once and failed inside the RTI.
  - Being a race, a start could work one time and fail the next.
  - The same could happen when a group starts its gateways, or when Rift creates a datasource
    while a gateway joins.
- **Fix.**
  - Connect, create and join on one RTI library now take turns: the class path RTI, or one
    `rtijar`, shared by gateways, Talk probes and Rift's datasources alike.
  - Everything after the join stays parallel: subscriptions, the dynamic FDD, the traffic.
  - Starting a gateway may take a moment longer, by the joins of its other endpoints.
- **Logged.** An endpoint that fails to open is now logged with the whole stack trace, so a
  failure inside an RTI can be traced.
- **Tested.**
  - The test RTIs now refuse a connect or join while another is running, with the same
    exception.
  - With the fix taken out, TalkBridgeSelfTest and GroupSelfTest fail with your message. With
    it, all suites pass.
  - Run `sh fakerti/build-fake.sh` again.
- Documented in `junction.md` section 10.

### New: descriptions for group variables

```properties
junction.groupvar.GROUPVAR1.desc=Exercise federation
junction.groupvar.GROUPVAR2.desc=DIS exercise id
```

- **A label for people, nothing else.**
  - The settings still say `GROUPVAR1`, and the API still takes
    `{"vars":{"GROUPVAR1":"…"}}`.
  - A description is never substituted into anything.
  - A variable named inside a description's text doesn't count as used by it.
- **Where it shows:**
  - **Start group** and **Start gateway**: the description is the field's label, with
    `GROUPVAR1` small beside it.
  - The group header: *Exercise federation (GROUPVAR1)*.
  - Running instances and running gateways: *Exercise federation=FederationX*.
  - The configuration cards' VARIABLES chips.
  - The tooltips keep the variable names.
- **In a group,** any gateway file of the group may describe any of the group's variables, also
  one that only another gateway uses. So you can describe them once, e.g. in the group's main
  gateway. When two files describe the same variable differently, the first gateway of the
  group (by file name) wins. A gateway started on its own also gets the group's descriptions.
- **Warnings:**
  - A gateway outside a group that describes a variable its settings don't use.
  - A description key whose name isn't `GROUPVARn`.
  - `.desc` may be written in any case.
- **The Config assistant** now writes the *Meaning* column of its Group panel as these keys. Before,
  it wrote it as a comment.
- **REST:** variables in `GET /api/junction/configs` (per configuration and per group) carry
  `description`, and a running gateway has `vardescriptions`.
- **Samples**, in both `etc/junctions` and `etc/junctions.dist`. These are the only changes to
  your samples:
  - `asim.junction.settings`: GROUPVAR1 is *Training Session* and GROUPVAR2 *Shared Session*,
    for the whole ASIM-Trainer group, ASIM-COMMS included;
  - `exercise-hla-dis.junction.settings`: GROUPVAR1 *Exercise federation* and GROUPVAR2 *DIS
    exercise id*;
  - `junctions.dist/exercise-talk.junction.settings` describes GROUPVAR3.
- Documented in `junction.md` section 7, *Descriptions*, and in `junction-rest-api.md`.

### New: Discover for group variable values

The **⟳** button next to each variable field in **Start group** and **Start gateway** asks the
RTIs which federations run (DIS: which exercises are heard) and offers values to fill in:

- **Values taken out of federation names.**
  - For each endpoint whose federation (or exercise) setting uses the variable, it asks that
    endpoint's RTI, each RTI once, with the LSD shown in the dialog.
  - It then reads the variable's value out of each federation name by the setting as written:
    with `GROUPVAR2-COMMS`, a federation `TheOtherOnes-COMMS` offers **TheOtherOnes**.
  - A value that fits several endpoints comes first. In ASIM-Trainer, *TheOtherOnes* fits both
    `GROUPVAR2` (ASIM-EXT) and `GROUPVAR2-COMMS` (ASIM-EXT-Comms).
- **The whole names too.** Any federation not already offered as a value is listed under *All
  federations*, so a name can always be taken as it is, or copied.
- A variable in no federation setting (a channel name, say) lists the federations of every HLA
  endpoint's RTI.
- Clicking one fills the field, and you can still edit it.
- **For this,** configurations now report each endpoint's target as written when it contains
  variables: `targettemplate`, e.g. `"GROUPVAR2-COMMS"`.
- Documented in `junction.md` section 7, *Discover a value*.

### Files (0.9.16)

- `simwjunction/.../config/GroupVariables.java`: description keys are neither uses nor
  substituted
- `simwjunction/.../config/ConfigLoader.java`: reads them, warnings
- `simwjunction/.../config/GatewayConfig.java`: `getVariableDescriptions()`, JSON
  `description`
- `simwjunction/.../JunctionManager.java`: the group's descriptions; `Gateway.java`:
  `vardescriptions`
- `simwjunction/.../config/EndpointConfig.java`, `ConfigLoader.java`: `targettemplate`
- `simwrift/rtdir/webroot/js/junction.js`, `junction-start.js`, `config-assistant-junction.js`,
  `css/junction.css`
- `simwrift/rtdir/etc/junctions/asim.junction.settings`, `exercise-hla-dis.junction.settings`,
  and in `junctions.dist` the same plus `exercise-talk.junction.settings`
- `simwjunction/test/.../GroupSelfTest.java`: 12 more checks (52)
- The fix: `simwcommon/.../hla/session/RtiLibrary.java` (`connectLock()`),
  `simwjunction/.../endpoint/hla/HlaEndpoint.java`, `TargetProbe.java`, `Gateway.java` (the
  log), `simwrift/.../datasources/RiftFederateHla3/4.java`; `fakerti/core/.../Bus.java`,
  `fakerti/e/…/FakeRtiFactory.java`, `fakerti/v4/…/FakeRtiFactory.java`
- The lobby only: `simwjunction/.../config/RouterConfig.java`, `router/talk/TalkBridge.java`,
  `test/.../TalkBridgeSelfTest.java`; `etc/junctions[.dist]/routers/ASIM-COMMS.router.settings`,
  `pitchtalk-lobby.router.settings` (new)
- Docs: `junction.md` (6, 7, 10, 13), `junction-rest-api.md`, `CLAUDE.md`, `junction-config.md`

### Checked

- Your tree with this folder applied builds with `build.sh`, with no `junctions.dist` warning.
- All self tests pass, 0 failures:

  | Module | Test | Checks |
  |---|---|---|
  | simwcommon | HlaCodec | 93 |
  | simwcommon | DisRoundTrip | 112 |
  | simwcommon | RtiSession | 41 |
  | simwcommon | SimwProperties | 97 |
  | simwcommon | SimwArgs | 57 |
  | simwjunction | Bridge | 41 |
  | simwjunction | DisBridge | 84 |
  | simwjunction | TalkBridge | 87 |
  | simwjunction | Group | 52 |
  | simwjunction | IffBridge | 24 |
  | simwjunction | ValueMap | 43 |
  | simwjunction | RprConversion | 132 |
  | simwjunction | Naming | 40 |
  | simwjunction | DynamicFom | 47 |
  | simwjunction | Lsd | 29 |
  | simwrift | Map | 31 |
  | simwrift | Overlay | 45 |
  | simwrift | MapOverlay | 24 |
  | simwrift | Emission | 16 |

- **Live**, with descriptions in the ASIM group's main gateway only:
  - the group header, the Start group dialog, the running instance and both running gateways
    showed them, ASIM-COMMS included (it has none of its own);
  - Discover for *Shared Session*, with an instance running on `TheOtherOnes`, offered
    *TheOtherOnes* first (from `TheOtherOnes-COMMS` too), and clicking it filled the field;
  - no script errors.

---

## LSD choices and your etc folder (0.9.15)

Rift (`rift/Version`, revision 15) and Junction (`junction/Version`) are both 0.9.15, and so are
`README.md`, the docs and the developer notes.

### New: LSD choices, shown and chosen at the start (Junction and Rift)

```properties
endpoint.hla.lsd=default;crc2;crc@10.0.0.5:8989        # Junction endpoint (kit or gateway file)
datasource.hla.lsd=default;crc2                         # Rift datasource configuration
```

- **A list of choices.** The first entry is the default. The others are offered when a gateway or
  datasource is started, and any other value can be typed too. Empty means the RTI's own
  default (no designator). For HLA 4 the same setting is the RTI configuration name, and the
  dialogs call it that.
- **Shown everywhere:**
  - Junction configuration cards: an `LSD default +2` chip, with the choices in the tooltip.
  - Running endpoints: the LSD they connected with, marked ✎ when it was chosen at the start.
  - Rift configuration cards: `default +2`.
  - The datasource page: the LSD the datasource connected with.
- **Chosen at the start:**
  - **Start gateway**: every HLA (and Talk) endpoint has an LSD field. It is prefilled with
    the default and has the other choices as buttons. **Discover** asks the RTI with the LSD
    in the field.
  - **Start group**: the same, for every HLA endpoint of the group's gateways. The fields are
    folded away, since most starts keep the defaults.
  - **Create datasource** in Rift: the same field. Picking a choice rescans the federations
    on that LSD.
- **Only a changed LSD is sent.** An LSD left at its default comes from the configuration, so a
  group variable in the setting (`endpoint.hla.lsd=GROUPVAR3`) still takes the start's value. A
  typed LSD may contain group variables too.
- **Inspect** opens the Rift datasource with the LSD the endpoint connects with, so a chosen LSD
  is followed there too.
- **REST:**
  - `POST /api/junction/gateways` takes `"lsds": {"<endpoint>": "<lsd>"}`.
  - `POST …/groups/{group}/start` takes `"lsds": {"<gateway config>": {"<endpoint>": "<lsd>"}}`.
  - `GET …/endpoints/{ep}/targets` and Rift's `…/autofederations` take `?lsd=`. (Rift used to
    ignore the `lsd` the page sent.)
  - `POST /api/datasources` takes `"lsd"`.
  - Configurations list `lsd` + `lsds` (Rift: `hlalsd` + `hlalsds`); running endpoints and
    datasources report the LSD they use.
  - New error `LSD_INVALID` (400): an `lsds` key that is not an HLA endpoint of the gateway, or
    an LSD over 512 characters or with control characters.
- **The setting's form.**
  - Entries are trimmed, and an entry listed twice is offered once.
  - An empty entry (`default;;crc2`, or a leading `;`) offers the RTI's default.
  - An LSD that itself contains `;` can't be listed, but can still be typed at the start.
  - A single value works exactly as before.
- **Test RTI.** It refuses the LSD `fakerti:refuse`, so the self test can prove which LSD an
  endpoint really connected with.
- Documented in `junction.md` section 4, *LSD choices*; `junction-rest-api.md`; `rest-api.md`;
  and the install guide and web UI guide.

### Your `etc` folder (2026-10-25)

Your folder is the baseline now, exactly as you sent it. Here is how I read it, and what follows
from that:

- **The layout.** `etc/junctions.dist` holds every sample, including `talk-radio`,
  `prti5-prti6` and `exercise-talk` under their real names. `etc/junctions` holds the ones you
  want in use.
- **The build check** (`build.sh`) no longer wants the two folders identical. It now checks that
  every file in `etc/junctions` is the same as its copy in `etc/junctions.dist`, and warns for
  each one that isn't. Your folder passes.
- **`junctions.dist/README.txt`** is rewritten for this: it explains that the folder has every
  sample, how to bring one into use, and how to see which samples aren't in use.
- **`junction.md` section 2** now lists the samples that are only in `junctions.dist`. It also
  mentions that a file in `etc/junctions` not ending in `.junction.settings` (e.g. `.off`) isn't
  loaded.

### Included from 0.9.14 fix 1: a 1516-2025 FDD for an HLA Evolved endpoint

The dynamic FOM failed on pRTI with *module is IEEE 1516-2025 (HLA 4) but the FOM list is being
loaded as IEEE 1516-2010*. pRTI reports the FDD in the 1516-2025 format to HLA Evolved federates
too. That is legitimate, because the format belongs to the document, not to the API.

- The FDD is now read in the format its own namespace names. The endpoint's FOM source says
  *written in the IEEE 1516-2025 format*.
- The same applies to Rift's dynamic datasources.
- The test RTI reproduces this with `-Dfakerti.fddformat=2025`, and `DynamicFomSelfTest` (now 47
  checks) covers it.

### Files (0.9.15)

- `simwcommon/.../hla/session/LsdOptions.java` (new): the list, the checks
- `simwjunction/.../config/EndpointConfig.java`: `getLsdOptions()`, JSON `lsds`
- `simwjunction/.../JunctionManager.java`: `start(…, lsds)`, `startGroup(…, lsds)`, checks,
  the effective LSD in the loop warnings
- `simwjunction/.../Gateway.java`: `effectiveLsds()`, JSON `lsd` / `lsdoverridden`
- `simwjunction/.../endpoint/EndpointFactory.java`, `endpoint/hla/HlaEndpoint.java`: connect
  with the chosen LSD
- `simwjunction/.../TargetProbe.java`, `api/JunktionApi.java`: `?lsd=`, `lsds`, `LSD_INVALID`
- `simwjunction/.../Inspector.java`: `EndpointRef.lsd`
- `simwrift/.../DatasourceConfig.java`: choices, a probe per LSD
- `simwrift/.../DatasourceManager.java`, `api/DatasourceFactory.java`,
  `api/ApiRequestEntryPoint.java`, `datasources/RiftFederateHla3/4.java`: the chosen LSD;
  `junktion/RiftJunktionHost.java`: Inspect with the endpoint's LSD
- `simwrift/rtdir/webroot/`:
  - `js/junction.js`, `junction-start.js`, `junction-group.js`, `junction-api.js`,
    `css/junction.css`;
  - `index.html`, `js/create-datasource.js`, `api.js`, `config-card.js`, `datasource.js`,
    `css/rift.css`
- `simwrift/rtdir/etc/junctions.dist/README.txt`; `build.sh` (the check)
- `fakerti/e/…/FakeRtiFactory.java`, `fakerti/v4/…/FakeRtiFactory.java`: the refused LSD (run
  `sh fakerti/build-fake.sh` again)
- `simwjunction/test/.../LsdSelfTest.java` (new, 29 checks)
- Docs: `junction.md` (2, 4, 11, 13), `junction-rest-api.md`, `rest-api.md`,
  `simweaver-rift-install-and-config.md`, `README.md` (web UI), `CLAUDE.md`, and the doc copies
  (`junction-config.md`, `rift-install-and-config.md`, `rift-rest-api.md`)

### Checked

- Your tree with this folder applied builds with `build.sh`, with no `junctions.dist` warning.
- All self tests pass, 0 failures:

  | Module | Test | Checks |
  |---|---|---|
  | simwcommon | HlaCodec | 93 |
  | simwcommon | DisRoundTrip | 112 |
  | simwcommon | RtiSession | 41 |
  | simwcommon | SimwProperties | 97 |
  | simwcommon | SimwArgs | 57 |
  | simwjunction | Bridge | 41 |
  | simwjunction | DisBridge | 84 |
  | simwjunction | TalkBridge | 75 |
  | simwjunction | Group | 40 |
  | simwjunction | IffBridge | 24 |
  | simwjunction | ValueMap | 43 |
  | simwjunction | RprConversion | 132 |
  | simwjunction | Naming | 40 |
  | simwjunction | DynamicFom | 47 |
  | simwjunction | Lsd (new) | 29 |
  | simwrift | Map | 31 |
  | simwrift | Overlay | 45 |
  | simwrift | MapOverlay | 24 |
  | simwrift | Emission | 16 |

- **Live**, on the server with the test RTI and an LSD list in a kit and in a datasource
  configuration:
  - a gateway started with `red` on `crc2` shows *LSD crc2 ✎*;
  - `fakerti:refuse` fails that start with the RTI's message;
  - Discover with `?lsd=` follows the chosen LSD;
  - a Rift datasource created with `"lsd":"crc2"` reports it;
  - the Start gateway, Start group and Create datasource dialogs render with the choices, and
    there are no script errors.

---

## FOM from the federation and junctions.dist (0.9.14)

Rift (`rift/Version`, revision 14) and Junction (`junction/Version`) are both 0.9.14, and so are
`README.md`, the docs and the developer notes.

### New: FOM from the federation (`dynamic`)

An HLA endpoint (HLA Evolved, HLA 4, also over the Federate Protocol) can take its FOM from the
federation instead of from files:

```properties
endpoint.standard=ieee1516-2010
endpoint.hla.foms=dynamic
endpoint.hla.lsd=default
```

- **How it works.**
  - The endpoint joins without FOM modules.
  - It reads the federation's current FDD from the MOM: attribute `HLAcurrentFDD` of the
    `HLAobjectRoot.HLAmanager.HLAfederation` object, the merged FOM as text.
  - It builds its model from that text.
  - The gateway then bridges whatever FOM the federation runs: RPR, NETN, your proprietary
    extensions, custom FOMs. No module copies are needed on the gateway side.
- **The federation must exist.** A dynamic endpoint never creates one, since it has no FOM to
  create it with. When the federation is missing, the start fails and the message says why.
- **The FDD is read once, at the start.** If a federate later joins with more modules, the
  endpoint notices that the FDD changed. The page then shows *The federation's FDD has changed …*
  on the endpoint, and the API reports it as `fomnotice`. The gateway keeps routing with the
  model it started with; restart it to pick up the new classes.
- **RPR v2 or v3 is recognised from the classes** in the FDD, so v2 ⇄ v3 conversion works with
  dynamic endpoints. This also applies to FOM files whose names don't say which RPR they are.
  NETN is recognised the same way.
- **On the page:**
  - the endpoint shows a *FOM: FEDERATION* chip, and its FOM source reads *from the federation:
    FDD 7a6e65c9531f, RPR v2 recognised from its classes*;
  - configuration cards show *DYNAMIC FOM*;
  - **Compatibility** can't compile a dynamic endpoint before it has joined, and says so;
  - **Inspect** of a dynamic endpoint opens a Rift datasource that also takes its FOM from the
    federation.
- **Settings:**

  | Key | Default | Meaning |
  |---|---|---|
  | `endpoint.hla.foms` | | `dynamic` |
  | `endpoint.hla.fddtimeoutseconds` | `15` | How long to wait for the FDD after the join |
  | `endpoint.hla.fddclass` / `fddattribute` | `HLAobjectRoot.HLAmanager.HLAfederation` / `HLAcurrentFDD` | For an RTI that names them differently |

- **Rift datasources** can do the same: `datasource.hla.foms = dynamic` (HLA Evolved and HLA 4),
  with `datasource.hla.fddtimeoutseconds`. `GET /api/datasourceconfigs` shows `hladynamicfom`.
- **Samples** (new in `etc/junctions`):
  - `endpoints/hla-evolved-dynamic.endpoint.settings`: HLA Evolved, federation `Blue`;
  - `endpoints/hla4-dynamic.endpoint.settings`: HLA 4, federation `Green`;
  - `hla-hla-dynamic.junction.settings`: HLA Evolved ⇄ HLA Evolved, `Blue` ⇄ `Red`, both
    endpoints dynamic, router `rpr-all`.
- **Please verify against pRTI 5 / 6.** The test RTIs give `HLAcurrentFDD` the way the standard
  describes it, but the details have only been tested against them:
  - that the value comes on request after the join;
  - that it is the merged FDD;
  - that it is updated when a federate joins with more modules (the notice).

  `fddclass` / `fddattribute` are there in case an RTI names them differently.
- Documented in `junction.md` section 4, *FOM from the federation*, and in the Rift install
  guide, section 2.2.

### New: `etc/junctions.dist`, the original samples

`etc/junctions.dist/` is an untouched copy of the delivered `etc/junctions`: gateways,
`endpoints/`, `routers/` and `mappings/`, plus a `README.txt` that says what the folder is for.

- The server never reads it. Only `etc/junctions` is used.
- To get a sample back, copy it from there. To see what you changed in your own folder:
  `diff -r etc/junctions.dist etc/junctions`.
- **Upgrading:** replace `junctions.dist` with the new version's and keep your `etc/junctions`.
  Comparing the old and the new `junctions.dist` shows what changed in the samples. The install
  guide now has a short *Upgrading* note.
- In the source tree the two folders must stay identical. `build.sh` warns when they differ, and
  prints the command that refreshes `junctions.dist`. The IntelliJ build doesn't check this, so
  when you change a sample, change it in both places, or run `build.sh` once to see the
  warning.

### Fixed in Rift: a dynamic datasource's MOM object

Found in the live check. After reading the FDD, a dynamic datasource handed the MOM federation
object to its object list. The model has no MOM classes, so that ended in a
`NullPointerException` in the log (*the federation object could not be listed*). The datasource
kept working, but the log line was wrong.

- The datasource now unsubscribes the MOM class when its model doesn't list it.
- A discovery of a class the datasource doesn't list is now ignored rather than failing.

### Files (0.9.14)

- `simwcommon/.../hla/session/DynamicFom.java` (new): decoding `HLAunicodeString`, building the
  model from FDD text, and the `Reader` over an `RtiSession`
- `simwcommon/.../hla/session/impl1516e/Rti1516eSession.java`,
  `impl2025/Rti2025Session.java`: joining without modules
- `simwcommon/.../iem/InformationExchangeModel.java`: RPR / NETN recognised from content
- `simwjunction/.../config/EndpointConfig.java`: the settings and their checks
- `simwjunction/.../endpoint/hla/HlaEndpoint.java`: reading and watching the FDD, the notice,
  the FOM source
- `simwjunction/.../Gateway.java`, `TargetProbe.java`, `api/JunktionApi.java`: the API fields,
  probing, compatibility
- `simwrift/.../DatasourceConfig.java`, `datasources/RiftFederateHla3.java`, `RiftFederateHla4.java`,
  `junktion/RiftJunktionHost.java`: dynamic datasources, and Inspect
- `simwrift/rtdir/webroot/js/junction.js`, `css/junction.css`: the chips and the notice
- `simwrift/rtdir/etc/junctions/`: the three samples above; `etc/junctions.dist/` (new)
- `fakerti/`: the test RTIs keep a merged FDD per federation (`core/Fdd.java`, new), publish it
  as `HLAcurrentFDD` on the MOM federation object, and push a new value when a join adds modules
  (run `sh fakerti/build-fake.sh` again before the self tests)
- `simwjunction/test/.../DynamicFomSelfTest.java` (new, 31 checks)
- `build.sh`: the `junctions.dist` check
- Docs: `junction.md` (sections 2, 4, 12, 13), `junction-rest-api.md`, `rest-api.md`,
  `simweaver-rift-install-and-config.md` (1, 2.1, 2.2), `README.md`, `CLAUDE.md`. Your
  `doc/` folder also has `junction-config.md`, `rift-install-and-config.md` and
  `rift-rest-api.md`, which are copies of `junction.md`, `simweaver-rift-install-and-config.md`
  and `rest-api.md`. They are updated to match.

### Checked

- Your tree with this folder applied builds with `build.sh`, with no `junctions.dist` warning.
- All self tests pass, 0 failures:

  | Module | Test | Checks |
  |---|---|---|
  | simwcommon | HlaCodec | 93 |
  | simwcommon | DisRoundTrip | 112 |
  | simwcommon | RtiSession | 41 |
  | simwcommon | SimwProperties | 97 |
  | simwcommon | SimwArgs | 57 |
  | simwjunction | Bridge | 41 |
  | simwjunction | DisBridge | 84 |
  | simwjunction | TalkBridge | 75 |
  | simwjunction | Group | 40 |
  | simwjunction | IffBridge | 24 |
  | simwjunction | ValueMap | 43 |
  | simwjunction | RprConversion | 132 |
  | simwjunction | Naming | 40 |
  | simwjunction | DynamicFom (new) | 31 |
  | simwrift | Map | 31 |
  | simwrift | Overlay | 45 |
  | simwrift | MapOverlay | 24 |
  | simwrift | Emission | 16 |

- **Live**, on the server with the test RTI:
  - a file-FOM gateway created `Blue` and `Red`;
  - `hla-hla-dynamic` started; both endpoints took the FDD (RPR v2 recognised from its classes);
  - the file-FOM gateway was stopped, and `hla-dis6` was started on `Blue`;
  - DIS Entity State PDUs (`VIPER11`) went DIS → `Blue` → the dynamic gateway → `Red`;
  - **Inspect** on the dynamic `red` endpoint created a dynamic datasource, which listed the
    Aircraft with every attribute decoded.

  A note from that check: two gateways that bridge the same two federations copy each other's
  copies back and forth without end (`1-2-77@blue@blue@…`). The start reply of the second one
  already carries a `warning` that another running gateway uses the same target. Take it
  seriously when you see it.

---

## Name templates and the subscription fix (0.9.13)

Rift (`rift/Version`, revision 13) and Junction (`junction/Version`) are both 0.9.13, and so are
`README.md`, `doc/junction.md`, `doc/junction-rest-api.md` and the developer notes.

### New: name templates for copies

A router can now give the copies it registers names from a template, for every destination or
only toward one endpoint, and cut them to a maximum length.

```properties
# copies in endpoint ext only: RED-<name>-<GUID>, at most 32 characters
router.names.template.ext=RED-{name}-{hexguid}
router.names.maxlength.ext=32
```

In a gateway file the keys are written `junction.router.names.template.ext=...`.

- **Tokens:**

  | Token | Becomes |
  |---|---|
  | `{name}` | The original instance name |
  | `{hexguid}` | A GUID of the object, 32 lower-case hex digits |
  | `{HEXGUID}` | The same in upper case |
  | `{guid}` | The same as `8-4-4-4-12` |
  | `{origin}` | The id of the source endpoint |

  Anything else in the template is kept as it is.
- **One direction.** `router.names.template.<endpoint>` applies only to copies registered in
  that endpoint, and wins over `router.names.template` (all destinations).
  - A destination without a template keeps the name policy, so here `int` keeps the original
    names.
  - An empty value cancels the general template for that endpoint.
- **The GUID** is derived from the gateway (id and instance), the source endpoint and the
  object's name, not drawn at random.
  - A copy therefore gets the same name after a reconnect, or after the gateway is stopped and
    started, so references held on the other side stay valid.
  - Two instances of one gateway give different GUIDs.
- **Maximum length** (`router.names.maxlength[.<endpoint>]`) is counted in characters.
  - When a name is taken, `#2`..`#4` still fit inside the limit: the name is cut shorter to
    make room.
  - The limit applies to the name policy's names too, keeping their `@origin#n`.
  - `0` means no limit.
- **References need no settings and work both ways.** Every `RTIobjectId` in routed attributes
  and parameters is rewritten: simple values, record fields, array and variant elements, at any
  depth. A Detonation in `ext` aimed at `RED-Tank-12-…` arrives in `int` aimed at `Tank-12`. Fire
  from `Tank-12` in `int` arrives in `ext` with the copy's name as the shooter, also in
  `EventIdentifier.IssuingObjectIdentifier`.
- **Mistakes:**
  - An unknown token, a lone `{` or `}`, or a length that is not a whole number stops the
    gateway from starting, with the reason on the page.
  - A template with neither `{name}` nor a GUID token only warns, and so does a template keyed
    to a DIS endpoint (DIS uses entity ids) or to an endpoint the gateway doesn't have.
- Documented in `junction.md` section 5, *Naming copies*.

### Fixed: objects that exist before a gateway starts got the wrong class

Writing the restart test for the templates turned up a bug that also affects gateways without
templates.

- **The bug.** An RTI discovers an object that already exists using the first class the
  federate subscribes to that fits it, and that "known class" never changes afterwards. The
  gateway subscribed parents before children, BaseEntity before Aircraft. So every object
  already in a federation when the gateway started was known to the gateway as a BaseEntity,
  and its copy was registered as a BaseEntity. A federate subscribed to Aircraft never saw it.
- **Who it affected.** Objects registered *after* the gateway started were fine. The typical
  case is a gateway started after the federates are running, or stopped and started again
  during an exercise.
- **The fix.** The gateway now subscribes the most specific classes first, as Rift's
  datasources already did (they were never affected).
- **Covered by** `NamingSelfTest`'s restart check: objects that existed before the restart
  reach a subscriber of their own class.

### Your configurations

- `endpoints/ASIM-INT-talk.endpoint.settings` and `endpoints/ASIM-EXT-talk.endpoint.settings`
  now set `endpoint.hla.lsd=default` explicitly. The two HLA kits already did.
- Everything else in `etc/junctions` is exactly as you sent it.

### Files (0.9.13)

- `simwjunction/.../router/CopyNames.java` (new): templates, tokens, cutting
- `simwjunction/.../router/IemRouter.java`: copy names from the template or the policy, the
  GUID
- `simwjunction/.../config/RouterConfig.java`: the settings and their checks
- `simwjunction/.../config/GatewayConfig.java`: the warnings
- `simwjunction/.../endpoint/hla/HlaEndpoint.java`: subscriptions most specific first
- `simwjunction/test/.../NamingSelfTest.java` (new, 40 checks). It covers:
  - the tokens and cutting, counting characters;
  - the settings and their mistakes;
  - a two-federation HLA Evolved gateway renaming only toward `ext`;
  - references in an attribute, an array, a record and interactions, both ways;
  - the same names after a restart.
- Docs: `junction.md` sections 5, 12 and 13; `CLAUDE.md`

### Checked

- Your tree with this folder applied builds with `build.sh`.
- All self tests pass, 0 failures:

  | Module | Test | Checks |
  |---|---|---|
  | simwcommon | HlaCodec | 93 |
  | simwcommon | DisRoundTrip | 112 |
  | simwcommon | RtiSession | 41 |
  | simwcommon | SimwProperties | 97 |
  | simwcommon | SimwArgs | 57 |
  | simwjunction | Bridge | 41 |
  | simwjunction | DisBridge | 84 |
  | simwjunction | TalkBridge | 75 |
  | simwjunction | Group | 40 |
  | simwjunction | IffBridge | 24 |
  | simwjunction | ValueMap | 43 |
  | simwjunction | RprConversion | 132 |
  | simwjunction | Naming (new) | 40 |
  | simwrift | Map | 31 |
  | simwrift | Overlay | 45 |
  | simwrift | MapOverlay | 24 |
  | simwrift | Emission | 16 |

---

## Instances of groups and gateways (0.9.12)

A group can now run several times at once, usually with other variable values (one exercise per
instance). Each start of a group is an **instance** with a name of its own. Single gateways
work the same way.

**Starting**

- The Start group dialog, and a gateway's Start dialog, have an **Instance** field. It is
  prefilled with the next free number and lists the instances that run.
- Left empty, the name is the lowest free number: `1`, `2`, …
- A name has at most 40 letters, digits, spaces, `_`, `-` or `.`; `Övning A` is fine.
- **One name runs once.** Starting an instance that already runs is refused with 409
  `INSTANCE_RUNNING`, and nothing is started. The dialog says so before you press Start.
- **Completing an instance.** A single start only refuses a running instance of *its own*
  configuration. So when one gateway of an instance failed or was removed, start it on its own
  with the same instance name and values.

**Monitoring and stopping**

- **One row per running instance** in the group block, with:
  - its name and the values it was started with;
  - `2/2 RUNNING`, in red when the instance is incomplete;
  - its own **■ Stop**.
- **■ Stop all** stops every instance. While only one instance runs, the button reads
  **■ Stop group** as before.
- **Running gateways** are listed by group and instance, and each is labelled
  `GROUP GX · INSTANCE 2 · GROUPVAR1=…`. A groupless gateway shows `INSTANCE n` when it runs
  more than once or has a name other than `1`.

**Under the hood**

- **Object names per instance.** The objects a gateway registers itself are named after its
  instance, except for instance `1`, so single starts keep today's names. Pitch Talk channels,
  clients and radios become `Junction-<gateway>-<instance>-…`, so two instances in one Talk
  federation don't collide.
- Log lines, thread names and Inspect datasource names include the instance as well.
- **Simultaneous starts.** Two starts at the same moment can't take the same name: each start
  reserves its name until it is done.

**REST** (`doc/junction-rest-api.md`, sections 3 and 4.1)

- `POST /junction/groups/{group}/start` takes `"instance"`. The reply names the instance it
  used, and each result has `"instance"`.
- `POST /junction/groups/{group}/stop` stops one instance with `{"instance":"Night"}` or
  `?instance=Night`. It answers 404 `INSTANCE_NOT_FOUND` when that instance doesn't run.
  Without an instance it stops every instance, as before.
- `POST /junction/gateways` takes `"instance"`.
- `GET /junction/groups` and the groups in `GET /junction/configs` add:
  - `instances`: name, running count, `complete`, `vars`, and the gateways with their states;
  - `nextinstance`.
- Every configuration in `GET /junction/configs` lists its running `instances` and its
  `nextinstance`.
- Every gateway has `"instance"`.
- `GET /junction/gateways` is sorted: grouped gateways first, by group, instance and
  configuration.
- New errors:
  - `INSTANCE_RUNNING` (409)
  - `INSTANCE_INVALID` (400)
  - `INSTANCE_NOT_FOUND` (404)

**Files**

- `simwjunction/.../Gateway.java`: instance, `runtimeName()`
- `simwjunction/.../JunctionManager.java`: instances, reservations, stop per instance,
  listings, order
- `simwjunction/.../api/JunktionApi.java`: the routes
- `simwrift/rtdir/webroot/`: `junction.html`, `js/junction.js`, `js/junction-group.js`,
  `js/junction-start.js`, `js/junction-api.js`, `css/junction.css`
- `GroupSelfTest`: 40 checks (was 22). The new checks cover:
  - auto-numbered and named instances;
  - the refusals;
  - the listings and their order;
  - stop per instance, and a freed number being reused;
  - completing an instance;
  - a single gateway started twice.
- Docs: `junction.md` sections 7, 11 and 13; `junction-rest-api.md`; `CLAUDE.md`

### Also in 0.9.12

- **A DIS entity whose name is taken in HLA.** `DisBridgeSelfTest` has a new scenario, now 84
  checks (was 75), and it confirms what you asked about:
  - an HLA federate holds the name `1-2-3`;
  - the DIS entity `1-2-3` reaches HLA as `1-2-3@dis`;
  - a Fire PDU from it carries `FiringObjectIdentifier = 1-2-3@dis`, not the HLA object's
    name;
  - fire from HLA at `1-2-3@dis` goes to DIS as target `1-2-3`.
  The code needed no changes.
- **`endpoint.hla.lsd`.** `junction.md` had this as "class path RTI" only. It works with any RTI
  library (class path or `rtijar`). On HLA 4 it is the `RtiConfiguration`'s configuration name.
- **`README.md`** for the top of the tree, with your wording on line 5.
- **`build.sh`** now also packs `out/artifacts/simweaverrift/simweaverrift.jar`, the same jar
  as your IntelliJ artifact.
- **`simwrift/rtdir/LICENSE`**: a copy of your LICENSE for the installed folder.


---

## The 0.9.11 sync (unchanged since you received it)

- **Self tests** in `test/` next to each module's `src/`, with the package `com.simweaver.junction`.
  They read the FOMs and mappings from `simwrift/rtdir/etc` by default, so they need no
  arguments.
- **`build.sh`** for the new layout.
- **`fakerti/`**: the in-memory test RTIs. Build them with `sh fakerti/build-fake.sh`.
- **`doc/junction.md`**: the test class names in section 13 are restored; the IDE rename had
  turned them into `junction`.
