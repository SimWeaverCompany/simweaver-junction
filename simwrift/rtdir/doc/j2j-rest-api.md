# SimWeaver J2J — REST API

The REST calls of the **J2J** page (`j2j.html`): this server's view of its J2J network — the
members, how each is reached, the links between them, the sessions and the J2J log events.
J2J is set up in the install guide, section 2.8; the protocol between the servers is described in
[j2j-wire-protocol.md](j2j-wire-protocol.md).

The calls follow the conventions of the Rift API ([rest-api.md](rest-api.md)): HTTPS on the web
port, JSON in UTF-8 with lower-case keys, times in UTC ISO 8601, and the error envelope
`{"success": false, "error": {"code", "message"}}` with a real HTTP status. With API authentication on
(0.11.0) they need a token like every call under `/api` ([rest-api.md](rest-api.md), 14).

The API only reads, with three actions that stay on this server: ping a member, read the member
list again, and make a key text. Nothing in it controls another server.

API state: SimWeaver 0.10.0, October 2026.

---

## Contents

1. [Summary](#1-summary)
2. [`GET /api/j2j`](#2-get-apij2j)
3. [`GET /api/j2j/members`](#3-get-apij2jmembers)
4. [`GET /api/j2j/members/{id}`](#4-get-apij2jmembersid)
5. [`POST /api/j2j/members/{id}/ping`](#5-post-apij2jmembersidping)
6. [`POST /api/j2j/members/reload`](#6-post-apij2jmembersreload)
7. [`GET /api/j2j/topology`](#7-get-apij2jtopology)
8. [`GET /api/j2j/sessions`](#8-get-apij2jsessions)
9. [`GET /api/j2j/events`](#9-get-apij2jevents)
10. [`POST /api/j2j/key/new`](#10-post-apij2jkeynew)
11. [Live events (SSE topic `j2j`)](#11-live-events-sse-topic-j2j)
12. [Errors](#12-errors)

---

## 1. Summary

| Method | Path | Reply |
|---|---|---|
| `GET` | `/api/j2j` | This server's J2J state, also when J2J is off |
| `GET` | `/api/j2j/members` | Every member of the member list: state, how it is reached, ping, users |
| `GET` | `/api/j2j/members/{id}` | One member, with attempts, traffic per class and its own status |
| `POST` | `/api/j2j/members/{id}/ping` | The round trip to it now, hop by hop |
| `POST` | `/api/j2j/members/reload` | Read `etc/j2j/network.settings` and `etc/j2j/network.key` again |
| `GET` | `/api/j2j/topology` | Every server and every link known, for the network diagram |
| `GET` | `/api/j2j/sessions` | The sessions this server knows of |
| `GET` | `/api/j2j/events?limit=100` | The latest J2J log events |
| `POST` | `/api/j2j/key/new` | A random network key text to hand out (not installed) |
| `GET` | `/api/events?topics=j2j` | Live updates (SSE) |

While J2J is off, every call but `GET /api/j2j`, `POST /api/j2j/members/reload` and
`POST /api/j2j/key/new` answers 409 `J2J_OFF`.

`GET /api/serverinfo` has `"j2j": {"state": "off|running|error"}`: the pages show the **J2J**
button in the top bar only when it is not `off`.

---

## 2. `GET /api/j2j`

```json
{ "state": "running",
  "problems": [],
  "self": { "id": "sto-1", "name": "Stockholm lab" },
  "network": { "id": "exercise-net", "name": "Exercise network North",
               "keyid": "k1", "keyfingerprint": "65c61742", "keys": 1,
               "memberhash": "8baa96a2", "members": 5, "known": 3, "agree": 3 },
  "listen": { "tcp": 8844, "udp": 8844, "fallback": 443, "fallbackproxy": null, "problems": [] },
  "counts": { "direct": 1, "routed": 1, "notreachable": 2, "sessions": 0 },
  "now": "2026-10-28T05:59:16.031Z" }
```

| Field | Meaning |
|---|---|
| `state` | `off`: the member list has no members. `running`. `error`: the files are there but cannot be used; `problems` says why, and nothing is connected |
| `problems` | What stops J2J (`error`), one text each |
| `how` | Only when `off`: what to do to switch J2J on |
| `self` | This server's id (`j2j.node.id`) and name |
| `network.keyid`, `keyfingerprint` | The key new links use (the preferred one) and its fingerprint: 8 hex digits of SHA-256 of the key. The key itself is never shown |
| `network.keys` | Keys in `network.key` (2 during a key change) |
| `network.memberhash` | 8 hex digits identifying this server's member list |
| `network.known`, `agree` | Member lists known (this server's and those the members told it) and how many of them are the same as this one |
| `listen` | The ports in use: `tcp`, `udp`, `fallback` (TLS last resort); `null` when not listening. `problems`: a port that could not be opened |
| `counts` | Members `direct`, `routed`, `notreachable` (any other state), and sessions |

---

## 3. `GET /api/j2j/members`

Every member of the member list, this server first, then in the order of the file.

```json
{ "members": [
  { "id": "got-2", "name": "Gothenburg", "site": "West", "self": false,
    "addresses": ["10.20.0.7:8844"], "fallback": null, "connect": true,
    "state": "direct", "statemessage": null,
    "since": "2026-10-28T06:18:12.068Z", "address": "10.20.0.7:8844", "direction": "out",
    "via": null, "path": ["sto-1", "got-2"], "hops": 1,
    "channels": [ { "kind": "tcp", "address": "10.20.0.7:8844", "state": "up", "since": "2026-10-28T06:18:12.068Z" },
                  { "kind": "udp", "address": "10.20.0.7:8844", "state": "up" } ],
    "encrypted": true, "rttms": 12.4, "lossratio": 0.0, "clockoffsetms": 3,
    "version": "SimWeaver 0.10.0", "memberlist": "same",
    "lastseen": "2026-10-28T06:18:19.150Z",
    "rate": { "inkbps": 41.2, "outkbps": 38.0 },
    "users": { "gateways": [], "sessions": [], "transitfor": ["mlm-3"] } } ] }
```

| Field | Meaning |
|---|---|
| `addresses`, `fallback`, `connect`, `site` | As configured in `network.settings` |
| `state` | See below; `statemessage` explains `waiting`, `refused`, `unreachable`, `notdialled` |
| `retryseconds` | Only when `waiting`: seconds to the next try |
| `since`, `address`, `direction` | Direct: when the link came up, the address it runs on, and `out` (this server dialled) or `in` |
| `via`, `path`, `hops` | Routed: the next hop, the whole path from this server, and its length. Direct: `path` is this server and the member, `hops` 1 |
| `channels` | Direct: `tcp` or `tls443` (the last resort), and `udp` with its state (`up`, or `down` while best effort goes over TCP) |
| `encrypted` | `true` when a link or path is up (every link is encrypted) |
| `rttms` | Smoothed round trip in ms: of the link (direct) or the sum along the path (routed) |
| `lossratio` | UDP loss over about 30 s (direct, with UDP) |
| `clockoffsetms` | Its clock minus ours, estimated from the heartbeats (direct) or its status (routed) |
| `version` | Its software, from the handshake or its status |
| `memberlist` | `same`, `different` or `unknown`: its member list compared with ours |
| `lastseen` | When it was last heard from |
| `rate` | Direct: traffic on the link, kbit/s, over the last seconds |
| `users.gateways` | This server's gateways that share a session with it: `{gateway, instance, endpoint, session, remotegateways}` (its members of that session with the same model) |
| `users.sessions` | The sessions it has members in |
| `users.transitfor` | Members this server reaches through it |

**States**

| State | Meaning |
|---|---|
| `self` | This server |
| `direct` | A link to it is up |
| `routed` | No link of its own, but reachable through other members (`via`) |
| `connecting` | Being dialled now |
| `waiting` | Not reachable; dialled again in `retryseconds` |
| `refused` | The last handshake was refused, by either side (`statemessage`: e.g. *the peer does not hold the network key k1*) |
| `unreachable` | Seen before, but now neither a link nor a path, and not dialled (no addresses or `connect=false`) |
| `notdialled` | Never seen, and not dialled: it has no addresses or `connect=false`; it must dial this server |
| `notstarted` | J2J did not start on this server (`state` `error`) |

---

## 4. `GET /api/j2j/members/{id}`

`{"member": {…}}`: the member as in 3, plus:

```json
"attempts": [ { "address": "10.40.0.2:8844", "kind": "tcp", "at": "2026-10-28T04:49:02.110Z", "result": "timeout", "ok": false },
              { "address": "gw.bdn.example.org:443", "kind": "tls443", "at": "2026-10-28T04:31:05.880Z", "result": "ok", "ok": true } ],
"classes": { "admin":      { "outmsgs": 1201, "inmsgs": 1187, "queued": 0 },
             "reliable":   { "outmsgs": 52011, "inmsgs": 50100, "queued": 3, "unacked": 12 },
             "besteffort": { "outmsgs": 910233, "inmsgs": 899120, "queued": 0, "dropped": 1203, "superseded": 88411,
                             "udpout": 905000, "udpin": 898000 } },
"rtthistory": [12.1, 12.4, 11.9],
"node": { "name": "Gothenburg", "version": "SimWeaver 0.10.0", "started": "2026-10-22T02:10:00.000Z",
          "cpu": 4.2, "heapused": 412316860, "heapmax": 4294967296, "gateways": 3, "sessions": 1,
          "received": "2026-10-28T04:16:52.004Z" },
"lasterror": null, "lasterrortime": null,
"pending": 0
```

| Field | Meaning |
|---|---|
| `attempts` | The last try on each address it was dialled on: when, with what `result`, `ok` |
| `classes` | Direct: messages per traffic class on the link. `queued`: waiting to be sent; `unacked`: reliable messages to it not yet acknowledged (also through other members); `dropped`: best effort dropped (queue full or too old); `superseded`: best effort replaced by a newer value before it was sent; `udpout`/`udpin`: best effort over UDP. `null` without a link |
| `rtthistory` | Direct: the last round trips (ms), one per heartbeat, oldest first |
| `node` | What it last told about itself (every 10 s): software, start, CPU (%), heap (octets), gateways and sessions, and when it came. `null` before the first status |
| `lasterror`, `lasterrortime` | Why the last link or try ended |
| `pending` | Reliable messages to it not yet acknowledged |

404 `UNKNOWN_MEMBER` for an id not in the member list.

---

## 5. `POST /api/j2j/members/{id}/ping`

Measures the round trip now, to every server on the path in turn (an echo through the network,
answered by each):

```json
{ "success": true, "rttms": 48.2,
  "path": [ { "id": "got-2", "rttms": 12.3 }, { "id": "mlm-3", "rttms": 48.2 } ] }
```

`rttms` of a hop is the round trip from this server to that hop (`null` when it did not answer).
409 `UNREACHABLE` when there is no path (or `{id}` is this server), 504 `PING_TIMEOUT` when the
member does not answer within 5 s, 409 `J2J_OFF` when J2J does not run.

---

## 6. `POST /api/j2j/members/reload`

Reads `etc/j2j/network.settings` and `etc/j2j/network.key` again and applies them: J2J restarts
with the new list when anything changed (its links come up again within seconds). This also
switches J2J on (a list with members) and off (an empty list) without restarting the server.

```json
{ "success": true, "added": ["mlm-3"], "removed": [], "changed": ["got-2"], "state": "running", "problems": [] }
```

- `changed`: members whose name, addresses, fallback, site or `connect` changed.
- **A list with problems while J2J runs** is not applied: 422 `MEMBERS_INVALID` with `problems`; the running list stays.
- While J2J is off or did not start, a list with problems is applied: `state` is then `error`.

---

## 7. `GET /api/j2j/topology`

The network as the members' link-state updates describe it: also links between other members.

```json
{ "sites": ["North", "South", "West"],
  "nodes": [ { "id": "sto-1", "name": "Stockholm lab", "site": "North", "state": "self", "via": null, "rttms": null },
             { "id": "mlm-3", "name": "Malmo", "site": "South", "state": "routed", "via": "got-2", "rttms": 24.7 } ],
  "links": [ { "a": "got-2", "b": "sto-1", "state": "up", "channels": ["tcp", "udp"], "rttms": 8.4, "cost": 13, "ours": true },
             { "a": "got-2", "b": "mlm-3", "state": "up", "channels": [], "rttms": 16.3, "cost": 21, "ours": false },
             { "a": "kir-5", "b": "sto-1", "state": "waiting", "reason": "10.50.0.2:8844: Connection refused", "ours": true },
             { "a": "lkp-6", "b": "sto-1", "state": "refused", "reason": "the peer does not hold the network key k1", "ours": true } ] }
```

- `nodes`: every member, with its state as in 3.
- `links`: `a` and `b` in id order. `up` links are those both ends advertise, with the advertised `rttms` and `cost` (the routing cost: RTT in ms + 5 per hop). `channels` is known for this server's own links (`ours`) only.
- A link that is not up is shown only when it is one of this server's own: `waiting` (dialled, not up) or `refused`, with the `reason`.

---

## 8. `GET /api/j2j/sessions`

The sessions this server knows of: gateways on different servers sharing DIS PDUs (`kind`
`pdu`) or the objects and interactions of a model (`kind` `objects`), joined with the `j2j`
endpoint of Junction ([junction.md](junction.md), *J2J sessions*). Every session with a
member here or on a server this one hears from; this server's members first.

```json
{ "sessions": [
  { "name": "north-pdu", "kind": "pdu", "model": "DIS 7, exercise 1",
    "members": [ { "node": "sto-1", "gateway": "dis-north", "instance": "", "endpoint": "j2j", "model": "DIS 7, exercise 1",
                   "state": "active", "statemessage": null, "local": true, "since": "2026-10-28T03:12:44.000Z" },
                 { "node": "kir-5", "gateway": "dis-range", "instance": "", "endpoint": "j2j", "model": "DIS 7, exercise 1",
                   "state": "lost", "statemessage": "not reachable", "local": false, "since": "2026-10-28T03:10:02.000Z" },
                 { "node": "bdn-4", "gateway": "dis-boden", "instance": "", "endpoint": "j2j", "model": "DIS 6, exercise 1",
                   "state": "mismatch", "statemessage": "DIS 6, exercise 1 (ours: DIS 7, exercise 1)", "local": false, "since": "…" } ],
    "since": "2026-10-28T03:12:44.000Z" } ] }
```

- `kind` and `model`: this server's members' (else the first member's). An object session's model is `HLA objects <fingerprint> (<FOM families>)`. Every member also has its own `kind` and `model`. `since`: when the first member here joined.
- Member `state`: `active`; `lost` (its server has had no route for `j2j.session.lostseconds`, default 10); `mismatch` (another model: no PDUs between it and this server's members).
- `gateway` is the gateway's name on its server (the configuration, with `-<instance>` for instances but the first).

---

## 9. `GET /api/j2j/events`

The latest J2J log events, newest first, from memory (the last 500; the log keeps them all).
`limit`: 1-500, default 100; anything else is 400 `BAD_REQUEST`.

```json
{ "events": [
  { "at": "2026-10-28T06:19:16.050Z", "level": "WARNING", "event": "j2j.handshake.refused",
    "text": "J2J: refused e (127.0.0.1:8944): the peer does not hold the network key k1",
    "fields": { "from": "e", "address": "127.0.0.1:8944", "reason": "AUTH", "side": "ours", "count": "1", "by": "j2j" } },
  { "at": "2026-10-28T06:19:15.011Z", "level": "NOTICE", "event": "j2j.link.up",
    "text": "J2J link to b up (dialled 127.0.0.1:8942)",
    "fields": { "member": "b", "address": "127.0.0.1:8942", "direction": "out", "channel": "tcp", "software": "SimWeaver 0.10.0", "by": "j2j" } } ] }
```

The events and their fields are listed in the install guide, section 2.8.

---

## 10. `POST /api/j2j/key/new`

```json
{ "success": true, "key": "<base64, 32 octets>", "keyid": "k2", "fingerprint": "2af4e36b", "line": "k2=<base64>" }
```

- 32 random octets from `SecureRandom`, with the next free key id, to copy into `network.key` on every member.
- The server does **not** install it, and keeps no copy.
- No call ever shows a key in use, only its id and fingerprint.

---

## 11. Live events (SSE topic `j2j`)

`GET /api/events?topics=j2j` (the Rift event stream, [rest-api.md](rest-api.md) section 8):

| Event | Data | When |
|---|---|---|
| `j2j.status` | As `GET /api/j2j` | Every 2 s |
| `j2j.members` | As `GET /api/j2j/members` | Every 2 s, while J2J is configured |
| `j2j.topology` | As `GET /api/j2j/topology` | When it changed (checked every 2 s) |
| `j2j.event` | One event as in 9 | When it is logged |

The J2J page reads the calls once, then follows the stream; without the stream it polls.

---

## 12. Errors

| HTTP | Code | When |
|---|---|---|
| 400 | `BAD_REQUEST` | A malformed `limit` |
| 404 | `NOT_FOUND` | No such call |
| 404 | `UNKNOWN_MEMBER` | The id is not in the member list |
| 405 | `METHOD_NOT_ALLOWED` | E.g. `GET` on `…/ping` |
| 409 | `J2J_OFF` | J2J is off (or, for a ping, does not run) |
| 409 | `UNREACHABLE` | Ping: no path to the member |
| 422 | `MEMBERS_INVALID` | Reload: the new member list has problems; the running one stays |
| 504 | `PING_TIMEOUT` | Ping: no answer within 5 s |

---

By SimWeaver - released under the BSD 3-Clause License.
