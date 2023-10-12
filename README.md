sc4pac
======

A package manager for SimCity 4 plugins.

This program only comes with a command-line interface (CLI) for now.


## Usage

- Prerequisites:
  - Java 8+
  - enough disk space
- Download the [latest release](https://github.com/memo33/sc4pac-tools/releases/latest)
  and extract the contents to any location in your user directory (for example, your Desktop).
- Open a shell in the new directory (e.g. on Windows, open the folder and type `cmd` in the address bar of the explorer window)
  and run the command-line tool `sc4pac` by calling:
  - `sc4pac` in Windows cmd.exe
  - `.\sc4pac` in Windows PowerShell
  - `./sc4pac` on Linux or macOS

  If everything works, this displays a help message.
- Install your first package [memo:essential-fixes](https://memo33.github.io/sc4pac/channel/?pkg=memo:essential-fixes):
  - `sc4pac add memo:essential-fixes`
  - `sc4pac update`
- Be aware that Simtropolis has a download limit of 20 files per day.

![demo-video](https://github.com/memo33/sc4pac-tools/releases/download/0.1.3/demo-video.gif)

- Other available packages:
  - [memo:industrial-revolution-mod](https://memo33.github.io/sc4pac/channel/?pkg=memo:industrial-revolution-mod)
    (base pack including all dependencies [~1.4GB])
  - BSC Common Dependencies: `sc4pac search bsc` displays all the package names.
    - [bsc:essentials](https://memo33.github.io/sc4pac/channel/?pkg=bsc:essentials)
    - [bsc:mega-props-sg-vol01](https://memo33.github.io/sc4pac/channel/?pkg=bsc:mega-props-sg-vol01)
    - [bsc:mega-props-cp-vol01](https://memo33.github.io/sc4pac/channel/?pkg=bsc:mega-props-cp-vol01)
    - [bsc:textures-vol01](https://memo33.github.io/sc4pac/channel/?pkg=bsc:textures-vol01)
    - and many more
  - [cycledogg:missouri-breaks-terrain](https://memo33.github.io/sc4pac/channel/?pkg=cycledogg:missouri-breaks-terrain)
    (an SD terrain mod)


## Available commands

    add             Add new packages to install explicitly.
    update          Update all installed packages to their latest version and install any missing packages.
    remove          Remove packages that have been installed explicitly.
    search          Search for the name of a package.
    info            Display more information about a package.
    list            List all installed packages.
    variant reset   Select variants to reset in order to choose a different package variant.
    channel add     Add a channel to fetch package metadata from.
    channel remove  Select channels to remove.
    channel list    List the channel URLs.
    channel build   Build a channel locally by converting YAML files to JSON.


## Plugins folder structure

(preliminary)

    100-props-textures
    150-mods
    170-terrain
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


## Details

The file `sc4pac-plugins.json` stores the identifiers of packages you explicitly requested to install (without dependencies).
This information is used by sc4pac to compute all the necessary dependencies and download and extract them into your plugins folder.

The file `sc4pac-plugins-lock.json` stores information about all the installed packages (including dependencies).
This tells sc4pac which version of packages are installed, where to find them in your plugins folder and how to upgrade them to newer versions.

Sc4pac obtains its information from metadata stored in a remote channel.
The metadata is added in terms of .yaml files.
See the [commented example](channel-testing/yaml/templates/package-template-basic.yaml)
and the [empty template](channel-testing/template-empty.yaml).
The metadata of the default channel is stored at https://github.com/memo33/sc4pac.


## Uninstalling

- Remove all installed packages from your plugins folder. Either:
  * run `sc4pac remove --interactive` and select everything, or
  * delete every folder named `*.sc4pac` from your plugins folder, or
  * delete the entire plugins folder.
- Optionally, delete the cache folder.
  (In case you forgot its location, it is saved in the file `sc4pac-plugins.json`, which you can open with a text editor.)
- Finally, delete the folder containing the sc4pac program files.


## Build instructions

Compile the CLI with `sbt assembly`.
Create a release bundle with `make dist` in a Unix shell.

For editing the website locally, run `sbt ~web/fastLinkJS` as well as `make channel-testing-web host-web`
and open `http://localhost:8090/channel/index-dev.html`.
For publishing the website, refer to the Makefile at https://github.com/memo33/sc4pac.

## Roadmap

- [x] Basic functionality
- [x] Command-line interface (CLI) with all important commands
- [ ] Improve resilience of downloads
  - [x] missing content-length (ST)
  - [x] incomplete downloads (SC4E)
  - [ ] non-persistent URLs (Moddb)
  - [ ] handling servers that have gone offline
- [x] Collaborative central metadata channel: https://github.com/memo33/sc4pac
- [ ] Website and online documentation
- [ ] Server API (backend)
- [ ] Graphical UI (frontend) aka Mod manager
