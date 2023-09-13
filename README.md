sc4pac
======

A package manager for SimCity 4 plugins.

(unstable, work-in-progress, subject to change)


# Usage

- Prerequisites:
  - Java 8+
  - enough disk space
- Download the [latest release](https://github.com/memo33/sc4pac-tools/releases/latest)
  and extract the contents to any location in your user directory (for example, your Desktop).
- Open a shell in the new directory and run the command-line tool `sc4pac` by calling:
  - `sc4pac` in Windows cmd.exe
  - `.\sc4pac` in Windows PowerShell
  - `./sc4pac` on Linux or macOS

  If everything works, this displays a help message.
- Install your first package:
  - `sc4pac add memo:essential-fixes`
  - `sc4pac update`
- Be aware that Simtropolis has a download limit of 20 files per day.

![demo-video](https://github.com/memo33/sc4pac-tools/releases/download/0.1.3/demo-video.gif)


## Available commands

    add             Add new packages to install explicitly.
    update          Update all installed packages to their latest version and install any missing packages.
    remove          Remove packages that have been installed explicitly.
    search          Search for the name of a package.
    list            List all installed packages.
    variant reset   Select variants to reset in order to choose a different package variant.
    channel add     Add a channel to fetch package metadata from.
    channel remove  Select channels to remove.
    channel list    List the channel URLs.
    channel build   Build a channel locally by converting YAML files to JSON.


# Plugins folder structure

(preliminary)

    100-props-textures
    150-mods
    200-residential
    300-commercial
    400-industrial
    500-utilities
    600-civics
    700-transit
    777-network-addon-mod  (installed manually)
    900-overrides

Packages are installed into even-numbered subfolders, as the order in which files are loaded by the game is important.
Files you install manually should be put into odd-numbered subfolders.


# Details

The file `sc4pac-plugins.json` stores the identifiers of packages you explicitly requested to install (without dependencies).
This information is used by sc4pac to compute all the necessary dependencies and download and extract them into your plugins folder.

The file `sc4pac-plugins-lock.json` stores information about all the installed packages (including dependencies).
This tells sc4pac which version of packages are installed, where to find them in your plugins folder and how to upgrade them to newer versions.

Sc4pac obtains its information from metadata stored in a remote channel.
The metadata is added in terms of .yaml files.
See the [commented example](channel-testing/yaml/templates/package-template-basic.yaml)
and the [empty template](channel-testing/template-empty.yaml).


# Uninstalling

- First, remove all installed packages from your plugins folder. Either:
  * run `sc4pac remove --interactive` and select everything, or
  * delete every folder named `*.sc4pac` from your plugins folder, or
  * delete the entire plugins folder.
- Optionally, delete the cache folder.
  (In case you forgot its location, it is saved in the file `sc4pac-plugins.json`, which you can open with a text editor.)
- Finally, delete the folder containing the sc4pac program files.


# Build instructions

Compile with `sbt assembly`.
Create a release bundle with `make dist` in a Unix shell.

# Roadmap

- [x] Basic functionality
- [x] Command-line interface with all important commands
- [ ] Improve robustness of downloading (missing content-length (ST), incomplete downloads (SC4E), non-persistent URLs (Moddb), handling servers that have gone offline)
- [ ] Central metadata channel
- [ ] Website and online documentation
- [ ] Server API (backend)
- [ ] Graphical UI (frontend), Mod manager
