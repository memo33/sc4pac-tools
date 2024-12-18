sc4pac
======

*A package manager for SimCity 4 plugins.*

This program handles the process of downloading and installing
SimCity 4 plugins, including all their dependencies.

This program comes with a command-line interface (CLI).
A GUI application ([sc4pac GUI](https://github.com/memo33/sc4pac-gui/releases)) is available as a separate download.


<div style='display: none'>
<b>Main website:</b> <a href="https://memo33.github.io/sc4pac/#/">https://memo33.github.io/sc4pac/</a>
</div>


## Usage

- Prerequisites:
  - Java 11+ (https://adoptium.net/installation/)
  - Enough disk space
  - On macOS and Linux (not needed on Windows):
    - Mono ([macOS](https://www.mono-project.com/docs/getting-started/install/)/[Linux](https://repology.org/project/mono/versions)):
      This is optional and only needed for a few packages that originally use an installer.
- [Download the latest release](https://github.com/memo33/sc4pac-tools/releases/latest)
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
  Intermediate downloads are cached, so if you reach the limit,
  simply continue the installation process the next day.
  Alternatively, there is a temporary [workaround using cookies](https://github.com/memo33/sc4pac-tools/blob/main/src/scripts/sc4pac.bat#L13-L32).
- Install other [available packages](https://memo33.github.io/sc4pac/#/packages).

![demo-video](https://github.com/memo33/sc4pac-tools/releases/download/0.1.3/demo-video.gif)


## Available commands

```
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
server          Start a local server to use the HTTP API.
```

See [CLI](https://memo33.github.io/sc4pac/#/cli?id=command-line-interface) for details.


## Plugins folder structure

(preliminary)

```
050-load-first
075-my-plugins         (your existing manually installed plugins)
100-props-textures
150-mods
170-terrain
180-flora
200-residential
300-commercial
360-landmark
400-industrial
500-utilities
600-civics
610-safety
620-education
630-health
640-government
650-religion
660-parks
700-transit
710-automata
777-network-addon-mod  (installed manually)
891-my-overrides       (your manually installed zzz-folders)
900-overrides
```
[(source)](https://github.com/memo33/sc4pac-actions/blob/main/src/lint.py#L16-L36)

Packages are installed into even-numbered subfolders, as the order in which files are loaded by the game is important.
Files you install manually should be put into odd-numbered subfolders
(ideally before `900-overrides`).

## Migrating an existing Plugins folder

If you already have a non-empty Plugins folder and want to switch to *sc4pac*,
start by moving the bulk of your manually installed files into a new subfolder `075-my-plugins`
and your zzz-folders into `891-my-overrides` to ensure correct load order.
Once you install more and more packages and dependencies with *sc4pac*,
delete the corresponding older, redundant, manually installed files
to avoid duplication or version conflicts.
If a dependency was installed with *sc4pac*, there is no need to keep older manually installed copies of it.

In the long run, create additional odd-numbered subfolders for better organization of your manually installed files
and to fine-tune load order.


## Details

The *sc4pac* CLI saves its state in two files.

The file `sc4pac-plugins.json` stores the identifiers of packages you explicitly requested to install (without dependencies).
This information is used by *sc4pac* to compute all the necessary dependencies and download and extract them into your plugins folder.

The file `sc4pac-plugins-lock.json` stores information about all the installed packages (including dependencies).
This tells *sc4pac* which version of packages are installed, where to find them in your plugins folder and how to upgrade them to newer versions.

*Sc4pac* obtains its information from metadata stored in a remote channel.
The metadata is added in terms of .yaml files (see [Adding metadata](https://memo33.github.io/sc4pac/#/metadata)).
The metadata of the default channel is stored in the [metadata repository](https://github.com/memo33/sc4pac).


## Uninstalling

- Remove all installed packages from your plugins folder. Either:
  * run `sc4pac remove --interactive` and select everything, or
  * delete every folder named `*.sc4pac` from your plugins folder, or
  * delete the entire plugins folder.
- Optionally, delete the cache folder.
  (In case you forgot its location, it is saved in the file `sc4pac-plugins.json`, which you can open with a text editor.)
- Finally, delete the folder containing the *sc4pac* program files.


<div style='display: none'>

## Build instructions <!-- {docsify-ignore} -->

Compile the CLI with `sbt assembly` using build tool SBT.
Create a release bundle with `make dist` in a Unix shell.

For editing the channel page of the website locally, run `sbt ~web/fastLinkJS` as well as `make channel-testing-web host-web`
and open `http://localhost:8090/channel/index-dev.html`.
For publishing the website, refer to the Makefile of the [metadata repository](https://github.com/memo33/sc4pac).

The documentation pages of the website are rendered directly from these [markdown files](https://github.com/memo33/sc4pac/tree/main/docs/)
and do not require any build step.

## Roadmap <!-- {docsify-ignore} -->

- [x] Basic functionality
- [x] Command-line interface (CLI) with all important commands
- [ ] Improve resilience of downloads
  - [x] missing content-length (ST)
  - [x] authentication (ST), [partially implemented](https://github.com/memo33/sc4pac-tools/blob/main/src/scripts/sc4pac.bat#L13-L32)
  - [x] incomplete downloads (SC4E)
  - [ ] non-persistent URLs (Moddb)
  - [ ] handling servers that have gone offline (e.g. version pinning)
- [x] Collaborative central [metadata channel](https://github.com/memo33/sc4pac)
- [x] [Website and online documentation](https://memo33.github.io/sc4pac/)
- [x] Server API (backend): https://memo33.github.io/sc4pac/#/api or [api.md](api.md)
- [x] Graphical UI (frontend) aka Mod manager: [sc4pac GUI](https://github.com/memo33/sc4pac-gui)

</div>
