# SimWeaver J2J — wire protocol

The protocol between SimWeaver Junction servers in a J2J network: frames, the handshake, encryption and messages. This document is **normative**: an implementation that follows it interoperates with SimWeaver. The test vectors in appendix A are checked by the self test (`com.simweaver.j2j.J2jSelfTest`), and were also reproduced by a separate implementation written from this text.

| | |
|---|---|
| Protocol versions | **1** (this document) |
| Document state | 0.10.0 (draft), October 2026 |
| Transport | TCP; UDP for best effort (feature `udp`); TLS on 443 as the last resort (§3.5) |
| Port | 8844 by default (`j2j.port`) |

---

## 1. Conventions

- Integers are unsigned and **big endian**: `u8`, `u16`, `u32`, `u64`.
- Strings are **UTF-8**, without a terminator; their length comes from the enclosing field.
- `|` joins octet strings.
- **Reserved** fields and bits are sent as 0 and **ignored** on receipt.
- Lengths are in octets.

## 2. Compatibility rules

These rules keep every later version working with this one:

1. **Versions are negotiated** in the handshake (§4). An implementation sends the range it speaks and uses the highest common version.
2. **Features are negotiated** (TLV `FEATURES`). Optional behaviour is used only when both sides list the feature.
3. **TLVs are skipped when unknown,** unless bit 15 of the tag (*critical*) is set. An unknown critical TLV makes the receiver refuse (handshake) or drop the message and report it.
4. **Fields are only appended.** A field's position, size and meaning never change. A receiver ignores octets after the fields it knows, in frame bodies and in TLV values.
5. **Unknown frame and message types are ignored** once authenticated, unless they carry the *must understand* flag (frame flag bit 0, message flag bit 0). An unknown frame type with the flag closes the channel (`CLOSE PROTOCOL`).
6. **Incompatible changes get a new protocol version.** Implementations keep speaking the previous version for at least one release series.

## 3. Frames

A TCP channel carries frames, back to back. Every frame starts with its length:

```
u32 length      octets after this field; at most 1 048 640 (1 MiB + 64)
u8  type        frame type
…               the rest (length − 1 octets)
```

A receiver closes the channel on a length out of range.

### 3.1 Frame types

| Type | Name | Encrypted | Direction |
|---|---|---|---|
| 1 | `HELLO` | no | initiator → responder |
| 2 | `WELCOME` | no | responder → initiator |
| 3 | `FINISH` | no | initiator → responder |
| 4 | `REFUSE` | no | either side, during the handshake |
| 16 | `DATA` | yes | a message (§6) |
| 17 | `PING` | yes | keepalive and RTT |
| 18 | `PONG` | yes | answer to `PING` |
| 19 | `CLOSE` | yes | orderly close |

Types 5–15 are reserved for future handshake frames, and types 20 and up for future encrypted frames.

### 3.2 Encrypted frames

After the handshake, every frame in both directions is encrypted with AES-256-GCM:

```
u32 length      1 + 1 + 8 + plaintext length + 16
u8  type
u8  flags       bit 0: must understand; bits 1–7 reserved
u64 counter     0 for the first encrypted frame in this direction, then +1 per frame
…   ciphertext  same length as the plaintext
16  tag         GCM authentication tag
```

- **Key:** the direction's key from the handshake (§4.4).
- **Nonce:** 12 octets, `00 00 00 00 | counter` (counter as u64).
- **Associated data:** the first 14 octets of the frame (length, type, flags, counter).
- **Counter:** must be exactly the next one expected; otherwise the receiver closes the channel. A frame that does not authenticate also closes the channel.
- **Rekeying:** a channel is re-keyed (a fresh handshake on a new channel, then the old one is closed with `CLOSE REKEY`) at the latest after 2³² frames or one hour, so a counter never comes near wrapping.

### 3.3 PING, PONG and CLOSE (TCP and UDP)

```
PING   u64 send time (UTC ms since 1970) | u32 ping id
PONG   u64 the PING's send time | u32 the PING's id | u64 responder's time (UTC ms)
CLOSE  u16 code | TLVs: MESSAGE (11)
```

- **PING:** each side sends one at least every second.
- **Dead channel:** a channel with no frame received for `deadSeconds` (default 5) is closed.
- **RTT:** the time from sending a PING to receiving its PONG.
- **Clock offset:** responder's time − (send time + RTT / 2).

Close codes:

| Code | Name | Meaning |
|---|---|---|
| 1 | `SHUTDOWN` | the sender is stopping |
| 2 | `DUPLICATE` | both sides dialled; this channel is the one dropped (§5) |
| 3 | `TIMEOUT` | nothing received for `deadSeconds` |
| 4 | `PROTOCOL` | a malformed or unacceptable frame |
| 5 | `REKEY` | replaced by a newer channel with fresh keys |
| 6 | `MEMBER_REMOVED` | the peer is no longer in the member list |
| 7 | `REPLACED` | the peer restarted and a new channel replaced this one |

### 3.4 UDP datagrams (feature `udp`)

When both sides list the feature `udp`, best-effort messages may also travel as UDP datagrams, to the peer's J2J port number.

```
u32 connection id   the receiving direction's id from the handshake (§4.4)
u8  type            16 DATA (best effort only), 17 PING, 18 PONG
u8  flags           as §3.2
u64 counter         0 for the first datagram in this direction, then +1 per datagram
…   ciphertext
16  tag
```

- **Encryption:** as §3.2, with the direction's UDP key. The AAD is the first 14 octets.
- **Size:** a datagram is at most 1400 octets. Bigger best-effort messages go over TCP.
- **Replay:** the receiver accepts a counter only once, and only within a window of 1024 behind the highest seen. It updates the window only after the datagram authenticates.
- **Addresses:**
  - The dialling side sends to the address it dialled, and the accepting side learns the peer's address from the first authenticated datagram.
  - Each side sends a UDP `PING` every second to that address.
  - The accepting side always answers to the source address of the latest authenticated datagram, which keeps NAT mappings working.
- **When UDP is "up":** while a `PONG` came within `deadSeconds`. While UDP is up, best-effort messages may be sent by UDP. Otherwise, and for everything else, the TCP channel is used.

### 3.5 TLS on 443: the last resort

When no TCP address of a peer answers, a server may dial the peer's fallback address (port 443 by default). It sets up TLS there, optionally through an HTTP proxy (`CONNECT host:port HTTP/1.1`, answered with any 2xx). Inside the TLS connection it runs exactly the same frames as on a TCP channel, handshake included.

- **Certificates:** TLS is only a wrapper for firewalls and proxies. The client does not verify the server certificate, because the J2J handshake inside authenticates both sides.
- **Moving back:** the dialling side tries its primary addresses again regularly. A working one replaces the TLS channel. The replaced channel gets `CLOSE REKEY` (§5).

## 4. Handshake

The dialling side (**initiator**, I) and the accepting side (**responder**, R) exchange three plain frames. Authentication uses the network key (PSK). Forward secrecy comes from ephemeral X25519 keys (RFC 7748).

```
I → R   HELLO
R → I   WELCOME        (or REFUSE)
I → R   FINISH         (or REFUSE)
        both sides switch to encrypted frames; I may send them right after FINISH
```

A responder gives up after 5 s without a complete handshake.

### 4.1 TLVs

Handshake fields are TLVs:

```
u16 tag      bit 15: critical; bits 0–14: the tag number
u16 length
…   value
```

| Tag | Name | Value |
|---|---|---|
| 1 | `NETWORK` | UTF-8: the network id (`network.id`) |
| 2 | `FROM` | UTF-8: the sender's node id |
| 3 | `TO` | UTF-8: the node id the initiator means to reach |
| 4 | `KEY_ID` | UTF-8: id of the network key used (e.g. `k1`) |
| 5 | `EPHEMERAL` | 32 octets: the sender's X25519 public key (RFC 7748 encoding) |
| 7 | `BOOT` | u64: random, new at every process start |
| 8 | `FEATURES` | ASCII: comma-separated feature names; may be empty |
| 9 | `SOFTWARE` | UTF-8: product and version, e.g. `SimWeaver 0.10.0` |
| 10 | `MEMBER_HASH` | 4 octets: hash of the sender's member list (compared, never interpreted) |
| 11 | `MESSAGE` | UTF-8: a human-readable text |
| 12 | `VERSIONS` | 2 octets: lowest and highest protocol version the sender speaks |
| 32 | `AUTH` | 32 octets: authenticator; always the **last** TLV of its body |

Tag 6 is reserved. A tag appearing twice keeps its first value.

### 4.2 HELLO (I → R)

```
"J2J"  (4A 32 4A)
u8 lowest protocol version I speaks
u8 highest protocol version I speaks
TLVs: NETWORK, FROM, TO, KEY_ID, EPHEMERAL, BOOT, FEATURES, SOFTWARE, MEMBER_HASH
```

`KEY_ID` is I's preferred key.

### 4.3 WELCOME (R → I)

```
u8 version      the chosen protocol version: the highest in both ranges
TLVs: FROM, KEY_ID (the same as in HELLO), EPHEMERAL, BOOT, FEATURES (the common ones), SOFTWARE, MEMBER_HASH, AUTH
```

### 4.4 Keys and authenticators

```
shared  = X25519(own ephemeral private key, peer's EPHEMERAL)
th      = SHA-256("J2J v1 handshake" | HELLO body | WELCOME body up to, not including, its AUTH TLV)
prk     = HKDF-Extract(salt = network key (32 octets), IKM = shared)              RFC 5869, SHA-256
AUTH R  = HKDF-Expand(prk, "j2j v1 auth responder" | th, 32)    in WELCOME
AUTH I  = HKDF-Expand(prk, "j2j v1 auth initiator" | th, 32)    in FINISH
key I→R = HKDF-Expand(prk, "j2j v1 key i2r" | th, 32)
key R→I = HKDF-Expand(prk, "j2j v1 key r2i" | th, 32)

feature udp:
UDP key I→R = HKDF-Expand(prk, "j2j v1 udp key i2r" | th, 32);   UDP key R→I = HKDF-Expand(prk, "j2j v1 udp key r2i" | th, 32)
UDP id I→R  = HKDF-Expand(prk, "j2j v1 udp id i2r" | th, 4) as u32; UDP id R→I = HKDF-Expand(prk, "j2j v1 udp id r2i" | th, 4) as u32
```

- **Strings:** the label strings are ASCII, without a terminator.
- **"Body"** means the frame after its length and type octets.
- **Checking:** each side compares the peer's authenticator in constant time.
- **Zero secret:** an all-zero `shared` (a low-order public key) fails the handshake.

### 4.5 FINISH (I → R)

```
TLVs: AUTH (= AUTH I)
```

### 4.6 What each side checks

The **responder** refuses (REFUSE, then closes) when:

| Condition | Code |
|---|---|
| no magic, or not a HELLO | `PROTOCOL` (9) |
| no common protocol version | `VERSION` (1), with `VERSIONS` |
| `NETWORK` differs from its own | `NETWORK` (2) |
| `FROM` is not in its member list | `NOT_MEMBER` (3) |
| `FROM` is its own id | `SELF` (8) |
| `TO` is present and is not its own id | `WRONG_TARGET` (4) |
| it has no key with `KEY_ID` | `KEY_ID` (5) |
| `EPHEMERAL` is missing or not 32 octets | `PROTOCOL` (9) |
| an unknown critical TLV | `PROTOCOL` (9) |
| too many handshakes at once | `BUSY` (7) |
| `FINISH` has a wrong `AUTH` | `AUTH` (6) |

The **initiator** refuses (REFUSE, then closes) when:
- the version is outside its range (`VERSION`);
- `FROM` is not the server it dialled (`WRONG_TARGET`);
- the key id differs (`KEY_ID`);
- `AUTH R` is wrong (`AUTH`).

### 4.7 REFUSE

```
u16 code | TLVs: MESSAGE (11), VERSIONS (12, with VERSION)
```

`MESSAGE` explains the refusal for logs. For `AUTH` and `PROTOCOL` it is left out, so a guesser learns nothing.

### 4.8 Features

| Feature | Meaning |
|---|---|
| `udp` | UDP datagrams for best effort (§3.4) |

## 5. Channels and links

- **One link per pair.** Between two servers there is one link. When a new channel completes its handshake while another to the same peer exists, the new channel replaces the old one when:
  1. the peer's `BOOT` differs (it restarted: `CLOSE REPLACED`); or
  2. both channels were dialled by the same server (re-keying, the move back from the last resort, or a half-dead old channel: `CLOSE REKEY`); or
  3. they were dialled by different servers, and the new one by the server with the smaller id (ids compared octet by octet, which suits the ASCII ids): `CLOSE DUPLICATE`.

  Otherwise the new channel is closed with `CLOSE DUPLICATE`. Both sides apply the same rule, so they keep the same channel.
- **Replacement:** what still waits to be sent on a replaced channel moves to its replacement.
- **`CLOSE REKEY` may come first.** The side that replaced a channel closes the old one at once, so the other side can read the `CLOSE REKEY` before its own handshake of the new channel has finished. A receiver should then wait a moment (SimWeaver: 3 s) for the new channel before it treats the link as down.
- **Reconnecting** is the dialler's business. Delays and backoff are not part of the protocol.

## 6. Messages (DATA frames)

The plaintext of a `DATA` frame is one message:

```
u8  class          0 admin, 1 reliable, 2 best effort
u8  flags          bit 0 must understand; bit 2 destination is a session key; others reserved
u8  ttl            hops left; a forwarding server decrements it and drops the message at 0
u8  reserved
u64 source         node key of the original sender
u64 destination    node key, or session key (flag bit 2); 0 = the neighbour only (link state)
u32 stream         end-to-end stream, 0 = none
u64 sequence       position in the stream (reliable, admin), 0 = unsequenced (§7.2)
u16 type           message type (§6.2)
u16 ext length
…   ext            TLVs (as §4.1); unknown non-critical ones skipped
…   payload        the rest of the frame
```

The fixed part is 36 octets. A message is at most 1 MiB (1 048 576 octets); larger contents are fragmented, with fragment TLVs in the extension block, in a later revision.

- **Node key:** the first 8 octets of SHA-256 of the node id (UTF-8), read as a u64.
- **Session key:** the same rule applied to the session name.

Extension TLVs:

| Tag | Name | Value |
|---|---|---|
| 1 | `SUPERSEDE` | best effort: an opaque key. A newer message from the same source to the same destination with the same key may replace a queued one, also at a forwarding server |

### 6.1 Classes

| Class | Delivery |
|---|---|
| 0 admin | reliable, in order, end to end; sent before everything else |
| 1 reliable | reliable, in order per stream, end to end |
| 2 best effort | at most once; may be dropped, or replaced by a newer message for the same thing |

Admin and reliable messages are sequenced per stream (§7.2). Messages with sequence 0 are unsequenced: delivered at most once and never acknowledged. Link state, acknowledgements, resets and echoes are unsequenced.

### 6.2 Message type ranges

| Range | Use |
|---|---|
| 0x0000 | reserved |
| 0x0001–0x00FF | network: link state, acknowledgements, status, ping/trace |
| 0x0100–0x01FF | sessions: PDU sessions |
| 0x0200–0x02FF | sessions: object sessions (HLA, Talk) |
| 0xF000–0xFFFF | experimental; never sent between different products |

## 7. Network messages and delivery

### 7.1 Messages

All are class 0 (admin), unsequenced, ttl 16 (LSA: 1). Every list entry starts with its own `u16` length, so entries can grow; a reader skips what follows the fields it knows.

| Type | Name | Payload |
|---|---|---|
| 0x0001 | `LSA` | `u64 sequence` \| `u64 boot` \| `u16 lifetime (s)` \| `str8 origin` \| `u16 n` \| n × (`u16 len` \| `str8 neighbour` \| `u32 cost` \| `u32 rtt (µs)`) |
| 0x0002 | `ACK` | `u16 n` \| n × (`u16 len` \| `u32 stream` \| `u64 sequence`): everything up to and including `sequence` in that stream arrived |
| 0x0003 | `STREAM_RESET` | `u32 stream` \| `u64 sequence`: the receiver cannot place this sequence (it lost its state) |
| 0x0004 | `ECHO_REQUEST` | `u32 id` \| `u64 send time (UTC ms)` |
| 0x0005 | `ECHO_REPLY` | the request's 12 octets \| `u64 reply time (UTC ms)` |

`str8` is `u8 length | UTF-8`.

### 7.2 Sequenced delivery

A sequenced message's `sequence` is `epoch (24 bits, not 0) << 40 | counter (40 bits, from 1)`. The stream is identified by (source, destination, stream).

- **Sender:**
  - **Numbering:** it numbers each stream's messages 1, 2, 3 … within an epoch, and keeps each until acknowledged.
  - **Resending:** it resends unacknowledged messages after a timeout, and at once when the route to the destination changes.
  - **New epoch:** it starts a new epoch, renumbering what is still unacknowledged from 1 in the same order, when:
    - it receives `STREAM_RESET` for the current epoch; or
    - the destination's `BOOT` changes (from its LSA or a direct link).
- **Receiver:**
  - **Delivery:** it delivers each message once, in counter order, and acknowledges cumulatively (`ACK`, within about 200 ms).
  - **A new epoch** begins at counter 1. Messages of a retired epoch are dropped.
  - **Lost state:** a message of an unknown epoch with a counter above 1 waits briefly for counter 1. After 3 s without it, the receiver sends `STREAM_RESET`.
- **Consequence:** delivery is exactly once while both sides run. Across a receiver restart it is at least once: what the old receiver got but had not acknowledged is sent again.

### 7.3 Routing (informative)

The routing below is how SimWeaver behaves. Only the LSA format is protocol.

- **LSAs:** every server floods its LSA:
  - its neighbours, each with a cost (RTT in ms + 5);
  - when its links change, and every 30 s;
  - lifetime 120 s.
- **Flooding:** a server accepts an LSA whose sequence is higher than the one it holds for that origin, and passes it to every neighbour except the sender. On a new link it sends its whole database.
- **Edges:** an edge counts only when both ends list each other.
- **Routes:** Dijkstra gives the next hop. A working route is kept unless the new one is at least 20 % and 5 better, or has fewer hops and costs no more.
- **First cost of a link:** the handshake's round trip, until the first PONG.
- **Forwarding:** a server forwards by destination, decrementing `ttl`, and drops a message at `ttl` 0.

### 7.4 NODE_STATUS (0x0010)

Class 0 (admin), unsequenced, to every reachable server every 10 s and to a server when it becomes reachable. Read only: it tells, it controls nothing. The payload is TLVs (§4.1), all optional:

| Tag | Name | Value |
|---|---|---|
| 1 | `NAME` | UTF-8: the member's name |
| 2 | `SOFTWARE` | UTF-8: product and version |
| 3 | `STARTED` | u64: when it started (UTC ms) |
| 4 | `CPU` | u16: process CPU in per mille of the machine; 0xFFFF unknown |
| 5 | `HEAP_USED` | u64: octets |
| 6 | `HEAP_MAX` | u64: octets |
| 7 | `GATEWAYS` | u16: running gateways |
| 8 | `MEMBER_HASH` | 4 octets: its member list hash (as `MEMBER_HASH` in the handshake) |
| 9 | `SESSIONS` | u16: the sessions it has members in |
| 10 | `NOW` | u64: when it was sent (UTC ms) |

## 8. PDU sessions (0x0100–0x01FF)

Gateways on different servers share DIS PDUs, as they are, in a named **session**. A gateway joins with a `j2j` endpoint; its server is a *member node* of the session. The session key is the node-key rule applied to the session name (§6).

### 8.1 SESSION_STATE (0x0100)

Class 0 (admin), unsequenced. A server's members of every session, **all of them each time**: a member no longer listed has left. Sent on a change, every 15 s, and to a server when it becomes reachable.

```
u64 boot          random per start of the sender's session layer
u64 counter       increases with every SESSION_STATE of that boot
u16 n
n × ( u16 length | TLVs )     one member
```

A receiver keeps the last state per sender and ignores one with the same `boot` and a counter not above the last; a new `boot` replaces everything of that sender.

| Tag | Name | Value |
|---|---|---|
| 1 | `SESSION` | UTF-8: the session name (1-64 of letters, digits, `.`, `_`, `-`) |
| 2 | `MEMBER` | u64: the member's key, random per join |
| 3 | `GATEWAY` | UTF-8: the gateway's name on its server |
| 4 | `INSTANCE` | UTF-8: its instance (may be empty) |
| 5 | `ENDPOINT` | UTF-8: the endpoint's id |
| 6 | `KIND` | u8: 1 = PDU session, 2 = object session (§9); members of other kinds are skipped by a receiver that does not know them |
| 7 | `MODEL` | UTF-8: what the members must agree on: for a PDU session `DIS <version>, exercise <n>`, for an object session the model's fingerprint (§9) |
| 8 | `SINCE` | u64: when it joined (UTC ms) |

A member whose `KIND` or `MODEL` differs from a receiver's own member of the session is a *mismatch*: nothing is sent to it, and nothing from it is delivered.

### 8.2 SESSION_PDU (0x0110)

One DIS PDU of a member, sent once to every member node of the session with the same model (unicast, along the routes):

```
u64 session key
u64 member key    the sender's member
u8  flags         0; others reserved
…   the PDU       DIS of the session's version and exercise
```

- **Class:** Entity State (type 1) and Entity State Update (type 67): best effort, with the extension `SUPERSEDE` = session key | member key | the PDU's entity id (6 octets), so a link that cannot keep up sends the newest state of each entity. Every other PDU: reliable, on stream `2 + (session key >> 34 & 0x3FFFFFFF)`, so a session's PDUs arrive in order. A member may send all PDUs reliable.
- **Receiving:** a PDU is delivered to the receiver's members of the session if its sender is a known member (from its `SESSION_STATE`) of the same model; otherwise it is dropped.
- **Lost members (informative):** SimWeaver marks the members of a server that has had no route for `j2j.session.lostseconds` (10 s) *lost*. Nothing is deleted: DIS receivers time entities out.
- **Loops (informative):** a SimWeaver member does not send a PDU it received from the session within the last 10 s (compared without the version and exercise octets), so two gateways bridging the same DIS network do not hand PDUs back and forth.

## 9. Object sessions (0x0200–0x02FF)

The members share the objects and interactions of an HLA object model. Membership is `SESSION_STATE` (§8.1) with `KIND` 2.

- **Wire ids:** a class, attribute or parameter is identified by the first 4 octets (u32) of SHA-256 of its qualified name in UTF-8: `HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft`, an attribute as its declaring class's name + `.` + its name (`HLAobjectRoot.BaseEntity.EntityType`), `HLAinteractionRoot.WeaponFire`, `HLAinteractionRoot.WeaponFire.FireMissionIndex`. A member checks at the join that its model has no two names of one kind with the same id.
- **Model (`MODEL`):** `HLA objects <fingerprint>` and optionally ` (<FOM families>)`. The fingerprint is 8 hex digits of SHA-256 of the sorted lines `O|<class>`, `A|<attribute>|<datatype name>`, `I|<interaction class>`, `P|<parameter>|<datatype name>`, joined by `\n`. Members compare the whole text.
- **Values** are HLA-encoded as the model's datatypes say, as an RTI carries them.
- **Objects** are identified by (sender member key, u64 object id); the id is the sender's own.

Every message starts with `u64 session key | u64 member key (sender) | u8 flags (0)`; then:

| Type | Name | Rest | Class |
|---|---|---|---|
| 0x0200 | `OBJ_CREATE` | `u64 object` \| `u32 class` \| `u16 n` \| name (UTF-8, n octets) \| values | reliable |
| 0x0201 | `OBJ_UPDATE` | `u64 object` \| values | reliable, or best effort with `SUPERSEDE` = session key \| member key \| object \| u32 hash of the attribute ids (attributes whose transportation is best effort) |
| 0x0202 | `OBJ_DELETE` | `u64 object` | reliable |
| 0x0203 | `OBJ_INTERACTION` | `u32 class` \| values (the parameters) | reliable, or best effort (as the interaction's transportation) |
| 0x0204 | `OBJ_PROVIDE` | `u64 owner member` \| `u64 object` \| `u16 n` \| n × `u32 attribute` | reliable, to the owner's server |
| 0x0205 | `OBJ_SNAPSHOT` | `u64 target member` (0 = every member of the session on that server) | reliable, to that server |

`values` = `u16 n` | n × (`u32 id` | `u32 length` | value). Reliable messages of a session use the stream of §8.2, so a create comes before the updates sent after it.

- **Receiving:** messages are delivered to the receiver's members of the session if the sender is a known member of the same kind and model. An update for an unknown object (best effort before its create) is dropped; ids not in the receiver's model are left out.
- **Creating twice** (a snapshot of an object the receiver knows) only updates its values.
- **Snapshots:** a member that joins sends `OBJ_SNAPSHOT` to every server with members of the session (after its `SESSION_STATE`), and a member sends one to a member that joins or whose server is back after being lost. The receiver answers with an `OBJ_CREATE` with the last values of each of its objects, to the asking server only.
- **Leaving, lost, restarted:** when a member is no longer in its server's `SESSION_STATE`, its server is lost (informative: no route for `j2j.session.lostseconds`), or its server's `boot` changes, the receivers delete its objects.

## 10. Change log

| Date | Change |
|---|---|
| 2026-10-28 | Version 1: frames, handshake, encryption, message envelope (0.10.0 draft) |
| 2026-10-28 | Version 1, revision 2: UDP datagrams (feature `udp`), the TLS last resort, the channel replacement rule, sequenced delivery, the network messages, routing (informative) |
| 2026-10-28 | Version 1, revision 3: `NODE_STATUS` documented (§7.4); PDU sessions (§8); `CLOSE REKEY` may come before the new channel (§5); routing notes (§7.3) |
| 2026-10-28 | Version 1, revision 4: object sessions (§9), `KIND` 2 |

---

## Appendix A. Test vectors

### A.1 Inputs

| | |
|---|---|
| network id | `example-net` |
| members | `sto-1` (address 10.10.0.5) and `got-2` (address 10.20.0.7); no names, sites or fallbacks |
| initiator / responder | `sto-1` / `got-2` |
| network key `k1` | `000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f` |
| I's ephemeral private key | `a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf` |
| R's ephemeral private key | `c0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedf` |
| BOOT of I / R | 1 / 2 |
| SOFTWARE | `SimWeaver 0.10.0` on both sides |
| FEATURES | empty on both sides |
| first DATA message | class 0, flags 0, ttl 1, source = node key of `sto-1`, destination = node key of `got-2`, stream 0, sequence 1, type 0x0001, no extension, payload `hi` |

The member hash in the vectors is SimWeaver's own: the first 4 octets of SHA-256 over its canonical member list (one line per member sorted by id: `id|name|site|address,…|fallback|connect`). Another implementation may hash differently: the value is only compared.

### A.2 Values

**member hash:** `31374ef5`

**I's ephemeral public key:** `605a725d2a4adfeeb1a29e17edd621c1b7593ee8cdbc44ac6c4ab6e2f805d23c`

**R's ephemeral public key:** `dc2cca31e8e43bbd91dff7e475cca3347eb478107d5bd765aba4ae4a30c35d44`

**HELLO body** (124 octets):

```
4a324a01010001000b6578616d706c652d6e65740002000573746f2d31000300
05676f742d32000400026b3100050020605a725d2a4adfeeb1a29e17edd621c1
b7593ee8cdbc44ac6c4ab6e2f805d23c00070008000000000000000100080000
0009001053696d57656176657220302e31302e30000a000431374ef5
```

**WELCOME body** (132 octets; the last 36 are the AUTH TLV):

```
0100020005676f742d32000400026b3100050020dc2cca31e8e43bbd91dff7e4
75cca3347eb478107d5bd765aba4ae4a30c35d44000700080000000000000002
000800000009001053696d57656176657220302e31302e30000a000431374ef5
0020002053e23b196e8e53f68aad9f7ee424e1dc763c49f1ceebd4b5ffe7eb31
a01df652
```

**FINISH body:**

```
002000203341ccc9ee2391a6dfb2bfcd2ecdcabc4ee852f4a1c24afc6920f17e
dbf7a530
```

**key I→R:** `2480e7893608536d8009165ba83009d8b16bab784cbe3cf64e9bb9a581fd203e`

**key R→I:** `760466c2b1935dec96642aad13b25237dff2b14bf718b489ad22f968a8ff239b`

**First DATA frame I → R** (whole frame, counter 0):

```
00000040100000000000000000005a279a77c54f5f3b2f3f1dc35afc775955a7
2735fd4e8fcc6e46d55bd1be8809bd2a159aca9bea3d8a390f2b084bfd2b871b
419c3374
```

**UDP key I→R:** `2a2781a7d2034b5f54f6565747685ade50f934281b0308fda83e886852d11cf8`

**UDP id I→R / R→I:** `7575db19` / `c58a12fd` (hex u32)

### A.3 Session keys and wire ids

| Name | Value |
|---|---|
| session key of `north-pdu` (§8) | `cf0edd164cc5411b` |
| wire id of `HLAobjectRoot.BaseEntity.PhysicalEntity.Platform.Aircraft` (§9) | `2a881512` |
| wire id of `HLAobjectRoot.BaseEntity.EntityType` | `62b62d1c` |

---

By SimWeaver - released under the BSD 3-Clause License.
