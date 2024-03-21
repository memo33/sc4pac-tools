# Changelog

## [Unreleased]
### Fixed
- The path to the file `sc4pac.bat` may now contain spaces.


## [0.4.0] - 2024-03-16
### Added
- support for installing DLL plugins
- support for assets consisting of a single file (`.dat`/`.sc4*`/`.dll`) that has not been zipped
- an option `-y, --yes` for the update command to accept default answers

### Changed
- The API was upgraded to version 1.1.
- The API now sends `/error/scope-not-initialized` & `/error/init/not-allowed` with HTTP status code 409 instead of 405.
- The API endpoint `/packages.list` now includes a `category` for each package.

### Fixed
- an issue with parsing timestamps affecting Java 8 to 11
- an issue involving symbolic links on Windows
- an issue with handling some malformed zip files
- File extensions of assets are now treated case-insensitively.


## [0.3.0] - 2023-11-19
### Added
- an [API](api.md) for external programs
- new command `sc4pac server` for use with the API
- experimental basic authentication to Simtropolis using `SC4PAC_SIMTROPOLIS_COOKIE` environment variable
  [(usage)](https://github.com/memo33/sc4pac-tools/blob/e5e422252457ababdce450cdadda499a6bfa7dde/src/main/scala/sc4pac/Constants.scala#L39-L57)

### Fixed
- an issue involving local `file:/` URIs


## [0.2.0] - 2023-10-12
### Added
- support for extracting 7z archives and NSIS installers
- support for extracting nested zip files
- a new git repository for all the metadata: https://github.com/memo33/sc4pac
- a website with a dedicated page for each package, e.g. [memo:essential-fixes](https://memo33.github.io/sc4pac/channel/?pkg=memo:essential-fixes)
- support for shared dependencies between variants of a package and more flexible definition of variants

### Changed
- The default channel is now `https://memo33.github.io/sc4pac/channel/`.
  If you have upgraded from an earlier version:
  ```
  sc4pac remove channel github
  sc4pac add "https://memo33.github.io/sc4pac/channel/"
  ```
  Also delete `sc4pac-plugins-lock.json` and remove the folder `coursier/https/raw.githubusercontent.com/` from your cache.
  Then run `sc4pac update`.

### Fixed
- cleaning-up of temp folder


## [0.1.5] - 2023-09-26
…

[Unreleased]: https://github.com/memo33/sc4pac-tools/compare/0.4.0...HEAD
[0.4.0]: https://github.com/memo33/sc4pac-tools/compare/0.3.0...0.4.0
[0.3.0]: https://github.com/memo33/sc4pac-tools/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/memo33/sc4pac-tools/compare/0.1.5...0.2.0
[0.1.5]: https://github.com/memo33/sc4pac-tools/compare/0.1.4...0.1.5
