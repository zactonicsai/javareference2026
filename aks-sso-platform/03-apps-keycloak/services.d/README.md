# services.d — launchpad tiles added after the initial install

One JSON file per service, written by `scripts/add-service.sh` (or by hand). `scripts/install.sh` and
`add-service.sh` merge every `*.json` here into the launchpad's `app.extraServices` value.

```json
{
  "id": "project2-site",
  "name": "Project 2 site",
  "description": "Static site for the project 2 team",
  "url": "{publicUrl}/project2/",
  "healthUrl": "http://project2-site.appsteam.svc.cluster.local:8080/healthz",
  "groups": ["project2"],
  "icon": "folder",
  "order": 100,
  "external": false
}
```

`{publicUrl}` is replaced by the site's public URL at runtime. `groups` empty = visible to everyone.
Commit these files — they are the source of truth for the launch page.
