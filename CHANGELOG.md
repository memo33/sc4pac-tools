# Changelog

## [Unreleased]
### Added
- a few channel stats have been added to channel JSON file, such as which categories contain how many packages


## [0.4.5] - 2024-10-17
### Fixed
- an extraction failure affecting Clickteam exe installers containing files in subfolders
- an encoding issue that could affect console output on Windows with Java 19+

### Changed
- The naming convention for per-package variants was changed.
  ```
  SFBT.tree-family         -> sfbt:essentials:tree-family
  USL.light-color          -> kodlovag:uniform-street-lighting-mod:light-color
  nam-slope-mod.difficulty -> nam-team:tunnel-and-slope-mod:difficulty
  CETC.mode                -> 11241036:central-european-tree-controller:mode
  ```
  Once you update, pick the same variants you already installed before. The old ones can be deleted:
  ```
  sc4pac variant reset SFBT.tree-family USL.light-color nam-slope-mod.difficulty CETC.mode
  ```


## [0.4.4] - 2024-08-11
### Fixed
- support for Apple silicon M1 (#4) (previously, some packages using NSIS exe installers could not be extracted on that platform).
- a rare issue that could arise on platforms with a non-English locale (Turkish, in particular).

### Changed
- The subfolder `050-early-mods` was renamed to `050-load-first` for clarity. The old folder can safely be deleted.
- improved detection of ouf-of-date metadata JSON files. (The channels now provide checksums for each JSON file, so that the locally stored metadata is refreshed when necessary. This helps keep some informative data in sync, such as images or reverse dependencies.)
- internal refactoring and maintenance.


## [0.4.3] - 2024-05-21
### Added
- warning about outdated metadata, in case an inclusion/exclusion pattern does not match any files in an Asset anymore.
- support for rendering package identifiers in metadata description text, using syntax `` `pkg=group:name` ``.

### Changed
- The `channel add/remove` commands now show a message about the result of the command.
- The instructions for setting the Simtropolis authentication cookie have been moved to the file
  [sc4pac.bat](https://github.com/memo33/sc4pac-tools/blob/main/src/scripts/sc4pac.bat#L13-L32).
- The default `include` filter has been changed to include only plugin files (`.sc4*`/`.dat`/`.dll`) instead of arbitrary file extensions.
  This ensures that non-plugin files are not accidentally installed if a custom `exclude` filter is specified.
  (The default `exclude` filter remains unchanged and excludes any non-plugin files (`.sc4*`/`.dat`/`.dll`).)

### Fixed
- a bug causing assets containing Clickteam installers to be reinstalled whenever running `sc4pac update`.
- an error arising when building a channel containing empty YAML documents.
  The error handling for syntactically invalid YAML files is more graceful now, as well.
- The dates in the `lastModified` field are now more lenient in terms of surrounding whitespace.
- an issue in which incomplete variant definitions were not detected
- a rare bug in which the variant selection was not stored if all packages were up-to-date


## [0.4.2] - 2024-04-18
### Added
- support for extracting Clickteam exe-installers using the external program `cicdec.exe`.
  On macOS and Linux, this requires [mono](https://www.mono-project.com/docs/getting-started/install/) to be installed on your system.
  Assets containing Clickteam installers must include the new `archiveType` property in the metadata.

### Changed
- decreased caching period of channel table-of-contents file from 24 hours to 30 minutes to receive package updates sooner
- The API was upgraded to version 1.2.
- The `sc4pac server` option `--scope-root` was renamed to `--profile-root` and the corresponding error to `/error/profile-not-initialized`.


## [0.4.1] - 2024-03-25
### Added
- support for extracting `.rar` files

### Fixed
- The path to the file `sc4pac.bat` may now contain spaces.
- an issue when extracting 7zip files or exe installers containing multiple folders


## [0.4.0] - 2024-03-16
### Added
- support for installing DLL plugins
- support for assets consisting of a single file (`.dat`/`.sc4*`/`.dll`) that has not been zipped
- an option `-y, --yes` for the update command to accept default answers

### Changed
- The API was upgraded to version 1.1.
- The API now sends `/error/profile-not-initialized` & `/error/init/not-allowed` with HTTP status code 409 instead of 405.
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

[Unreleased]: https://github.com/memo33/sc4pac-tools/compare/0.4.5...HEAD
[0.4.4]: https://github.com/memo33/sc4pac-tools/compare/0.4.4...0.4.5
[0.4.4]: https://github.com/memo33/sc4pac-tools/compare/0.4.3...0.4.4
[0.4.3]: https://github.com/memo33/sc4pac-tools/compare/0.4.2...0.4.3
[0.4.2]: https://github.com/memo33/sc4pac-tools/compare/0.4.1...0.4.2
[0.4.1]: https://github.com/memo33/sc4pac-tools/compare/0.4.0...0.4.1
[0.4.0]: https://github.com/memo33/sc4pac-tools/compare/0.3.0...0.4.0
[0.3.0]: https://github.com/memo33/sc4pac-tools/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/memo33/sc4pac-tools/compare/0.1.5...0.2.0
[0.1.5]: https://github.com/memo33/sc4pac-tools/compare/0.1.4...0.1.5
