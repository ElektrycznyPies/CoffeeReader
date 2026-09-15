# Public feed-set files

Place `feed-sets/` in the public repository. Update its JSON files independently
of APK releases. The app fetches them when the user opens **Discover feed sets**.

Set `ReaderConfig.FEED_SETS_INDEX_URL` once to the raw URL of `index.json`, such as:
`https://raw.githubusercontent.com/OWNER/REPOSITORY/main/feed-sets/index.json`.
The placeholder must be replaced with the real repository. Do not use a GitHub
`/blob/` URL: that returns a web page rather than JSON.

`index.json` has a `schemaVersion` and a `sets` array. An entry contains `name`,
`description` and `url`; relative URLs resolve against the index URL. Example:

```json
{
  "schemaVersion": 1,
  "sets": [
    {
      "name": "Your set title",
      "description": "A short description for readers.",
      "url": "example.json"
    }
  ]
}
```

The supplied index is intentionally empty. `example.json` illustrates the complete
shape and contains no suggested subscriptions. Each source added to `sources`
uses this shape (replace the example address with a working RSS/Atom URL):

```json
{
  "url": "https://example.org/feed.xml",
  "name": "Source name",
  "tags": [0, 1],
  "limit": 3,
  "website": "https://example.org/"
}
```

The five tags have indices 0–4 in the order of the `tags` array. `bookmarks` may
be an empty array. The same schema is used by app exports and curated sets.

Once configured, the debug-only **Check set sources** button reads the latest
index and files, deduplicates feed URLs, parses the response and reports active,
empty, stale, redirected or failed feeds. A failure does not remove a source.
