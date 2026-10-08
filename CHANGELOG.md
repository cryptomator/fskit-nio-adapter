# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased](https://github.com/cryptomator/fskit-nio-adapter/compare/0.1.0...HEAD)

No changes yet.


## [0.1.0](https://github.com/cryptomator/fskit-nio-adapter/releases/tag/0.1.0) - 2026-10-08
### Added
* Mount provider `FSKit (Experimental)` for macOS 27 and later, which exposes a `java.nio.file.Path` as an FSKit volume
* FSKit file system extension that forwards file system operations to the mounting JVM, and a stand-in host app that embeds it for local testing
* The extension, unsigned, as a zip with the classifier `appex` next to the jar, for apps to embed and sign
* Within an app, the provider is offered only if the app embeds the extension, and a mount with the extension switched off opens System Settings at File System Extensions and asks to switch it on
