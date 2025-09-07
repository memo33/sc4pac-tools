# Changelog

## [Unreleased]
### Fixed
- an issue where an already occupied port was not detected on Windows (#38)
- a problem in which the profiles configuration directory could not be determined on Windows ([#32][gui32])
  (As a result of this change, in rare cases the default cache location can change from `%USERPROFILE%\sc4pac\cache` to `%LOCALAPPDATA%\io.github.memo33\sc4pac\cache`.)

[gui32]: https://github.com/memo33/sc4pac-gui/issues/32


## [0.8.0] - 2025-06-22
### Added
- new `packages` and `assets` attributes for YAML files, which allows using YAML anchors, aliases and overrides across multiple packages in the same document (#35)
- new `withConditions` attribute for includes/excludes that conditionally depend on variants. This is a new way to define variants, useful for complex packages with many different variant IDs (#36).
- new environment variable `SC4PAC_JAVA_OPTIONS` (optional, see [sc4pac.bat](https://github.com/memo33/sc4pac-tools/blob/3ee578261d9fe7203dd25e3ffef8fc88bc2c1c14/src/scripts/sc4pac.bat#L34-L39)) to configure Java command line options, allowing to avoid some deprecation warnings caused by Java 24+.
- new experimental `sc4pac test` command for testing whether packages can be installed without issues (#37)

### Fixed
- improved error handling while reading or writing JSON files
- improved error handling in API websocket communication

### Changed
- API upgraded to 2.6:
  - added `variantChoices` to `/packages.info` output
  - added new `/variants.choices` and `/variants.set` endpoints
  - added `osVersion` to `/server.status` endpoint


## [0.7.0] - 2025-05-11
### Added
- new `conflicting` attribute in YAML files, a list of conflicting packages (#11). Packages that are in conflict with each other cannot be installed at the same time.
- support for searching by author name (#20)
- support for selecting a local file from disk as fallback if the download of an asset failed (e.g. when a file was rehosted at a different URL)
- When DLL files are installed, a new dialog now informs about the origin of the DLLs (#18).

### Fixed
- a problem with handling version numbers containing spaces.

### Changed
- If a package uses a per-package variant of another package, the variant selection dialog now looks for the `variantInfo` field in both packages (#32).
  From now on, the `variantInfo` for a per-package variant only needs to be defined in the one package it belongs to, rather than everywhere it is used.
- API upgraded to 2.5:
  - added a new `/prompt/choice/update/remove-conflicting-packages` message to `/update`
  - added a new `/prompt/json/update/initial-arguments` message to `/update`
  - added a new `/prompt/json/update/download-failed-select-mirror` message to `/update`
  - added a new `/prompt/confirmation/update/installing-dlls` message to `/update` (#18)
  - added support for `externalIds` in `/packages.search.id` ([#26][gui26])
  - output format changes in `/packages.search` and `/packages.search.id`
  - message `/prompt/choice/update/variant` now informs about `previouslySelectedValue` and `importedValues` if applicable
  - added new `/plugins.export` endpoint

[gui26]: https://github.com/memo33/sc4pac-gui/issues/26


## [0.6.1] - 2025-04-13
### Fixed
- a rare problem where the default profiles configuration directory could not be located ([#25][gui25]).
  As a workaround, the directory path can now also be configured using the new environment variable `SC4PAC_PROFILES_DIR`, as an alternative to the `--profiles-dir` launch parameter.
- a sporadic issue in which corrupted downloaded files stored in the download cache could block normal execution of the program (#34).

### Changed
- removed support for cookie-based authentication

[gui25]: https://github.com/memo33/sc4pac-gui/issues/25


## [0.6.0] - 2025-03-09
### Added
- support for token-based authentication to Simtropolis (#31)
- The [Simtropolis Channel](https://community.simtropolis.com/forums/topic/763620-simtropolis-x-sc4pac-a-new-way-to-install-plugins/) has been added to the default channels.
- new `extract` command for extracting generic archive files (#30)

### Changed
- When calling `update`, a new dialog allows to remove unresolvable packages if any (e.g. when packages have been renamed or deleted from a channel) ([#24][gui24]).
- API upgraded to 2.4:
  - added a new `/prompt/confirmation/update/remove-unresolvable-packages` message to `/update`
  - format changes in `/variants.list` (#7)

[gui24]: https://github.com/memo33/sc4pac-gui/issues/24


## [0.5.4] - 2025-02-08
### Added
- support for descriptions of variant IDs. The new `variantInfo` field should be used instead of `variantDescriptions` in newly written metadata ([#2][actions2]).
- support for default choices of variants ([#2][actions2]).

### Fixed
- added support for extracting nested .rar files (#27).
- an issue involving symbolic links on Windows when Plugins are stored on another drive (#28).

### Changed
- API upgraded to 2.3:
  - changes to format of `/prompt/choice/update/variant` message
  - added `notCategory` and `ignoreInstalled` options to `/packages.search` ([#20][gui20])

[actions2]: https://github.com/memo33/sc4pac-actions/issues/2
[gui20]: https://github.com/memo33/sc4pac-gui/issues/20


## [0.5.3] - 2025-01-26
### Fixed
- an issue that lead to downloading progress not being displayed in the GUI ([#19][gui19]).

[gui19]: https://github.com/memo33/sc4pac-gui/issues/19


## [0.5.2] - 2025-01-23
### Fixed
- an issue that could lead to concurrent API calls getting blocked by a locked cache if they requested downloading the same file simultaneously (typically the channel contents file).
- improved error handling in case of lack of permissions to access files or directories.

### Changed
- The `sc4pac` launch scripts now include a check for whether Java is installed.
- When the auto-shutdown option is used, the server now also shuts down after launch if no initial connection is established within a timeout interval.
  For the web-app, this means you need to open the browser within 60 seconds.
- For the `sc4pac server` command, the default `--profiles-dir` path has changed to a platform-dependent config directory instead of the current working directory.
  This means that multiple independent installations of sc4pac or sc4pac-gui would use the same `profiles` directory by default.
- The `exclude` patterns are now also matched against nested archives, like .jar or .exe files, to allow circumventing nested extraction if needed.
- API upgraded to 2.2:
  - new `/profiles.switch` endpoint
  - new `/packages.search.id` endpoint


## [0.5.1] - 2024-12-26
### Added
- Added `--launch-browser` option to the `server` command, which opens the web-app in the browser on start-up ([#3][gui3]).

### Fixed
- an issue where building a channel with file names containing spaces failed.
- Handling of malformed profile JSON files has been improved ([#4][gui4]).
- The auto-shutdown functionality of the server now handles multiple connections and page reloads of the web-app ([#10][gui10]).

### Changed
- The channels now include a URL for creating a new GH issue associated to a specific package (derived from `--metadata-source-url`).
- The metadata now supports a `websites` field for cases when there is more than one `website` (#22).
  Only the new field is used in JSON files, while YAML files may continue to use `website` in place of `websites`, for backward compatibility.
- API upgrade to 2.1:
  - `/update` accepts a new parameter `refreshChannels` to clear cached data ([#14][gui14]).
  - `/packages.search` supports a new parameter `channel` to filter the results ([#1][gui1]).
    Moreover the output format of `/channels.stats` has changed.
  - New `/packages.open` endpoint for externally instructing the GUI to open a specific package page (#21).
    The main channel website now shows an "Open in App" button for each package.

[gui1]: https://github.com/memo33/sc4pac-gui/issues/1
[gui3]: https://github.com/memo33/sc4pac-gui/issues/3
[gui4]: https://github.com/memo33/sc4pac-gui/issues/4
[gui10]: https://github.com/memo33/sc4pac-gui/issues/10
[gui14]: https://github.com/memo33/sc4pac-gui/issues/14


## [0.5.0] - 2024-12-14
### Added
- Added `--label` and `--metadata-source-url` options to the `channel build` command. Use these particularly for publicly accessible channels.
- Added "Channel" label, "Metadata" URL and "Required By" fields to the `info` command output.
- Channels now keep track of inter-channel dependencies. In particular, the "Required By" field includes packages from all channels.
- A few channel stats have been added to the channel JSON file, such as which categories contain how many packages.
- The lock file includes some new fields such as `installedAt` and `updatedAt`.
- You can search by STEX and SC4E URLs now to find corresponding packages.
- Several new `server` command options for use in combination with the GUI.

### Fixed
- an issue affecting some old terminals in which the escape sequences used for displaying progress bars were incorrectly printed to the console (#8)
- an issue that could cause warning messages to mess up the prompt display (#5)
- an issue that prevented selecting some variants if a prompt had 10+ variants (#12)
- an issue in which an interrupted internet connection was not handled gracefully

### Changed
- The variant `IRM.industrial-capacity` was renamed to `toroca:industry-quadrupler:capacity`.
- improved error message if channel-build fails randomly in case old files could not be removed (#6)
- improved `sc4pac` bash script to allow symlinking into path on Linux/macOS
- The progress spinner animation was switched to ASCII symbols for compatibility with non-Unicode fonts in some terminals.
- The metadata text fields `description` etc. are now rendered as Markdown (#14, #15).
  For correct text wrapping, multiline text blocks should start with `|` instead of `>`, from now on.
- Installing DLLs now requires a checksum (#13, #17). Only DBPF files can be installed without checksum.
  New fields `withChecksum`, `checksum` and `nonPersistentUrl` have been added to the metadata.
- The fuzzy search algorithm was changed to improve results for partial matches.
- The `contents` array of the channel JSON file has been split into new `packages` and `assets` fields.
- The API was upgraded to version 2.0:
  * many API endpoints now require a `profile` parameter
  * initializing a profile now requires a `temp` folder parameter
  * several new endpoints and backward incompatible changes
  * the server can store settings for the client
  * authentication cookie can now be set by the client


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

[Unreleased]: https://github.com/memo33/sc4pac-tools/compare/0.8.0...HEAD
[0.8.0]: https://github.com/memo33/sc4pac-tools/compare/0.7.0...0.8.0
[0.7.0]: https://github.com/memo33/sc4pac-tools/compare/0.6.1...0.7.0
[0.6.1]: https://github.com/memo33/sc4pac-tools/compare/0.6.0...0.6.1
[0.6.0]: https://github.com/memo33/sc4pac-tools/compare/0.5.4...0.6.0
[0.5.4]: https://github.com/memo33/sc4pac-tools/compare/0.5.3...0.5.4
[0.5.3]: https://github.com/memo33/sc4pac-tools/compare/0.5.2...0.5.3
[0.5.2]: https://github.com/memo33/sc4pac-tools/compare/0.5.1...0.5.2
[0.5.1]: https://github.com/memo33/sc4pac-tools/compare/0.5.0...0.5.1
[0.5.0]: https://github.com/memo33/sc4pac-tools/compare/0.4.5...0.5.0
[0.4.5]: https://github.com/memo33/sc4pac-tools/compare/0.4.4...0.4.5
[0.4.4]: https://github.com/memo33/sc4pac-tools/compare/0.4.3...0.4.4
[0.4.3]: https://github.com/memo33/sc4pac-tools/compare/0.4.2...0.4.3
[0.4.2]: https://github.com/memo33/sc4pac-tools/compare/0.4.1...0.4.2
[0.4.1]: https://github.com/memo33/sc4pac-tools/compare/0.4.0...0.4.1
[0.4.0]: https://github.com/memo33/sc4pac-tools/compare/0.3.0...0.4.0
[0.3.0]: https://github.com/memo33/sc4pac-tools/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/memo33/sc4pac-tools/compare/0.1.5...0.2.0
[0.1.5]: https://github.com/memo33/sc4pac-tools/compare/0.1.4...0.1.5
