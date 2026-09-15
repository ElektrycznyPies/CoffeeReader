# Development notes

This is an overlay for the user-provided Kotlin/Compose project. Keep the existing
`com.electricdog.coffeereader.ui.theme.CoffeeReaderTheme` and root Gradle files.

## Files

| File | Responsibility |
| --- | --- |
| MainActivity.kt | Activity and retained ViewModel host |
| reader/ReaderModels.kt | Models, limits, URL normalization and filtering |
| reader/ReaderViewModel.kt | State changes, refresh, imports and diagnostics |
| reader/ReaderStorage.kt | Atomic private JSON files and the export schema |
| reader/FeedNetwork.kt | Bounded HTTP requests, discovery and RSS/Atom parsing |
| reader/ReaderScreen.kt | Compose UI, panels and gestures |
| reader/ReaderPlatform.kt | Browser handoff, QR and sampled thumbnail loading |
| res/values/coffee_reader.xml | English UI strings |
| res/drawable/ic_cr_*.xml | Replaceable vector placeholders |

## Configuration

`ReaderConfig.COLLAPSED_TEXT_LIMIT = 180` and `EXPANDED_TEXT_LIMIT = 900` count
Unicode code points and include the ellipsis. A collapsed preview stops at the
first sentence when possible. Summaries are cleaned of HTML. Article pages are
not scraped and paywalls are not bypassed.

`ReaderConfig.FEED_SETS_INDEX_URL` is intentionally empty until the public GitHub
repository and curated feed sets are available.

Debug APKs show **Check set sources** in the feed-set panel once the index URL is
configured. Release APKs omit this control. The checker fetches current JSONs and
tests each distinct feed without editing subscriptions or GitHub files.

The app uses `schemaVersion: 1` and `format: coffee-reader`. Export includes
sources, their per-source limits and tags, five tag names, and bookmarks. It does
not export the downloaded article cache or read history. The receiving app keeps
its existing sources and settings. Unrecognized imported tag names are explicitly
mapped to local slots or omitted, never added as a sixth tag.

Local storage is under the app-private `files/coffee-reader` directory:
`reader.json` and `bookmarks.json`. Writes run on an IO dispatcher, in order, using
AtomicFile. These are two separate atomic files, not a cross-file transaction.
Cached articles are retained for 30 days based on publication or first-seen time;
bookmarks are retained until removed. RSS feeds do not guarantee 30 days of history.

## Icons and translations

Replace an `ic_cr_*.xml` with a PNG of the same resource name, removing the XML
first to avoid duplicate resources. Resource names must use lowercase letters,
digits and underscores. Use transparent PNGs without baked-in text.

`CrIcon` currently uses Compose `Icon` and applies the current text color. For
full-color PNG artwork, replace its implementation with Compose `Image` and
`painterResource(id)`, keeping the same modifier and content description.

For Polish, copy `res/values/coffee_reader.xml` to `res/values-pl/coffee_reader.xml`
and translate values while preserving keys and format placeholders. Do not
translate Kotlin identifiers or JSON keys. User-renamed tags are stored data;
they are not automatically retranslated after changing the device language.

## Dependencies and permissions

The supplied AGP 9.3.2, Kotlin Compose plugin 2.2.10, SDK 37 and other catalog
versions are preserved. AGP 9 uses built-in Kotlin, so no extra Kotlin Android
plugin was added.

- jsoup 1.21.2: RSS/Atom XML and HTML discovery / cleanup.
- ZXing core 3.5.3: local QR generation.
- Google code scanner 16.1.0: optional QR import, through Google Play services.
- Lifecycle ViewModel KTX 2.6.1: explicit declaration; Gradle may resolve a newer
  compatible Lifecycle version through the existing Activity dependency graph.

Add INTERNET to the existing manifest. Allow cleartext traffic if supporting HTTP
feeds. No storage permission is needed: imports and file exports use the system
file picker. No app camera permission is needed for Google code scanner.
The scanner may require a one-time module download and Google Play services;
link and file imports remain available when scanning cannot start.

Reference documentation:
- https://developer.android.com/develop/ui/compose/resources
- https://developer.android.com/develop/ui/compose/touch-input/pointer-input/drag-swipe-fling
- https://developer.android.com/build/migrate-to-built-in-kotlin
- https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner
- https://jsoup.org/news/release-1.21.2
- https://github.com/zxing/zxing/releases/tag/zxing-3.5.3
- https://dpaste.com/api/v2/

## Validation and remaining work

Run the included JVM tests with `gradlew.bat :app:testDebugUnitTest` in the original
project. Tests cover source balance, expansion, read-item retention, tag and time
filters, URL identity, Unicode excerpts, RSS, Atom and rejection of HTML errors.

Before calling the APK a release, compile and test on a device: add different real
feeds; verify swipes versus vertical scrolling; return from the default browser;
check persistence across process termination; export/import on two devices;
verify a real dpaste upload, raw retrieval, expiry and error handling; scan the QR;
test large fonts and both themes. No device execution has been performed here.

Known first-version boundaries: no periodic background worker, no OPML importer,
no full-text offline reader, no popularity ranking, no supplied curated sources,
no custom splash artwork. RSS discovery tries advertised RSS/Atom links and five
common fallback paths. Site-specific discovery and unusual feeds may need fixes.
