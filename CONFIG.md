# Configuration placeholders

There is nothing to configure at runtime: no server, token, account or API key.

Settings:

| Where | Setting | Value |
|---|---|---|
| app/src/main/res/values/strings.xml | `contact_email` = `business.msk846@gmail.com` | support address shown under menu > Contact / Support, next to the issues link. Leave empty to show only the issues link |
| app/src/main/res/values/strings.xml | `update_repo` = `mdmsk9805/NoRouterDrop` | the GitHub repository (owner/repo) used by menu > Check for updates and the issues link. Change it if you fork |
| local.properties (not committed) | `sdk.dir` | path to your Android SDK |

Name and package are set to NoRouterDrop / dev.msk.norouterdrop. To rename again, run `rename.sh <NewName> <new.package.id>` (kept outside the repository) and rebuild. Name locations: `Brand.kt`, `strings.xml`, `settings.gradle`, README.md and the docs. Internal ids (preference file names, Keystore alias, pairing label) also carry the lowercase name.
