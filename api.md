# API - version 2.5

The API allows other programs to control *sc4pac* in a client-server fashion.

In a nutshell:

```
GET  /profile.read?profile=id
POST /profile.init?profile=id        {plugins: "<path>", cache: "<path>", temp: "<path>"}

GET  /packages.list?profile=id
GET  /packages.info?pkg=<pkg>&profile=id
GET  /packages.search?q=<text>&profile=id
POST /packages.search.id?profile=id  {packages: ["<pkg1>", "<pkg2>", …], externalIds: [["sc4e", "<extId1>"], …]}
POST /packages.open                  [{package: "<pkg>", channelUrl: "<url>"}]

GET  /plugins.added.list?profile=id
GET  /plugins.installed.list?profile=id
POST /plugins.add?profile=id         ["<pkg1>", "<pkg2>", …]
POST /plugins.remove?profile=id      ["<pkg1>", "<pkg2>", …]

GET  /variants.list?profile=id
POST /variants.reset?profile=id      ["<variantId1>", "<variantId2>", …]

GET  /channels.list?profile=id
POST /channels.set?profile=id        ["<url1>", "<url2>", …]
GET  /channels.stats?profile=id

GET  /update?profile=id              (websocket)

GET  /server.status
GET  /server.connect                 (websocket)

GET  /profiles.list
POST /profiles.add                   {name: string}
POST /profiles.switch                {id: "<id>"}

GET  /settings.all.get
POST /settings.all.set               <object>

GET  /image.fetch?url=<url>
```

- Everything JSON.
- Package placeholders `<pkg>` are of the form `<group>:<name>`.
- Launch the server using the `sc4pac server` command.
- On the first time, invoke `/profile.init` before anything else.
- All endpoints may return some generic errors:
  - 400 (incorrect input)
  - 404 (non-existing packages, assets, etc.)
  - 409 `/error/profile-not-initialized` (when not initialized)
  - 500 `/error/profile-read-error` (when one of the profile JSON files fails to parse)
  - 500 (other unexpected unresolvable situations)
  - 502 (download failures)
- Errors are of the form
  ```
  {
    "$type": "/error/<category>",
    "title": "<message for display>",
    "detail": "<info for debugging>"
  }
  ```

## profile.read

Read the config data stored for this profile.

Synopsis: `GET /profile.read?profile=id`

Returns:
- 200 `{pluginsRoot: string, cacheRoot: string, …}`.
- 409 `/error/profile-not-initialized` when not initialized.
  The response contains
  `platformDefaults: {plugins: ["<path>", …], cache: ["<path>", …], temp: ["<path>", …]}`
  for recommended platform-specific locations to use for initialization.
- 500 `/error/profile-read-error` when the profile JSON file exists, but does not have the expected format.

## profile.init

Initialize the profile by configuring the location of plugins, cache and temp folder.
Profiles are used to manage multiple plugins folders.

Synopsis: `POST /profile.init?profile=id {plugins: "<path>", cache: "<path>", temp: "<temp>"}`

Returns:
- 409 `/error/init/not-allowed` if already initialized.
- 400 `/error/init/bad-request` if parameters are missing.
  The response contains
  `platformDefaults: {plugins: ["<path>", …], cache: ["<path>", …], temp: ["<path>", …]}`
  for recommended platform-specific locations to use.

  ?> When managing multiple profiles, use the same cache for all of them.

- 200 `{pluginsRoot: string, cacheRoot: string, …}`, same as `/profile.read`.

**Examples:**

Without parameters:
```sh
curl -X POST http://localhost:51515/profile.init?profile=1
```
Returns:
```json
{
  "$type": "/error/init/bad-request",
  "title": "Parameters \"plugins\", \"cache\" and \"temp\" are required.",
  "detail": "Pass the locations of the folders as JSON dictionary: {plugins: <path>, cache: <path>, temp: <path>}.",
  "platformDefaults": {
    "plugins": [
      "/home/memo/Documents/SimCity 4/Plugins",
      "/home/memo/git/sc4/sc4pac-gui/profiles/1/plugins"
    ],
    "cache": [
      "/home/memo/.cache/sc4pac",
      "/home/memo/git/sc4/sc4pac-gui/profiles/1/cache"
    ],
    "temp": [
      "temp"
    ]
  }
}
```

With parameters:
```sh
curl -X POST -d '{"plugins":"plugins","cache":"cache","temp":"temp"}' http://localhost:51515/profile.init?profile=1
```

## packages.list

Get the list of all installable packages by fetching all channels.

Synopsis: `GET /packages.list?profile=id`

Returns:
```
[{package: "<pkg>", version: string, summary: string, category: [string]}, …]
```

## packages.info

Get detailed information about a single package.

Synopsis: `GET /packages.info?pkg=<pkg>&profile=id`

Returns:
```
{
  local: {
    statuses: {
      "<pkg>": {
        explicit: boolean,
        installed?: {
          version: string,
          variant: {"<variantId>": "<value>", …},
          installedAt: "<iso-date>",
          updatedAt: "<iso-date>"
        },
      },
      "<dependency1>": …,
      …
    }
  }
  remote: object
}
```
where `local` contains information about relevant locally installed packages,
and `remote` corresponds to package metadata stored in the channel:
[example](https://memo33.github.io/sc4pac/channel/metadata/memo/industrial-revolution-mod/latest/pkg.json ':include').

Returns 404 `/error/package-not-found` if package does not exist.

## packages.search

Search for a package in all channels.

Synopsis: `GET /packages.search?q=<text>&profile=id`

Optional parameters:
- `threshold=<num>` to bound the relevance, ranging from 0 to 100.
- `channel=<url>` to limit results to this channel.
- `category=<cat>` to limit results to this category.
  If `category` is passed, but `q` is empty, then all packages of that category are returned.
  Can be passed multiple times to include more than one category.
- `notCategory=<cat>` to ignore results from one or more categories.
- `ignoreInstalled` to limit results to packages that are not installed yet.

Returns:
```
[
  {
    package: "<pkg>",
    relevance: 100,
    summary: string,
    status?: {
      explicit: boolean,
      installed?: {
        version: string,
        variant: {"<variantId>": "<value>", …},
        installedAt: "<iso-date>",
        updatedAt: "<iso-date>"
      }
    }
  },
  …
]
```
The `status` field contains the local installation status if the package has been explicitly added or actually installed.

## packages.search.id

Find a list of packages by identifier across all channels and lookup their summary and installation status.

Synopsis: `POST /packages.search.id?profile=id {packages: ["<pkg1>", "<pkg2>", …], externalIds: [["sc4e", "<extId1>"], …]}`

Returns:
```
[
  {
    package: "<pkg>",
    summary: string,
    status?: … // see packages.search
  },
  …
]
```

Example:
```sh
curl -X POST -d '{"packages": ["cyclone-boom:save-warning", "memo:submenus-dll"]}' http://localhost:51515/packages.search.id?profile=1
```

## packages.open

Tell the server to tell the client to show a particular package, using the current profile.
This endpoint is intended for external invocation, such as an "Open in app" button on a website.

Synopsis: `POST /packages.open [{package: "<pkg>", channelUrl: "<url>"}]`

Returns:
- 200 `{"$type": "/result", "ok": true}`
- 503 if GUI is not connected to API server

Example:
```sh
curl -X POST -d '[{"package": "cyclone-boom:save-warning", "channelUrl": "https://memo33.github.io/sc4pac/channel/"}]' http://localhost:51515/packages.open
```
The client will be informed by the `/server.connect` websocket.

## plugins.added.list

Get the list of packages that have been added explicitly (not necessarily installed yet).
These packages are precisely the ones that can be removed.

Synopsis: `GET /plugins.added.list?profile=id`

Returns: `["<pkg>", …]`

## plugins.installed.list

Get the list of packages that are currently installed in your plugins.

Synopsis: `GET /plugins.installed.list?profile=id`

Returns:
```
[
  {
    package: "<pkg>",
    version: string,
    variant: {"<variantId>": "<value>", …},
    explicit: boolean
  },
  …
]
```

## plugins.search

Filter the list of installed packages by search text or category.
Packages explicitly added but not yet installed are not included.
If search text is empty, all installed packages are returned.

Synopsis: `GET /plugins.search?profile=id&q=<text>&threshold=<percentage>&category=<cat>`

Returns:
```
{
  stats: {"totalPackageCount": int, "categories": [{"category": "150-mods", "count": int}, …]}
  packages: [
    {
      package: "<pkg>",
      relevance: 100,
      summary: string,
      status: {
        explicit: boolean,
        installed: {
          version: string,
          variant: {"<variantId>": "<value>", …},
          installedAt: "<iso-date>",
          updatedAt: "<iso-date>"
        }
      }
    }, …
  ]
}
```

## plugins.add

Add packages to the list of packages to install explicitly.

Synopsis: `POST /plugins.add?profile=id ["<pkg1>", "<pkg2>", …]`

Returns: `{"$type": "/result", "ok": true}`

Example:
```sh
curl -X POST -d '["cyclone-boom:save-warning"]' http://localhost:51515/plugins.add?profile=1
```

## plugins.remove

Remove packages from the list of packages to install explicitly.
Only packages previously added can be removed.

Synopsis: `POST /plugins.remove?profile=id ["<pkg1>", "<pkg2>", …]`

Returns:
- 400 `/error/bad-request` if one of the submitted packages is not in `/plugins.added.list`
- 200 `{"$type": "/result", "ok": true}`

Example:
```sh
curl -X POST -d '["cyclone-boom:save-warning"]' http://localhost:51515/plugins.remove?profile=1
```

## variants.list

Get the list of configured variants of your plugins folder.

Synopsis: `GET  /variants.list?profile=id`

Returns:
```
{
  "variants": {
    "<driveside>": {"value": "<right>", "unused": false},
    "<nightmode>": {"value": "<dark>", "unused": false},
    …
  }
}
```

## variants.reset

Reset selected variants by removing them from `/variants.list`.

Synopsis: `POST /variants.reset?profile=id ["<driveside>", "<nightmode>", …]`

Returns:
- 400 `/error/bad-request` if one of the variant IDs is not in `/variants.list`
- 200 `{"$type": "/result", "ok": true}`

Example:
```sh
curl -X POST -d '["nightmode"]' http://localhost:51515/variants.reset?profile=1
```

## channels.list

Get the ordered list of configured channel URLs.

Synopsis: `GET /channels.list?profile=id`

Returns: `["<url1>", "<url2>", …]`

## channels.set

Overwrite the list of channel URLs. If empty, the default channel is added instead.

Synopsis: `POST /channels.set?profile=id ["<url1>", "<url2">, …]`

Returns:
- 400 `/error/bad-request`
- 200 `{"$type": "/result", "ok": true}`

Example:
```sh
curl -X POST -d '["url"]' http://localhost:51515/channels.set?profile=1
```

## channels.stats

Get the combined stats of all the channels.

Synopsis: `GET /channels.stats?profile=id`

Returns:
```
{
  "combined": {"totalPackageCount": int, "categories": [{"category": "150-mods", "count": int}, …]},
  "channels": {"url": string, "channelLabel": [string], "stats": …}
}
```

## update

Opening a websocket at `/update` triggers the update process.
This mirrors the interactive `sc4pac update` command of the CLI.
The websocket sends a series of messages, some of which expect a specific response, such as a confirmation to continue.

Parameters:
- `simtropolisToken=<value>` to re-use an authenticated session.
  Otherwise, the environment variable will be used instead if available.
- `refreshChannels` to clear the cached channel contents files before updating.

Example using Javascript in your web browser:
```javascript
let ws = new WebSocket('ws://localhost:51515/update?profile=1');
// ws.send(JSON.stringify({"$type": "/prompt/response", token: "<token>", body: "Yes"}))
```
The messages sent from the server are logged in the network tab of the browser dev tools.

Messages sent:
- `/prompt/choice/update/variant` for each variant to choose
- `/prompt/choice/update/remove-conflicting-packages` to resolve a potential package conflict
- `/prompt/confirmation/update/remove-unresolvable-packages` if explicit packages cannot be found (e.g. if renamed in channel)
- `/prompt/confirmation/update/plan` once (even if everything is up-to-date, in which case you can accept without user input)
- for each download (in parallel):
  - `/progress/download/started` once
  - `/progress/download/length` at most once (if the file size is known)
  - `/progress/download/intermediate` multiple times to inform about the download progress
  - `/progress/download/finished` once
- `/progress/update/extraction` for each package installed
- `/prompt/confirmation/update/warnings` once (if the warnings are empty, you can accept without user input)
- final message: either an error message or `{"$type": "/result", "ok": true}`.
  Afterwards the websocket is closed.

Prompt messages `/prompt/…` generally require a response from the client for the process to continue.

## Message protocol

The following specifies the message format used by the `/update` websocket.

### Prompts

#### Choice of variant
```javascript
{
  "$type": "/prompt/choice/update/variant",
  "package": "<pkg>",
  "variantId": string,                           // e.g. "nightmode"
  "choices": ["<value1>", "<value2>", …],        // e.g. ["standard", "dark"]
  "info": {
    "description"?: "…",
    "valueDescriptions"?: {"<value2>": "…", …},
    "default": ["<value2">]                      // or []
  },
  "token": string,
  "responses": {"<value1>": object, "<value2>": object, …}
}
```
The `responses` field contains the valid response message objects to send back to the websocket as JSON.

#### Confirmation of packages to install and remove
```javascript
{
  "$type": "/prompt/confirmation/update/plan"
  toRemove: [
    {"package": "<pkg>", "version": string, "variant": {"<variantId>": "<value>", …}},
    {"package": "<pkg>", "version": string, "variant": {}},
    …
  ],
  toInstall: [
    {"package": "<pkg>", "version": string, "variant": {}},
    {"package": "<pkg>", "version": string, "variant": {"<variantId>": "<value>", …}},
    …
  ],
  choices: ["Yes", "No"],
  token: string,
  "responses": {"Yes": object, "No": object}
}
```

#### Confirmation of installation despite warnings
```javascript
{
  "$type": "/prompt/confirmation/update/warnings"
  "warnings": {"<pkg>": ["warnings-1", "warning-2", …], …},
  choices: ["Yes", "No"],
  token: string,
  "responses": {"Yes": object, "No": object}
}
```

### Progress

#### Download
```
{ "$type": "/progress/download/started", "url": string }
{ "$type": "/progress/download/length", "url": string, "length": "Long (or string if > 2^53)" }
{ "$type": "/progress/download/intermediate", "url": string, "downloaded": "Long (or string if > 2^53)" }
{ "$type": "/progress/download/finished", "url": string, "success": boolean }
```

#### Extraction
```
{ "$type": "/progress/update/extraction", "package": "<pkg>", "progress": {"numerator": 3, "denominator": 3} }
```

### Errors

See [ErrorMessage](https://github.com/memo33/sc4pac-tools/blob/main/src/main/scala/sc4pac/api/message.scala).

## server.status

Test if the server is running.

Synopsis: `GET  /server.status`

Returns: `{"sc4pacVersion": "0.4.x"}`

## server.connect

Monitor whether the server is still running by opening a websocket at this endpoint.
Usually, no particular messages are exchanged, but if either client or server terminates,
the other side will be informed about it as the websocket closes.

Triggered by an external `/packages.open` request,
the server may send the following message to tell the client to show a particular package, using the current profile:
```
{ "$type": "/prompt/open/package", "packages": [{"package": "<pkg>", "channelUrl": "<url>"}] }
```

## profiles.list

Get the list of all existing profiles, each corresponding to a Plugins folder.

Synopsis: `GET /profiles.list`

Returns:
- 500 `/error/profile-read-error`
- 200:
  ```
  {
    profiles: [{id: "<id-1>", name: string}, …],
    currentProfileId: ["<id-1>"],
    profilesDir: "<platform-dependent-path>"
  }
  ```

## profiles.add

Create a new profile and make it the currently active one. Make sure to call `/profile.init` afterwards.

Synopsis: `POST /profiles.add {name: string}`

Returns: `{"id": "<id>", "name": string}`

## profiles.switch

Set a different existing profiles as the currently active one.

Synopsis: `POST /profiles.switch {id: "<id>"}`

Returns:
- 200 `{"$type": "/result", "ok": true}`
- 400 if profile does not exist

Example:
```sh
curl -X POST -d '{"id": "2"}' http://localhost:51515/profiles.switch
```

## settings.all.get

Get all settings. The settings are profile-independent.

Synopsis: `GET /settings.all.get`

Returns: a client-customizable JSON object that is opaque for the server.

## settings.all.set

Store a custom JSON object as settings. The previous settings object is overwritten.

Synopsis: `POST /settings.all.set <object>`

Returns: `{"$type": "/result", "ok": true}`

Example to clear all settings:
```sh
curl -X POST -d '{}' http://localhost:51515/settings.all.set
```

## image.fetch

This endpoint acts as a proxy for downloading an image from a remote URL, adding appropriate CORS headers to the response.

Synopsis: `GET /image.fetch?url=<url>`

Returns the downloaded image (200) or any errors sent by the remote server.
Returns 400 in case of missing or malformed `url` parameter.
