sc4pac
======

*A package manager for SimCity 4 plugins.*

This program handles the process of downloading and installing
SimCity 4 plugins, including all their dependencies.

It helps you assemble a full Plugins folder very quickly,
and also keeps your plugins up-to-date and organized.

This program comes as a GUI application ([sc4pac GUI](https://github.com/memo33/sc4pac-gui/releases)),
and can also be used with a command-line interface ([CLI](https://memo33.github.io/sc4pac/#/cli)).


<div style='display: none'>
<p><b>Main website:</b> <a href="https://memo33.github.io/sc4pac/#/">https://memo33.github.io/sc4pac/</a></p>
<p>
This repository contains the core functionality and the <a href="https://memo33.github.io/sc4pac/#/cli">sc4pac CLI</a>.
The GUI is located in the <a href="https://github.com/memo33/sc4pac-gui/releases">sc4pac GUI</a> repository and can be downloaded from there.
</p>
</div>


## Usage

- Prerequisites:
  - **Java** 11+ ([Windows]/[macOS]/[Linux], see [Adoptium] for details)
  - **Mono** ([macOS](https://www.mono-project.com/docs/getting-started/install/)/[Linux](https://repology.org/project/mono/versions), not needed on Windows)
  - Enough disk space

  [Adoptium]: https://adoptium.net/installation/
  [Windows]: https://adoptium.net/temurin/releases/?os=windows&package=jre
  [macOS]: https://adoptium.net/temurin/releases/?os=mac&package=jre
  [Linux]: https://repology.org/project/openjdk/versions

- [Download the latest release of the sc4pac GUI](https://github.com/memo33/sc4pac-gui/releases/latest).
  Choose between
  - the desktop application for **Windows** 10+,
  - the desktop application for **Linux**, and
  - the **cross-platform** web-app for other platforms,
  - or the non-graphical terminal-based [sc4pac CLI](https://github.com/memo33/sc4pac-tools/releases/latest).

- [Verify the checksum](https://howardsimpson.blogspot.com/2022/01/quickly-create-checksum-in-windows.html)
  of the downloaded ZIP file against the [checksums published on Github](https://github.com/memo33/sc4pac-gui/releases) (optional).

- Extract the contents of the downloaded ZIP file to any location of your choice.

- At first launch of the application, it guides you through the initial setup:
  creating a new Profile and configuring the location of your Plugins folder.

- Go to **Find Packages** to search and select packages to install (for example `pkg=cyclone-boom:save-warning`).
  There are already more than 3000 plugins with compatible *metadata* to choose from.
  Once satisfied, go to the **Dashboard** and hit **Update** to download and install the plugin files.

- Add the [Simtropolis Channel](https://community.simtropolis.com/forums/topic/763620-simtropolis-x-sc4pac-a-new-way-to-install-plugins/)
  in your **Dashboard** to be able to find and install many additional files from the STEX:
  ```
  https://memo33.github.io/sc4pac/channel/
  https://sc4pac.simtropolis.com/
  ```
  Start small and gradually build up your Plugins folder. Don't download everything at once.

- If you are signed in to [Simtropolis](https://community.simtropolis.com), watch out for the **“Install with sc4pac”** button on the STEX.
  It appears for plugins that can be installed with *sc4pac*.

- Install `pkg=null-45:startup-performance-optimization-dll` if you use the Windows digital edition of the game.
  (This adds support for plugin file paths exceeding the 260 character limit on Windows,
  which could otherwise be a problem for a small number of packages that use deeply-nested, long file names.)

- Be aware that Simtropolis has a download limit of 20 files per day for guests.
  If you reach the limit, go to **Settings** and set up authentication.
  Alternatively, simply continue the installation process the next day.


## Plugins folder structure

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
895-my-overrides       (your manually installed zzz-folders)
900-overrides
```
[(source)](https://github.com/memo33/sc4pac-actions/blob/main/src/lint.py#L16-L36)

Packages are installed into subfolders prefixed by an *even* number, as the order in which files are loaded by the game is important.
This ensures a consistent load order.
Files you install manually should be put into subfolders prefixed by an *odd* number
(ideally before `900-overrides`).


## Migrating an existing Plugins folder

If you already have a non-empty Plugins folder and want to switch to *sc4pac*,
start by moving the bulk of your manually installed files into a new subfolder `075-my-plugins`
and your zzz-folders into `895-my-overrides` to ensure correct load order.
Once you install more and more packages and dependencies with *sc4pac*,
delete the corresponding older, redundant, manually installed files
to avoid duplication or version conflicts.
If a dependency was installed with *sc4pac*, there is no need to keep older manually installed copies of it.

In the long run, create additional odd-numbered subfolders for better organization of your manually installed files
and to fine-tune load order.


## Managing multiple Plugins folders

The *sc4pac* GUI supports creating multiple Profiles.
For example, you could use a unique Profile for each of your Regions.
Each Profile corresponds to a Plugins folder.

!> Important: Make sure to select distinct locations for all your Plugins folders to avoid interference between Profiles.

?> You can use the [SC4 launch parameter](https://www.wiki.sc4devotion.com/index.php?title=Shortcut_Parameters#User_Dir) `-UserDir:"..."`
   to start the game with a custom location for the Plugins folder.


## Uninstalling

- Uninstall all packages for each Profile. Either:
  * with the GUI: unstar all starred packages and hit **Update**, or
  * with the CLI: run `sc4pac remove --interactive` and select everything, or
  * delete every folder named `*.sc4pac` from your plugins folder, or
  * delete the entire plugins folder.
- Delete the download cache folder (see **Dashboard**).
- With the GUI: remove the profiles configuration folder (see **Settings**).
- Finally, delete the folder containing the *sc4pac* program files.


<div style='display: none'>

## Details <!-- {docsify-ignore} -->

The *sc4pac* CLI saves its state in two files.

The file `sc4pac-plugins.json` stores the identifiers of packages you explicitly requested to install (without dependencies).
This information is used by *sc4pac* to compute all the necessary dependencies and download and extract them into your plugins folder.

The file `sc4pac-plugins-lock.json` stores information about all the installed packages (including dependencies).
This tells *sc4pac* which version of packages are installed, where to find them in your plugins folder and how to upgrade them to newer versions.

*Sc4pac* obtains its information from metadata stored in a remote channel.
The metadata is added in terms of .yaml files (see [Adding metadata](https://memo33.github.io/sc4pac/#/metadata)).
The metadata of the default channel is stored in the [metadata repository](https://github.com/memo33/sc4pac).

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
