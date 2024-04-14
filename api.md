# API - version 1.2

The API allows other programs to control *sc4pac* in a client-server fashion.

In a nutshell:

```
POST /init                {plugins: "<path>", cache: "<path>"}

GET  /packages.list
GET  /packages.info?pkg=<pkg>
GET  /packages.search?q=<text>

GET  /plugins.added.list
GET  /plugins.installed.list
POST /plugins.add         ["<pkg1>", "<pkg2>", …]
POST /plugins.remove      ["<pkg1>", "<pkg2>", …]

GET  /variants.list
POST /variants.reset      ["<label1>", "<label2>", …]

GET  /update              (websocket)
```

- Everything JSON.
- Package placeholders `<pkg>` are of the form `<group>:<name>`.
- Launch the server using the `sc4pac server` command.
- On the first time, invoke `/init` before anything else.
- All endpoints may return some generic errors:
  - 400 (incorrect input)
  - 404 (non-existing packages, assets, etc.)
  - 409 `/error/profile-not-initialized` (when not initialized)
  - 500 (unexpected unresolvable situations)
  - 502 (download failures)
- Errors are of the form
  ```
  {
    "$type": "/error/<category>",
    "title": "<message for display>",
    "detail": "<info for debugging>"
  }
  ```

## init

Initialize the profile by configuring the location of plugins and cache.
Profiles are used to manage multiple plugins folders.

Synopsis: `POST /init {plugins: "<path>", cache: "<path>"}`

Returns:
- 409 `/error/init/not-allowed` if already initialized.
- 400 `/error/init/bad-request` if parameters are missing.
  The response contains
  `platformDefaults: {plugins: ["<path>", …], cache: ["<path>", …]}`
  for recommended platform-specific locations to use.

  ?> When managing multiple profiles, use the same cache for all of them.

- 200 `{"$type": "/result", "ok": true}` on success.

**Examples:**

Without parameters:
```sh
curl -X POST http://localhost:51515/init
```
Returns:
```json
{
  "$type": "/error/init/bad-request",
  "title": "Parameters \"plugins\" and \"cache\" are required.",
  "detail": "Pass the locations of the folders as JSON dictionary: {plugins: <path>, cache: <path>}.",
  "platformDefaults": {
    "plugins": [
      "/home/memo/Documents/SimCity 4/Plugins",
      "/home/memo/git/sc4/sc4pac/profiles/profile-1/plugins"
    ],
    "cache": [
      "/home/memo/.cache/sc4pac",
      "/home/memo/git/sc4/sc4pac/profiles/profile-1/cache"
    ]
  }
}
```

With parameters:
```sh
curl -X POST -d '{"plugins":"plugins","cache":"cache"}' http://localhost:51515/init
```

## packages.list

Get the list of all installable packages by fetching all channels.

Synopsis: `GET /packages.list`

Returns:
```
[{package: "<pkg>", version: string, summary: string, category: [string]}, …]
```

## packages.info

Get detailed information about a single package.

Synopsis: `GET /packages.info?pkg=<pkg>`

Returns: [example](https://memo33.github.io/sc4pac/channel/metadata/memo/industrial-revolution-mod/latest/pkg.json ':include').
(The `requiredBy` field must be ignored for now due to caching.)

## packages.search

Search for a package in all channels.

Synopsis: `GET /packages.search?q=<text>`

Optionally, bound the relevance by passing a `threshold` paramater ranging from 0 to 100.

Returns:
```
[{package: "<pkg>", relevance: 100, summary: string}, …]
```

## plugins.added.list

Get the list of packages that have been added explicitly (not necessarily installed yet).
These packages are precisely the ones that can be removed.

Synopsis: `GET /plugins.added.list`

Returns: `["<pkg>", …]`

## plugins.installed.list

Get the list of packages that are currently installed in your plugins.

Synopsis: `GET /plugins.installed.list`

Returns:
```
[
  {
    package: "<pkg>",
    version: string,
    variant: {"<label>": "<value>", …},
    explicit: boolean
  },
  …
]
```

## plugins.add

Add packages to the list of packages to install explicitly.

Synopsis: `POST /plugins.add ["<pkg1>", "<pkg2>", …]`

Returns: `{"$type": "/result", "ok": true}`

Example:
```sh
curl -X POST -d '["cyclone-boom:save-warning"]' http://localhost:51515/plugins.add
```

## plugins.remove

Remove packages from the list of packages to install explicitly.
Only packages previously added can be removed.

Synopsis: `POST /plugins.remove ["<pkg1>", "<pkg2>", …]`

Returns:
- 400 `/error/bad-request` if one of the submitted packages is not in `/plugins.added.list`
- 200 `{"$type": "/result", "ok": true}`

Example:
```sh
curl -X POST -d '["cyclone-boom:save-warning"]' http://localhost:51515/plugins.remove
```

## variants.list

Get the list of configured variants of your plugins folder.

Synopsis: `GET  /variants.list`

Returns: `{"<driveside>": "<right>", "<nightmode>": "<dark>", …}`

## variants.reset

Reset selected variants by removing them from `/variants.list`.

Synopsis: `POST /variants.reset ["<driveside>", "<nightmode>", …]`

Returns:
- 400 `/error/bad-request` if one of the variant labels is not in `/variants.list`
- 200 `{"$type": "/result", "ok": true}`

Example:
```sh
curl -X POST -d '["nightmode"]' http://localhost:51515/variants.reset
```

## update

Opening a websocket at `/update` triggers the update process.
This mirrors the interactive `sc4pac update` command of the CLI.
The websocket sends a series of messages, some of which expect a specific response, such as a confirmation to continue.

Example using Javascript in your web browser:
```javascript
let ws = new WebSocket('ws://localhost:51515/update');
// ws.send(JSON.stringify({"$type": "/prompt/response", token: "<token>", body: "Yes"}))
```
The messages sent from the server are logged in the network tab of the browser dev tools.

Messages sent:
- `/prompt/choice/update/variant` for each variant to choose
- `/prompt/confirmation/update/plan` once (even if everything is up-to-date, in which case you can accept without user input)
- for each download (in parallel):
  - `/progress/download/started` once
  - `/progress/download/length` at most once (if the file size is known)
  - `/progress/download/downloaded` multiple times to inform about the download progress
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
  "label": string,                               // e.g. "nightmode"
  "choices": ["<value1>", "<value2>", …],        // e.g. ["standard", "dark"]
  "descriptions": {"<value2>": "…", …},
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
    {"package": "<pkg>", "version": string, "variant": {"<label>": "<value>", …}},
    {"package": "<pkg>", "version": string, "variant": {}},
    …
  ],
  toInstall: [
    {"package": "<pkg>", "version": string, "variant": {}},
    {"package": "<pkg>", "version": string, "variant": {"<label>": "<value>", …}},
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
{ "$type": "/progress/download/length", "url": string, "length": "string (Long)" }
{ "$type": "/progress/download/downloaded", "url": string, "downloaded": "string (Long)" }
{ "$type": "/progress/download/finished", "url": string, "success": boolean }
```

#### Extraction
```
{ "$type": "/progress/update/extraction", "package": "<pkg>", "progress": {"numerator": 3, "denominator": 3} }
```

### Errors

See [ErrorMessage](https://github.com/memo33/sc4pac-tools/blob/main/src/main/scala/sc4pac/api/message.scala).
