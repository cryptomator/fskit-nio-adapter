# fskit-nio-adapter

Provides directory contents specified by a `java.nio.file.Path` as an [FSKit](https://developer.apple.com/documentation/fskit) volume on macOS 27 and later, without third-party kernel or FUSE software.

The adapter registers as an `org.cryptomator.integrations.mount.MountService` named `FSKit (Experimental)`. It is an opt-in provider for early testers.

## Architecture

FSKit runs a file system as a sandboxed app extension launched by `fskitd`, while `MountService.forFileSystem(Path)` hands over a live `Path` in the caller's JVM. So the JVM that calls `mount()` serves the file system, and a thin Swift extension forwards each FSKit operation to it over an authenticated loopback TCP connection:

```mermaid
flowchart LR
    Kernel["kernel / fskitd"] --> Ext["Swift extension (FSKitNioExtension)"]
    Ext -->|bridge protocol over loopback TCP| Session["BridgeSession (calling JVM)"]
    Session --> Ops["FileSystemOperations"]
    Ops -->|java.nio.file| Path["Path to mount"]
```

- `mount()` starts a session on an ephemeral loopback port, writes the port and a random token into a manifest in an owner-only directory under the temp directory, and runs `/sbin/mount -F -t cryptomatorfs <that directory> <mountpoint>`.
- The extension reads the manifest, connects and presents the token. From then on it forwards every operation. The only file system state it holds is a map from node ids to FSKit items and the volume's size and free space as the server last reported them.
- The item table, name handling, directory listings and open channels live in Java (`org.cryptomator.frontend.fskit.fs`).
- Each volume serves its requests one at a time, end to end. Separate mounts are independent.
- The wire format is specified in [`protocol/PROTOCOL.md`](protocol/PROTOCOL.md).

This repository ships the Maven artifact `org.cryptomator:fskit-nio-adapter`, the extension sources in `fskit/`, and a stand-in host app for local testing. The mount only works while an app containing the extension is installed and the extension is enabled.

## Build and test

Java (JDK 25, on macOS or Linux, no Swift tooling needed):

```
./mvnw verify
```

The build fails on a compiler warning.

Swift (macOS 27 SDK, Swift 6.2 or later):

```
cd fskit
swift test                  # codec tests against protocol/vectors, and client tests
scripts/interop-test.sh     # Swift client against the Java server, nothing is mounted
scripts/process.sh          # SwiftFormat and SwiftLint, both have to be installed
```

The Swift sources follow [SwiftFormat](https://github.com/nicklockwood/SwiftFormat) and [SwiftLint](https://github.com/realm/SwiftLint), configured in `fskit/.swiftformat` and `fskit/.swiftlint.yml`.

To format and check what a commit contains, create `.git/hooks/pre-commit` with this content and make it executable:

```sh
#!/bin/sh
./fskit/scripts/process.sh --staged
```

When the hook reformats a staged file, it stops the commit so that the result can be reviewed. Commit again to go on.

## Install the extension

```
cd fskit
CODESIGN_IDENTITY="Apple Development: …" \
PROVISIONING_PROFILE=<profile for the extension's bundle identifier> \
scripts/package.sh

ditto build/FSKitNioHost.app /Applications/FSKitNioHost.app
codesign --verify --deep --strict /Applications/FSKitNioHost.app  # must pass
open /Applications/FSKitNioHost.app
```

Then enable "Cryptomator FSKit File System Extension" in System Settings > General > Login Items & Extensions > File System Extensions (in the "By Category" view). `pluginkit -m | grep -i fskit` lists the extension once it is registered. macOS switches the extension off again when a reinstalled build has a changed `Info.plist`.

For a file system type that no extension provides, `mount` exits with status 69 and `mount: Unable to invoke task`. With the extension disabled, it additionally prints `Module <bundle identifier> is disabled!`.

`package.sh` reads these environment variables:

| Variable | Default | Meaning |
| --- | --- | --- |
| `CODESIGN_IDENTITY` | `-` (ad-hoc) | Signing identity |
| `PROVISIONING_PROFILE` | none | Profile to embed into the extension |
| `HOST_BUNDLE_ID` | `org.cryptomator.fskit.host` | Bundle identifier of the host app. The extension's is this plus `.extension`. |
| `FS_TYPE_NAME` | `cryptomatorfs` | File system type name, as passed to `mount -t` |
| `SKIP_SWIFT` | unset | Set to `1` to skip `swift build` |

A build with a different `FS_TYPE_NAME` needs the JVM started with `-Dorg.cryptomator.frontend.fskit.fsType=<name>`.

### Signing

The extension carries the restricted `com.apple.developer.fskit.fsmodule` entitlement, which must be backed by a provisioning profile that authorizes it for the extension's bundle identifier. ExtensionKit refuses to launch an unprovisioned extension, so an ad-hoc build compiles and registers but cannot mount. Any Apple-issued identity works, including a free "Apple Development" certificate. Hardened runtime and notarization are not needed for local development.

A broken seal fails opaquely: `fskitd` does not launch a bundle whose signature is invalid, and the only symptom is `mount: Unable to invoke task`. After replacing the installed app, always check it with `codesign --verify --deep --strict`.

## Mirroring test

With the extension installed and enabled, `MirroringFSKitMountTest` mounts directories or a vault interactively. In either mode the JVM logs every request with its response at trace level.

Mirror one or more directories (prompts for pairs of directory and mount point, and for read-only):

```
./mvnw test -Pmirror
```

Mirror a vault (prompts for the vault, its passphrase, the mount point, and for read-only):

```
./mvnw test -Pcrypto-mirror
```

## Smoke test

With the extension installed and enabled, and built with the default `FS_TYPE_NAME`, the smoke test mounts directories and a throwaway vault, works on them through the shell, and scans the JVM's log for failures:

```
fskit/scripts/smoke-test.sh
```

It does not cover Finder, a disabled extension, a killed extension process or a killed JVM. The script's header lists its arguments and exit statuses.

## Known limitations

Observed on macOS 27.0.1 with JDK 26.

- A mounted volume may not be confined to the user who mounted it. macOS mounts it with `noowners`, and the extension checks no caller identity, so another local account that can reach the mount point may be able to read and write it. That is untested, so mount only data that other accounts on the Mac may see.
- Finder's Trash creates `.Trashes` in the volume root, which appears in the mounted `Path`.
- The name `.fseventsd` in the volume root is taken. The volume shows a directory of that name with an empty file `no_log` in it, which keeps macOS from creating the directory in the mounted `Path` and storing its log of file system events there. Neither is stored or can be changed, and the root's listing leaves the directory out. An entry named `.fseventsd` that the mounted `Path` already holds cannot be reached through the volume and stays as it is. Without that log, macOS has no event history for the volume, and an FSEvents stream created relative to the device reports absolute paths. Events are still delivered as they happen.
- Symbolic links are shown but cannot be read, followed or created. Hard links cannot be created.
- No extended attributes. macOS stores attributes in AppleDouble companion files named `._<name>` next to the file instead, which appear in the mounted `Path` as well. `xattr -w` therefore succeeds, and:
  - A process whose files are tagged with `com.apple.provenance` creates a companion for every file it creates.
  - The kernel removes a companion together with its file. `rm -r` may therefore report `No such file or directory` for a companion it had listed, and exit with status 1 although everything is removed.
  - An attribute set on a removed file that is still open leaves an orphaned companion behind.
  - Tools that scan their own directories see the companions. In a git repository on the volume, `git fsck` reports the ones inside `.git` as invalid refs and objects, and `git status` lists the others as untracked.
- One slow operation stalls its volume, since requests are served one at a time.
- A FIFO or a socket in the mounted `Path` is shown as a regular file. Opening the socket fails with "Input/output error". Opening the FIFO stalls the whole volume until a process opens it for writing in the mounted `Path` itself, not through the volume.
- Nothing else may modify the mounted `Path` while it is mounted.
- The mounted `Path` must not hold two names that differ only in Unicode normalization.
- The volume is case-sensitive: on a case-insensitive backing store, a name that differs from an existing one only in case is reported as absent and cannot be created.
- Replacing a file that is open (`mv` onto it) fails with "Resource busy" on a vault, because cryptofs refuses to replace an open file. It works on a plain directory.
- A time or permission change on a removed file that is still open is ignored.
- A modification or access time cannot be set on a file or directory whose owner may not read it: `touch` fails with "Permission denied", because the JDK opens the entry for reading to set its times.
- One request can change several attributes, as `setattrlist` does. When the backing store refuses one of them after another has been applied, the request still succeeds, and only the JVM's log reports the refusal.
- A file or directory is created even when the backing store refuses to set its mode. It then keeps the mode it was created with, which the umask of the JVM may have cut down. Only the JVM's log reports the refusal.
- A modification or access time later than 11 April 2262, 23:47:16 UTC, is set to that time, the latest the JDK sets.
- A rename that replaces an existing entry is atomic only when a file replaces a file. With a directory or a symbolic link on either side, the target is removed first and is gone if the move then fails.
- A file that was opened for writing only and then loses its owner-write permission cannot be opened for reading until it is closed.
- A mount does not survive its JVM. When the JVM dies, operations on the volume fail with an I/O error at once, and `umount -f` removes the mount. The directory holding the manifest stays in the temp directory.
- A mount does not survive its extension process either. When the process dies, macOS removes the mount at once, and an operation in flight fails with "Device not configured". The mount point is a plain directory again, so whatever is written to it afterwards lands in that directory, not in the mounted `Path`.
- The provider is offered on every Mac running macOS 27 or later, whether or not the extension is installed and enabled. If it is not, `mount()` fails with `MountFailedException`.

## License

This project is dual-licensed under the AGPLv3 for FOSS projects as well as a commercial license for independent software vendors and resellers. If you want to use this library in applications, that are *not* licensed under the AGPL, feel free to contact our [support team](https://cryptomator.org/help/).
