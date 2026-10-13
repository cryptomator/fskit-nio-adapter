# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased](https://github.com/cryptomator/fskit-nio-adapter/compare/0.3.0...HEAD)

No changes yet.


## [0.3.0](https://github.com/cryptomator/fskit-nio-adapter/releases/tag/0.3.0) - 2026-10-13
### Added
* `Mount#whenTerminated()` (`WHEN_TERMINATED`, from integrations-api 1.10.0). It completes once a mount has ended for any reason: unmounted through the provider or by someone else, its extension or `fskitd` ended, or its connection failed. It does not complete before every operation still running on the mounted `Path` has returned

### Removed
* The warning and the error in the log when a volume goes away or loses its connection without an unmount through the provider, which `Mount#whenTerminated()` replaces


## [0.2.0](https://github.com/cryptomator/fskit-nio-adapter/releases/tag/0.2.0) - 2026-10-12
### Added
* Mounting without a mount point (`MOUNT_TO_SYSTEM_CHOSEN_PATH`), into a directory the system creates in `/Volumes` and names after the volume in ASCII, for an app with the entitlement `com.apple.developer.fskit.mount`
* A warning in the log when a volume goes away without an unmount through the provider, as when its extension or `fskitd` ends or someone else unmounts it. A mount point the application chose is then a plain directory again, and whatever is written to it lands on the local disk

### Changed
* The extension in the `appex` zip carries the release version as its `CFBundleVersion` as well, where it had `1`

### Fixed
* A mount fails instead of returning a volume that serves nothing when the extension disconnects again right after connecting
* A listing of mounts or a `umount` that does not finish within its timeout is ended, and a listing that never closes its output no longer blocks mounting or unmounting for good


## [0.1.0](https://github.com/cryptomator/fskit-nio-adapter/releases/tag/0.1.0) - 2026-10-08
### Added
* Mount provider `FSKit (Experimental)` for macOS 27 and later, which exposes a `java.nio.file.Path` as an FSKit volume
* FSKit file system extension that forwards file system operations to the mounting JVM, and a stand-in host app that embeds it for local testing
* The extension, unsigned, as a zip with the classifier `appex` next to the jar, for apps to embed and sign
* Within an app, the provider is offered only if the app embeds the extension, and a mount with the extension switched off opens System Settings at File System Extensions and asks to switch it on
