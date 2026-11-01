# SimWeaver Junction — REST API

The Junction API lives under Rift's API, with Rift's transport, security and error envelope (see [rest-api.md](rest-api.md)):

```
https://<server>:<port>/api/junction/...
```

The 0.9.0 path `/api/junktion/...` is still answered the same way. The SSE topics are now `junction` and
`junction:<gid>` (0.9.0 used `junktion`); a client that subscribes to the old topic name gets no events.

Requests and replies are JSON (UTF-8). Errors use a real HTTP status and the envelope `{"success":false,"error":{"code":"...","message":"..."}}`. Live updates come over Rift's SSE stream (section 7).

With API authentication on (0.11.0) every call needs `Authorization: Bearer <token>`, as all of `/api` does ([rest-api.md](rest-api.md), 14).

API state: Junction 0.11.4, November 2026.

---

## 1. Endpoint summary

| Method | Path | Body | Reply |
|---|---|---|---|
| GET | `/junction` | | `{"name","version"}` |
| GET | `/junction/configs` | | Configurations, groups and kits (2.1) |
| GET | `/junction/configs/{cfgid}` | | `{"config":{...}}` |
| GET | `/junction/configs/{cfgid}/compatibility` | | Route tables from the FOMs, without connecting (2.3) |
| GET | `/junction/configs/{cfgid}/endpoints/{epid}/targets` | `?federation=` for Talk channels, `?lsd=` for another LSD | Federations / exercises / channels (2.4) |
| GET | `/junction/groups` | | `{"groups":[...]}`, with their running instances (3) |
| POST | `/junction/groups/{group}/start` | `{"vars":{"GROUPVAR1":".."},"instance":"..","lsds":{"cfg":{"ep":".."}}}` | 201 all started / 207 some (3) |
| POST | `/junction/groups/{group}/stop` | `{"instance":".."}`, optional | `{"group","instance"?,"stopped":n}` (3) |
| GET | `/junction/gateways` | | `{"gateways":[gateway...]}`: grouped ones first, by group, instance and configuration (4) |
| POST | `/junction/gateways` | `{"configid","targets":{},"vars":{},"instance"?,"lsds"?:{}}` | 201 `{"success":true,"gateway",...,"warning"?}` (4.1) |
| GET | `/junction/gateways/{gid}` | | `{"gateway":{...}}` |
| GET | `/junction/gateways/{gid}/compatibility` | | `{"routes":[...]}` |
| DELETE | `/junction/gateways/{gid}` | | 204: stopped and removed |
| POST | `/junction/gateways/{gid}/endpoints/{epid}/inspect` | | 201 created / 200 existing: `{"success":true,"inspect":{"datasource","page","created"}}` (5) |

---

## 2. Configurations

### 2.1 `GET /junction/configs`

```json
{
  "gateways": [ <config> ... ],
  "groups": [ {"group":"Exercise","gateways":["ex-alpha","ex-bravo"],
               "variables":[{"name":"GROUPVAR1","description":"Exercise federation"},{"name":"GROUPVAR2","default":"Bravo-red"}],
               "running":2, "nextinstance":"2",
               "instances":[ {"instance":"1","running":2,"complete":true,
                              "vars":{"GROUPVAR1":"FederationX","GROUPVAR2":"Bravo-red"},
                              "gateways":[{"id":"9bfb...","configid":"ex-alpha","name":"Exercise alpha","state":"RUNNING"},
                                          {"id":"41c0...","configid":"ex-bravo","name":"Exercise bravo","state":"RUNNING"}]} ]} ],
  "endpointkits": [ <endpoint> ... ],
  "routerkits": [ <router> ... ],
  "problems": [ "cannot read etc/junctions/x.junction.settings: ..." ],
  "directory": "etc/junctions"
}
```

### 2.2 A configuration

```json
{
  "id": "ex-bravo", "name": "Exercise bravo", "description": "", "file": "ex-bravo.junction.settings",
  "valid": true,
  "endpoints": [ {
      "id": "blue", "name": "Blue (test RTI)", "source": "endpoints/blue-test.endpoint.settings",
      "standard": "ieee1516-2010", "standardlabel": "HLA Evolved (IEEE 1516-2010)",
      "ishla": true, "isdis": false, "istalk": false,
      "defaulttarget": "GROUPVAR1-blue",
      "hlaversion": "IEEE 1516-2010", "lsd": "default", "lsds": ["default", "crc2"], "foms": ["RPR_FOM_v2.0_Link16_1516-2010.xml"],
      "federatetype": "SimwJunction", "createfederation": true, "rtijar": "lib/rti/fakerti-1516e.jar",
      "errors": [] } ],
  "router": { "id": "ex-bravo", "source": "routers/all.router.settings", "kind": "iem",
      "filter": {"objectsallow":"*","objectsblock":"","interactionsallow":"*","interactionsblock":""},
      "filtertext": "objects allow * interactions allow *", "promote": true, "namepolicy": "same-or-suffix",
      "valuemap": [ {"datatype":"EntityTypeStruct","from":"1.2.225.1.3.0.0","to":"1.2.222.1.3.0.0",
                     "key":"router.valuemap.EntityType.f16"} ],
      "provideforward": true, "provideforwardintervalms": 1000,
      "settings": "router.kind=iem\n...", "warnings": [], "errors": [] },
  "autostart": false,
  "errors": [], "warnings": [],
  "group": "Exercise",
  "variables": [ {"name":"GROUPVAR1","description":"Exercise federation"}, {"name":"GROUPVAR2","default":"Bravo-red","value":"Bravo-red"} ],
  "unresolved": ["GROUPVAR1"],
  "variableerrors": [],
  "availability": { "blue": {"available": true}, "red": {"available": false, "reason": "..."} },
  "running": 0
}
```

| Field | Meaning |
|---|---|
| `valid` | Can be started. Missing variable values do not make it invalid (they are asked for at the start); errors caused only by them are in `variableerrors`. |
| `variables` | The group variables the settings use, with their `default` (`junction.groupvar.*`), the `value` used for this listing (the default), and the `description` of this file (`junction.groupvar.GROUPVARn.desc`) when it has one. A group's `variables` (2.1) carry the description from any gateway of the group, the first by file name. Descriptions are labels only: `vars` are always keyed by `GROUPVARn`. |
| `unresolved` | Variables without a default: a start must give them. |
| `availability` | Per endpoint: is its RTI library there (checked without connecting). |
| `running` | Running gateways of this configuration. |

HLA endpoints have `lsd`, the default LSD (the first entry of `endpoint.hla.lsd`; `""` = the RTI's default), and `lsds`, every choice with the default first.

An HLA endpoint with `endpoint.hla.foms=dynamic` (FOM from the federation) has `"foms": ["dynamic"]` and `"dynamicfom": true`; its `compatibility` (2.3) cannot be compiled before the gateway runs and is reported as an error saying so.

An endpoint whose target setting contains group variables also has `targettemplate`, the setting as written (`"GROUPVAR2-COMMS"`), while `defaulttarget` has the variables replaced by their defaults. The page's Discover for variables uses it.

DIS endpoints have `disinterface`, `disport`, `disexid`, `dissiteid`, `disappid`, `disentityids`, `dissendaddress`. C-DIS endpoints (`"standard": "cdis"`, also `"isdis": true`) add `"iscdis": true` and `"cdis": {"updatemode":"full","fullupdatemultiplier":2.4,"timeoutmultiplier":2.4,"heartbeats":{"entitystate":5.0,"emission":10.0,"designator":5.0,"transmitter":2.0,"iff":10.0}}` (seconds). A PDU relay router has `"kind": "pdu"` and `"pdutypes"` (empty = all). J2J endpoints (0.10.0, `"standard": "j2j"`) add `"isj2j": true`, `j2jsession` (also `defaulttarget`), `j2jdis`, `j2jexid` and `j2jtransport` (`auto` / `reliable`). Talk routers have `talkmode` (`full` / `messages`), `talktrackchannels`, `talkmessages`, `talkmatch`, `talkchannels`, `talkmirrorclients`, `talkradiomap`, `talkradioendpoint`, `talkradioentityid`, `talksamplerate`, `talkchannelmap`, `talkchanneluuidmap`.

### 2.3 Compatibility

`GET /junction/configs/{cfgid}/compatibility` loads the FOMs and compiles the routes for every ordered pair of endpoints, without connecting:

```json
{ "config": "rpr2-rpr3",
  "routes": [ { "from": "v2", "to": "v3", "conversion": "RPR v2.0 -> v3.0 (rpr2-rpr3.mapping)",
      "report": { "objectclasses": 63, "interactionclasses": 88,
                  "members": {"pass": 1061, "rewritereferences": 0, "transcode": 467, "dropped": 486, "valuerules": 0},
                  "blockedcount": 0, "blocked": [], "nocounterpartcount": 0, "nocounterpart": [],
                  "promoted": ["HLAobjectRoot.EmbeddedSystem.IFF.NatoIFF -> ..."],
                  "droppedmembers": {"HLAobjectRoot...Aircraft.HeadLightsOn": "no attribute HeadLightsOn on ..."},
                  "transcodedmembers": {"HLAobjectRoot.BaseEntity.Spatial": "SpatialVariantStruct -> SpatialVariantStruct"},
                  "valuerulemembers": {} } } ],
  "errors": { "v3": "FOM module not found: ..." } }
```

Lists are capped at 300 entries; the counts are complete. `members.valuerules` and `valuerulemembers` (member → datatype) are the members the router's value rules (`router.valuemap.*`) apply to; a rule that does not fit an endpoint's FOM appears in `errors` under that endpoint.

### 2.4 Targets

`GET /junction/configs/{cfgid}/endpoints/{epid}/targets` asks the endpoint's RTI for its federations, listens 1.5 s on a DIS network, or (Talk, with `?federation=`) joins briefly and lists the channels; a J2J endpoint lists the sessions of the J2J network:

```json
{"endpoint":"blue","result":{"kind":"federation","targets":["Blue","FederationX-blue"]},"default":"Blue"}
{"endpoint":"dis","result":{"kind":"exercise","targets":[{"id":"1","pdus":412}]},"default":"1"}
{"endpoint":"site1","result":{"kind":"channel","targets":[{"id":"Battalion","type":"VoiceChannel"}]},"default":"TalkSite1"}
{"endpoint":"j2j","result":{"kind":"session","targets":[{"id":"exercise-pdu","model":"DIS 7, exercise 1","members":3}]},"default":"exercise-pdu"}
```

`result.error` is set when the probe failed (RTI missing, port in use, …).

`?lsd=crc2` asks the RTI with that LSD instead of the endpoint's default, as the Start dialog does when another LSD is chosen. An LSD longer than 512 characters or with control characters gives 400 `BAD_REQUEST`.

---

## 3. Groups

`GET /junction/groups` returns the `groups` list of 2.1.

- `instances` are the running instances of the group, numbers first in numeric order, then the other names alphabetically.
- `complete` is false when fewer gateways of the instance run than the group has.
- `vars` are the values the instance was started with.
- `nextinstance` is the name a start without `instance` gets.

`POST /junction/groups/{group}/start` with `{"vars":{"GROUPVAR1":"FederationX"},"instance":"Night"}` starts every gateway of the group as one instance, one after the other, with those values on top of each file's defaults:

```json
{ "success": true, "allstarted": true, "group": "Exercise", "instance": "Night", "started": 2, "total": 2,
  "results": [ {"configid":"ex-alpha","instance":"Night","success":true,"gateway":{...}},
               {"configid":"ex-bravo","instance":"Night","success":false,"error":{"code":"TARGET_CONFLICT","message":"..."}} ] }
```

- `instance` is optional. Without it the instance is the lowest free number (`1`, `2`, …).
- `lsds` is optional: `{"ex-alpha": {"hla": "crc2"}}` gives the HLA endpoint `hla` of gateway `ex-alpha` that LSD, as `lsds` does for one gateway (4.1). Gateways and endpoints left out use their default. A configuration id that is not in the group gives 400 `LSD_INVALID` and starts nothing; a bad LSD fails that gateway only.
- The status is 201 when all started and 207 when some did. When none started, the reply is 400 `GROUP_START_FAILED`, with each gateway's reason in the message.

| Error | Status | When |
|---|---|---|
| `GROUP_NOT_FOUND` | 404 | No configuration is in the group |
| `INSTANCE_RUNNING` | 409 | A gateway of the group runs as that instance; nothing was started |
| `INSTANCE_INVALID` | 400 | Longer than 40, or other than letters, digits, spaces, `_`, `-`, `.` |

`POST /junction/groups/{group}/stop` stops the group's running gateways, every instance: `{"group":"Exercise","stopped":4}`. With `{"instance":"Night"}` (or `?instance=Night`) it stops that instance only: `{"group":"Exercise","instance":"Night","stopped":2}`, or 404 `INSTANCE_NOT_FOUND` when no gateway of the group runs as that instance.

---

## 4. Gateways

### 4.1 Start

```json
POST /junction/gateways
{ "configid": "hla3-dis7",
  "targets": { "hla": "Blue", "dis": "3" },
  "vars": { "GROUPVAR1": "FederationX" },
  "lsds": { "hla": "crc2" } }
```

`targets`, `vars`, `instance` and `lsds` are optional.

- `targets` gives an endpoint's federation (HLA, Talk), exercise id (DIS, 0-255) or session (J2J). Missing ones use the configuration's default, and a target may contain group variable tokens.
- The call returns when every endpoint has joined or opened, so HLA joins can take a while.
- The reply includes `warning` when another running gateway already uses one of the targets.
- `lsds` gives an HLA endpoint the LSD (1516-2025: the RTI configuration name) to connect with: one of the configured choices (`endpoint.hla.lsd=default;crc2`) or any other text, `""` for the RTI's default. Missing endpoints use the configured default, the first choice. Group variable tokens in it are replaced. Send only what differs from the default, so a group variable in the setting keeps working.
- `instance` names this start, so one configuration can run several times; without it, it is the lowest free number. A gateway of a group started on its own with an instance name becomes part of that group instance, e.g. to complete it.

| Error | Status | When |
|---|---|---|
| `BAD_REQUEST` | 400 | Malformed body |
| `CONFIG_NOT_FOUND` | 404 | No such configuration |
| `CONFIG_INVALID` | 400 | Configuration errors; unknown endpoint in `targets`; a `vars` key that is not `GROUPVARn` |
| `VARIABLES_REQUIRED` | 400 | A variable the settings use has no value |
| `TARGET_CONFLICT` | 409 | Two endpoints of the gateway on the same federation / DIS network and exercise |
| `INSTANCE_RUNNING` | 409 | This configuration already runs as that instance |
| `INSTANCE_INVALID` | 400 | Not a usable instance name |
| `LSD_INVALID` | 400 | An `lsds` key that is not an HLA endpoint of the gateway; an LSD longer than 512 characters or with control characters |
| `RTI_NOT_AVAILABLE` | 503 | An endpoint's RTI library is missing |
| `CONNECT_FAILED`, `FOM_INVALID`, … | 500 | Joining or opening failed |

### 4.2 A gateway

```json
{ "id": "cab6a810-...", "configid": "test-blue-red", "name": "Test Blue - Red",
  "state": "RUNNING", "message": null, "created": "2026-10-21T18:05:29.772Z",
  "router": "iem", "group": "", "instance": "1", "vars": {}, "vardescriptions": {},
  "endpoints": [ { "id": "blue", "name": "Blue (test RTI)", "standard": "ieee1516-2010",
      "standardlabel": "HLA Evolved (IEEE 1516-2010)", "target": "Blue", "state": "JOINED",
      "library": "/.../lib/rti/fakerti-1516e.jar", "federatename": "SimwJunction-105",
      "lsd": "crc2", "lsdoverridden": true,
      "fom": "RPR_FOM_v2.0_Link16_1516-2010.xml", "dynamicfom": false,
      "flowingin": false, "flowingout": false,
      "inspectdatasource": "8b0c...",
      "stats": { "in":  {"messages":0,"discovered":0,"updates":0,"removed":0,"interactions":0,"bytes":0,"ignored":0,"rate":0.0,"bytesrate":0.0,"last":0},
                 "out": {"messages":0,"registered":0,"updates":0,"deleted":0,"interactions":0,"bytes":0,"rate":0.0,"bytesrate":0.0,"last":0},
                 "senderrors":0, "registrationfailures":0, "reconnects":0 } } ],
  "objects": { "bridged": 0, "copies": 0, "pending": 0, "failed": 0 },
  "routerstats": { "updatesrouted":0, "interactionsrouted":0, "providesanswered":0, "providesforwarded":0, "transcoded":0,
      "referencesrewritten":0, "unresolvedreferences":0, "renamed":0, "valuerules":0, "valuesreplaced":0,
      "queuedrops":0, "queuehighwater":0,
      "unknownobjects":0, "conversionfailures":0, "conversionwarnings":0, "problems":[] },
  "queue": 0 }
```

| Field | Values |
|---|---|
| `vardescriptions` | The descriptions this gateway's own file gives for the variables in `vars`, e.g. `{"GROUPVAR1":"Exercise federation"}`. The page also uses the group's (2.1) |
| `state` | `STARTING`, `RUNNING`, `DEGRADED` (an endpoint lost, reconnecting), `STOPPING`, `STOPPED`, `FAILED` |
| endpoint `state` | `NEW`, `CONNECTING`, `JOINED`, `LOST`, `RECONNECTING`, `FAILED`, `CLOSED` |
| `rate` / `bytesrate` | Messages / octets per second, over the last seconds |
| `flowingin` / `flowingout` | Traffic in the last 5 s |
| `last` | Epoch milliseconds of the last message, 0 = none |
| `lsd` | HLA endpoints: the LSD it connects with (`""` = the RTI's default) |
| `lsdoverridden` | HLA endpoints: the LSD was chosen at the start and is not the configured default |
| `fom` | HLA endpoints: the FOM files, or for a dynamic endpoint where its FOM came from, e.g. `from the federation: FDD 7a6e65c9531f, RPR v2 recognised from its classes` (`from the federation (not read yet)` before the join) |
| `dynamicfom` | HLA endpoints: the FOM is taken from the federation |
| `providesanswered` / `providesforwarded` | Provide requests answered from the gateway's cache / passed on to the object's origin because the cache had no value of some requested attribute (0.9.19; at most once per attribute and object per `router.provide.forwardintervalms`, never to a DIS origin) |
| `fomnotice` | Only when there is something to say, e.g. a dynamic endpoint's federation FDD changed after the start (a federate joined with more modules): restart the gateway to take in the new classes |

J2J endpoints (0.10.0) have `j2j`: `session`, `model` (e.g. `"DIS 7, exercise 1"`), `transport`, `members` (the other members: `node`, `gateway`, `instance`, `endpoint`, `model`, `state` `active` / `lost` / `mismatch`, `statemessage`, `local`, `since`), `sent`, `received`, `copies` (sent to other servers), `notsent` (no route, or the reliable buffer full) and `loopsdropped` (received from the session shortly before, not sent back). A J2J endpoint whose server does not run J2J fails to open with `CONNECT_FAILED`.

DIS and C-DIS endpoints (0.9.18) also have `dis`: `disversion`, `exercise`, `sendexercise`, `destination`, `echoes` (own PDUs dropped), `otherexercise`, `malformed`, the objects heard and sent; with the PDU relay `relayedin` / `relayedout`. A C-DIS endpoint adds `"protocol": "C-DIS"`, `cdissettings` (as `cdis` in 2.2), `cdisobjects` (objects the decoder keeps state for) and the codec's counters:

```json
"cdis": { "encoded": 102, "encodeddisoctets": 14688, "encodedcdisoctets": 4794, "encoderatio": 0.326,
          "fullupdates": 102, "partialupdates": 0, "notconvertible": 0, "encodeerrors": 0,
          "decoded": 48, "decodedcdisoctets": 1332, "decodeddisoctets": 6912, "decodeerrors": 0,
          "unknownpdus": 0, "waitingforfull": 0, "timedout": 0, "lasterror": "..." }
```

`notconvertible`: PDUs of types C-DIS does not have, not sent. `waitingforfull`: partial updates dropped because the object's first full update had not arrived. `lasterror` only when there was one.

A PDU relay (`"router": "pdu"`) has in `objects` the PDUs passed per direction and per type: `{"relayed": {"dis -> cdis": 102}, "pdutypes": {"001": 102}, "filtered": 0}`; its endpoints count Entity State, EE, Designator, Transmitter, Receiver and IFF PDUs as updates, the others as interactions.

A Talk gateway's `objects` has a `talk` object:

```json
"talk": { "endpoints": [ { "endpoint": "a", "radioclient": true, "channels": [
            {"name":"Net1","class":"VoiceChannel","ours":false,"bridged":true,"matchedin":["b"],"radiohz":251000000},
            {"name":"Ops","class":"ChatChannel","ours":false,"bridged":true,"matchedin":["b as Operations"]} ] } ],
          "mode": "full", "trackchannels": true, "passedunknownchannel": 0,
          "messagesrouted": 4, "droppednocounterpart": 1, "droppedfiltered": 0, "droppedunknownchannel": 0,
          "channelscreated": 0,
          "radio": {"endpoint":"radio","transmitters":1,"heard":1,"framestorpr":1,"framestotalk":2,"framesdropped":0,"opus":false} }
```

---

## 5. Inspect

`POST /junction/gateways/{gid}/endpoints/{epid}/inspect` creates (201) or returns the existing (200) Rift datasource that joins the endpoint's target: `{"datasource":"<uuid>","page":"datasource.html?id=<uuid>","created":true}`. Rift datasources use the class path RTI and the endpoint's LSD (the one it connects with, also when chosen at the start), so an endpoint with its own `rtijar` or a Federate Protocol endpoint is inspected through the class path RTI. DIS datasources share the UDP port (works for broadcast and multicast, not for unicast). The datasource is removed when the gateway stops.

---

## 6. Datasources from endpoints

Every endpoint of every configuration also appears in Rift's `GET /api/datasourceconfigs`, with `"origin":"junction"` and `"originlabel":"Junction: <gateway> / <endpoint>"`. Rift's page lists them in a group of their own, folded away by default. Rift's server setting `junction.datasourceconfigs=false` leaves them out; Inspect (section 5) still works.

---

## 7. Live events (SSE)

On Rift's event stream (`/api/events?topics=...`):

| Topic | Event | Data |
|---|---|---|
| `junction` | `gateways` | `{"gateways":[gateway...]}` once a second while someone listens |
| `junction:<gid>` | `gateway` | `{"gateway":{...}}` once a second |
| `junction:<gid>` | `gateway.removed` | `{"id":"<gid>"}` |

---

## 8. curl

```sh
B=https://localhost:8843/api/junction
curl -sk $B/configs
curl -sk -X POST $B/gateways -H 'Content-Type: application/json' -d '{"configid":"hla3-dis7","targets":{"dis":"3"}}'
curl -sk -X POST $B/groups/Exercise/start -H 'Content-Type: application/json' -d '{"vars":{"GROUPVAR1":"FederationX"}}'
curl -sk -X POST $B/groups/Exercise/start -H 'Content-Type: application/json' -d '{"vars":{"GROUPVAR1":"FederationY"},"instance":"Night"}'
curl -sk -X POST $B/groups/Exercise/stop -H 'Content-Type: application/json' -d '{"instance":"Night"}'
curl -sk -X POST $B/groups/Exercise/stop
curl -sk -X DELETE $B/gateways/<gid>
```
