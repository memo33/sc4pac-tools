# Command-line interface

The *sc4pac* CLI supports all the commands you need.


---
## add

**Usage:** `sc4pac add [packages...]`

Add new packages to install explicitly.

Afterwards, run `sc4pac update` for the changes to take effect.

**Example:**
```sh
sc4pac add memo:essential-fixes
```

Package names are of the form `<group>:<package-name>`.


---
## update

**Usage:** `sc4pac update [options]`

Update all installed packages to their latest version and install any missing packages.

In particular, this installs the explicitly added packages and, implicitly, all their dependencies.

**Example:**
```sh
sc4pac update
```

**Options:**
- `-y, --yes` Accept some default answers without asking, usually "yes"


---
## remove

**Usage:** `sc4pac remove [options] [packages...]`

Remove packages that have been installed explicitly.

Afterwards, run `sc4pac update` for the changes to take effect.

**Examples:**
```sh
sc4pac remove --interactive          # Interactively select packages to remove.
sc4pac remove memo:essential-fixes   # Remove package <group>:<package-name>.
```

**Options:**
- `-i, --interactive` Interactively select packages to remove


---
## search

**Usage:** `sc4pac search [options] [search text...]`

Search for the name of a package.
The results are ordered such that the best match is displayed at the bottom.

**Examples:**

```sh
sc4pac search "Pause border"
>>> (1) smp:yellow-pause-thingy-remover
>>>         Remove the yellow border from the UI when the game is paused

sc4pac search --threshold 20 "Pause border"    # Decrease threshold for more results.
>>> ...
```

You can search for a URL of a STEX entry or SC4Evermore download page to find any corresponding packages:

```sh
sc4pac search "https://community.simtropolis.com/files/file/32812-save-warning/"
>>> ...

sc4pac search "https://www.sc4evermore.com/index.php/downloads/download/26-gameplay-mods/26-bsc-no-maxis"
>>> ...
```

**Options:**
- `--threshold <number>` Fuziness (0..100, default=80): Smaller numbers lead to more results.


---
## info

**Usage:** `sc4pac info [packages]`

Display more information about a package.

**Examples:**
```sh
sc4pac info memo:essential-fixes
```

---
## list

**Usage:** `sc4pac list`

List all installed packages.


---
## variant reset

**Usage:** `sc4pac variant reset [options] [variants...]`

Select variants to reset in order to choose a different package variant.

For some packages you install, you can choose from a list of package variants that match your preferences. Your choices are stored in a configuration file.

After resetting a variant identifier, the next time you run `sc4pac update`, you will be asked to choose a new variant.

**Examples:**
```sh
sc4pac variant reset --interactive    # Interactively select variants to reset.
sc4pac variant reset "driveside"      # Reset the "driveside" variant.
```

**Options:**
- `-i, --interactive`  Interactively select variants to reset


---
## channel add

**Usage:** `sc4pac channel add [options] [channel-URL]`

Add a channel to fetch package metadata from.

**Examples:**
```sh
sc4pac channel add "https://memo33.github.io/sc4pac/channel/"
sc4pac channel add "file:///C:/absolute/path/to/local/channel/json/"
```

The URL in the examples above points to a directory structure consisting of JSON files created by the `sc4pac channel build` command.

For convenience, the channel URL may also point to a single YAML file instead, which skips the `sc4pac channel build` step.
This is mainly intended for testing purposes.

```sh
sc4pac channel add "file:///C:/Users/Dumbledore/Desktop/hogwarts-castle.yaml"
sc4pac channel add "https://raw.githubusercontent.com/memo33/sc4pac/main/docs/hogwarts-castle.yaml"
```

---
## channel remove

**Usage:** `sc4pac channel remove [options] [URL-patterns]`

Select channels to remove.

**Examples:**
```sh
sc4pac channel remove --interactive     # Interactively select channels to remove.
sc4pac channel remove "github.com"      # Remove channel URLs containing "github.com".
```

**Options:**
- `-i, --interactive`  Interactively select channels to remove


---
## channel list

**Usage:** `sc4pac channel list`

List the channel URLs.

The first channel has the highest priority when resolving dependencies.


---
## channel build

**Usage:** `sc4pac channel build [options] [YAML-input-directories...]`

Build a channel locally by converting YAML files to JSON.

!> On Windows, this command may require special privileges to run. To resolve this, either
   run the command in a shell with administrator privileges, or
   use Java 13+ and enable
   [Windows Developer Mode](https://learn.microsoft.com/en-us/windows/apps/get-started/enable-your-device-for-development)
   on your device.

**Examples:**
```sh
sc4pac channel build --output "channel/json/" "channel/yaml/"
sc4pac channel build --label Local --metadata-source-url https://github.com/memo33/sc4pac/blob/main/src/yaml/ -o channel/json channel/yaml
```

Use the options `--label` and `--metadata-source-url` particularly for building publicly accessible channels.

**Options:**
- `-o, --output <dir>`         Output directory for JSON files
- `--label str`                Optional short channel name for display in the UI
- `--metadata-source-url url`  Optional base URL linking to the online YAML source files (for Edit Metadata button)


---
## server

**Usage:** `sc4pac server [options]`

Start a local server to use the HTTP [API](api).

**Example:**
```sh
sc4pac server --profiles-dir profiles --indent 1
sc4pac server --profiles-dir profiles --web-app-dir build/web --launch-browser  # used by GUI web
sc4pac server --profiles-dir profiles --auto-shutdown --startup-tag [READY]     # used by GUI desktop
```

**Options:**
- `--port <number>`         (default: 51515)
- `--profiles-dir <path>`   directory containing the `sc4pac-profiles.json` file and profile sub-directories (platform-dependent default), newly created if necessary
- `--web-app-dir <path>`    optional directory containing statically served webapp files (default: no static files)
- `--launch-browser`        automatically open the web browser when using the `--web-app-dir` option (default: `--launch-browser=false`)
- `--auto-shutdown`         automatically shut down the server when client closes connection to `/server.connect` (default: `--auto-shutdown=false`). This is used by the desktop GUI to ensure the port is cleared when the GUI exits.
- `--startup-tag <string>`  optional tag to print once server has started and is listening
- `--indent <number>`       indentation of JSON responses (default: -1, no indentation)
