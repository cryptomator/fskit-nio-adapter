# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased
### Added
* Mount provider `FSKit (Experimental)` for macOS 27 and later, which exposes a `java.nio.file.Path` as an FSKit volume
* FSKit file system extension that forwards file system operations to the mounting JVM, and a stand-in host app that embeds it for local testing
