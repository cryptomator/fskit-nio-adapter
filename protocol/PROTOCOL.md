# Bridge Protocol

The FSKit extension (client) forwards file system operations to the JVM that called `mount()` (server) over a loopback TCP connection. This document is the normative description of protocol version **1**. Both codecs (`org.cryptomator.frontend.fskit.protocol` and `fskit/Sources/FSKitBridge`) implement it and are tested against the examples in `vectors/`.

## Primitives

All integers are big-endian. `u8`, `u16`, `u32`, `u64` are unsigned, `i32` and `i64` are signed (two's complement).

| Type | Encoding |
| --- | --- |
| `string` | `u16` byte length, then that many bytes of UTF-8 |
| `bool` | `u8`, 0 or 1 |
| `timestamp` | `i64` seconds since the epoch, `u32` nanoseconds |
| `type` | `u8`: 1 file, 2 directory, 3 symlink |
| `modes` | `u8` bit set: 1 read, 2 write |

## Frame

```
u32 length          number of bytes that follow
u8  kind            0 request, 1 response
u16 opcode
u64 requestId
u32 controlLength
    control         controlLength bytes
    payload         all remaining bytes of the frame
```

Limits: the control section is at most 64 KiB (65,536 bytes), the payload at most 1 MiB (1,048,576 bytes). A receiver validates `length` and `controlLength` before allocating. It closes the connection on a violation, on an unknown `kind` or `opcode`, and on a control section that is too short for its message or has bytes left over.

## Exchange

- Strictly one request, then its response. The server does not read the next request before it has written the response.
- The client numbers its requests in increasing order. The server echoes `opcode` and `requestId`.
- The server closes the connection on a frame that is not a request. The client closes it on a response whose `kind`, `opcode` or `requestId` does not match the request it sent.
- The first request on a connection must be `HELLO`. The server accepts exactly one connection that completes it.

A response's control section starts with `i32 status`:

| Status | Meaning |
| --- | --- |
| 0 | success; the fields listed for the response follow |
| > 0 | failure; a macOS `errno` value. No further fields follow. |
| -1 | invalid directory cookie or verifier. No further fields follow. |

Only requests to `WRITE` and responses to `READ` have a payload.

## Items

Items are addressed by `u64 nodeId`. 2 is the root directory and 1 its parent, matching FSKit's `FSItem.Identifier.rootDirectory` and `.parentOfRoot`. 3 and 4 are the directory `.fseventsd` and the file `no_log` in it, which the notes on the messages describe. The server assigns every other id from 64 upwards and never reuses one within a connection.

An `attributes` record:

```
type      type
u16       mode          permission bits
u64       size
u64       nodeId
u64       parentId
timestamp modified
timestamp accessed
timestamp created
```

`u64 usableBytes` in a response is the space available in the backing store, read after the operation and any flush it caused. All ones (2^64 - 1) means unknown.

## Messages

| Opcode | Name | Request control | Response control after `status` |
| --- | --- | --- | --- |
| 1 | `HELLO` | `u32 magic`, `u16 protocolVersion`, `token` (32 bytes) | |
| 2 | `STATFS` | | `u64 totalBytes`, `u64 usableBytes` |
| 3 | `LOOKUP` | `u64 parentId`, `string name` | `attributes`, `string name` |
| 4 | `FORGET` | `u64 nodeId` | `u64 usableBytes` |
| 5 | `GETATTR` | `u64 nodeId` | `attributes` |
| 6 | `SETATTR` | `u64 nodeId`, `u8 valid`, `u64 size`, `u16 mode`, `timestamp accessed`, `timestamp modified` | `u8 applied`, `attributes`, `u64 usableBytes` |
| 7 | `READDIR` | `u64 nodeId`, `u64 cookie`, `u64 verifier`, `bool wantAttributes` | `u64 verifier`, `bool more`, `u16 count`, `count` entries |
| 8 | `CREATE` | `u64 parentId`, `string name`, `type type`, `u16 mode` | `attributes`, `string name`, `attributes directoryAttributes`, `u64 usableBytes` |
| 9 | `REMOVE` | `u64 nodeId`, `u64 parentId` | `attributes`, `attributes directoryAttributes`, `u64 usableBytes` |
| 10 | `RENAME` | `u64 nodeId`, `u64 sourceParentId`, `u64 destinationParentId`, `string destinationName` | `string name`, `attributes`, `attributes sourceDirectoryAttributes`, `attributes destinationDirectoryAttributes`, `bool replaced`, `attributes replacedAttributes` if `replaced`, `u64 usableBytes` |
| 11 | `OPEN` | `u64 nodeId`, `modes modes` | |
| 12 | `CLOSE` | `u64 nodeId`, `modes keptModes` | `u64 usableBytes` |
| 13 | `READ` | `u64 nodeId`, `u64 offset`, `u32 length` | `attributes`; payload: the bytes read |
| 14 | `WRITE` | `u64 nodeId`, `u64 offset`; payload: the bytes to write | `u32 written`, `attributes`, `u64 usableBytes` |
| 15 | `SYNC` | | `u64 usableBytes` |

Notes:

- `HELLO`: `magic` is `0x46534B4E` (`FSKN`). The server compares `magic` and `protocolVersion` first and answers a mismatch with `EPROTONOSUPPORT` (43) without interpreting the rest. A `HELLO` that cannot be decoded is answered the same way. The version must match exactly. A wrong token is answered with `EACCES` (13). After a non-zero status the server closes the connection.
- `LOOKUP`, `CREATE` and `RENAME` responses carry the item's name as the backing file system stores it, which may differ from the requested name in Unicode normalization.
- `SETATTR`: `valid` and `applied` are bit sets: 1 size, 2 mode, 4 accessed, 8 modified. Fields whose bit is not set in `valid` are ignored. A size or time the server cannot represent is answered with `EINVAL` (22), and nothing is applied. So is a time whose nanoseconds amount to a second or more. `applied` is the subset of `valid` that the server applied. An attribute that does not apply to the item is left out, as is a `mode` where the backing file system has no POSIX permissions. The server applies the size first. It applies the times before a `mode` that lacks the owner's read permission, and after any other `mode`. When the backing file system refuses an attribute after another has been applied, the response still has status 0, and `applied` leaves out the refused attribute and any not attempted yet. A refusal before any attribute has been applied is answered with its status.
- `CREATE` accepts the types file and directory. The server applies `mode` as given. If the backing file system refuses that once the item exists, the response still has status 0. A created file is open for reading and writing from then on, as after an `OPEN` with both modes, so that whoever creates it can write to it whatever its mode.
- `REMOVE` and `RENAME`: the `attributes` of an item that is removed or replaced are the ones read just before the change. `replaced` is set when the rename replaced an item the client was given a node id for.
- `RENAME`: a rename that replaces nothing, or replaces a file with a file, is done in one step. Where the backing file system cannot do that, as across its file stores, the reply is `EXDEV` (18) and nothing has changed. A rename that replaces a directory or a symbolic link, or moves either onto an existing item, removes the target first. A directory cannot be renamed into itself or below itself (`EINVAL`, 22).
- `CLOSE`: `keptModes` are the modes that stay open. With none kept, the server closes the item's channel.
- `READ`: `length` is at most the payload limit. Fewer bytes than requested mean the end of the file.
- `READ` and `WRITE` larger than the payload limit are split by the client into several requests.
- `SYNC` forces every open channel.
- The root directory holds a directory `.fseventsd` (id 3) with an empty file `no_log` (id 4) in it, which keeps macOS from storing a log of file system events on the volume. The server stores neither, leaves the directory out of the root's listing, and passes no request on to an entry of that name in the backing file system. A `CREATE` of `.fseventsd` in the root is answered with `EEXIST` (17). A request that would change either item, open one for writing, or move an item onto or into the directory is answered with `EPERM` (1). The directory's listing never changes and always has the same verifier.

### Directory listings

A `READDIR` entry:

```
string name
type   type
u64    nodeId
u64    nextCookie
bool   hasAttributes
       attributes      if hasAttributes
```

- A cookie is an index into a listing held by the server and is opaque to the client. Entries 0 and 1 of every listing are `.` and `..`. The root directory's `..` carries the root's own id. With `wantAttributes` set, the server skips those two.
- Cookie 0 always starts a new listing and returns a new verifier, whatever verifier was sent. Any other cookie must come with the verifier of a listing the server still holds.
- A verifier is never 0.
- A cookie equal to the listing's length is the normal end: the response has status 0, no entries and `more` unset.
- A larger cookie, or a non-zero cookie with an unknown verifier, yields status -1.
- A page holds no more entries than fit into the control limit, and may hold fewer. `more` tells whether entries follow the last one of the page.

## Manifest

The server writes a manifest file named `manifest` into the directory that is passed to `mount`. The client reads it to find the server.

```
u16    protocolVersion
u16    port              loopback TCP port
       token             32 bytes
string volumeName
```

## Example messages

Each file in `vectors/` holds one example: its decoded field values, a line `hex:`, and the encoded bytes in hexadecimal (whitespace is insignificant). Lines starting with `#` are comments.

- `frame.txt` is a frame with opaque `control` and `payload`.
- `manifest.txt` is a manifest.
- Every other file is a frame followed by the fields of the message it carries, in wire order: `kind`, `opcode`, `requestId`, then `status` for a response, then the message's fields. Fields that only announce what follows (`count`, `hasAttributes`, `replaced`) have no line of their own: the list elements or the optional record they announce do.

Values are written as follows: integers in decimal, strings as raw text, byte strings and payloads in lowercase hexadecimal, booleans as `true` or `false`, enumerations in lowercase. Fields of a nested record are prefixed with the record's name and a dot, list elements with the list's name and an index in brackets. An absent optional record has no lines. No example uses an integer of 2^63 or more.

The examples are frozen. One changes only together with a bump of `protocolVersion`.
