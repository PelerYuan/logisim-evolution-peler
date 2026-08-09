# Working notes for Claude

This file is the standing context for anyone (human or Claude) picking up work on this fork. The
long-form design history lives in [docs/peler-edition/ROADMAP.md](docs/peler-edition/ROADMAP.md);
this file holds the rules and the environment facts that are easy to get wrong.

## What this is

An unofficial personal fork of
[Logisim-evolution](https://github.com/logisim-evolution/logisim-evolution), published at
[PelerYuan/logisim-evolution-peler](https://github.com/PelerYuan/logisim-evolution-peler). Almost
all of the code is upstream's. The fork's own additions are listed in the README and designed in the
roadmap.

The maintainer writes in Chinese; answer in Chinese unless asked otherwise.

## Standing rules

**No emoji anywhere** — not in docs, code comments, commit messages, or UI strings. Use plain-text
status markers (`DONE:` / `TODO:`). Ordinary typographic symbols used as punctuation (arrows,
not-equals) are fine; the rule is about decorative pictographs.

**Align with the official release, not upstream's development branch.** The comparison point is
official Logisim-evolution **v4.1.0** (2026-02-15). The fork was originally branched from upstream's
`main`, so it still carries roughly 372 commits that were never in any release. When behaviour is
reported as differing from the official version, check whether the responsible commit actually
shipped:

```bash
git merge-base --is-ancestor <commit> v4.1.0
```

Being upstream-authored is not the same as being shipped. Revert unrequested deviations.

**Never cut a stable release unless the maintainer explicitly says to.** They test the dev build by
hand and say when it is good. Dev builds are free; stable releases are not.

**`.pcirc` is free to diverge.** The fork's everyday format is its own, so a new feature does not
have to answer "how does this survive in official Logisim". Only the `.circ` lowering in
`XmlWriter`'s compat mode does. Do not smuggle fork data into a compatible file: unknown `<a>`
attributes and unknown children of `<circuit>` are silently ignored by upstream's reader and then
discarded by its writer, so they buy nothing, and an unknown element directly under `<project>`
makes `XmlReader.toLogisimFile` throw so the file will not open at all.

**Keep the two editions apart.** Any packaging change must preserve this edition's own file
extension, MIME type, preference node, package name and bundle identifiers. The fork previously
declared `extension=circ` and `application/x-logisim-circuit`, byte-identical to upstream, so
whichever edition was installed last owned every `.circ` on the machine. That class of bug must not
come back.

**`default.templ` is fragile.** A literal `--` anywhere inside an XML comment breaks the whole
template, and every project then fails to open. Validate before pushing:

```bash
python3 -c "import xml.dom.minidom as m; m.parse('src/main/resources/resources/logisim/default.templ')"
```

Registering a new `Library` in `Builtin.java` is necessary but not sufficient — a new project only
loads what `default.templ`'s `<lib>` list names.

## Environment

There is no system JDK on the original development machine. Local builds use Android Studio's
bundled JBR 21, set per-command and never committed:

```bash
JAVA_HOME="C:/Users/peler/AppData/Local/Programs/Android Studio/jbr" ./gradlew shadowJar
```

Do not run the application headless for probing. `AppPreferences.hotkeyMenuMask` degrades to
`ALT_DOWN_MASK` when headless, and any new hotkey preference then persists that wrong default into
the real registry.

`gh` needs the repo spelled out here, or it resolves the working copy to upstream and 404s:

```bash
gh workflow run release.yml --repo PelerYuan/logisim-evolution-peler --ref main -f channel=dev -f platforms=windows
```

`platforms=windows` builds only the MSI and portable zip; a full dev build is mostly waiting. Stable
releases always build everything, and a pushed tag ignores the input.

## Releases

Two channels, deliberately kept apart.

**Dev** — the normal one while iterating. `channel=dev` republishes a single rolling `dev`
pre-release, so the releases page holds exactly one dev entry however many builds happen and the
download URL never changes. The publish job deletes the previous dev release and its tag first.
Package version is `1.0.<github.run_number>`.

**Stable** — pushing a `v*` tag (or dispatching with `channel=stable`) publishes a permanent
release; then set its notes with `gh release edit --notes-file` and `--latest --prerelease=false`.

Numbering: stable starts at **v1.1.0** and goes up. `1.0.x` is permanently reserved by the dev
channel's `1.0.<run_number>` scheme. `v1.0.0-peler.1` through `v1.0.20` were dev builds, kept as tags
only.

jpackage constraints learned the hard way: macOS rejects an app version whose first number is zero,
and the Windows MSI ProductVersion field caps parts at `255.255.65535`, so date-derived versions like
`2026.8.8` are invalid too. Any scheme must yield plain numeric `x.y.z` inside those bounds.

`--win-upgrade-uuid` is pinned to `48443d3c-0700-47f9-b825-40c7118027da` in `build.gradle.kts` and
**must never change** — Windows Installer's major-upgrade mechanism keys off it staying constant.

Version numbers: `gradle.properties` `version` stays upstream's and must keep tracking upstream,
because `BuildInfo.version` feeds `.circ` file-format compatibility logic in `XmlReader`/`XmlWriter`.
The fork's own package version is `pelerAppVersion`, resolved in `.github/workflows/release.yml`.

## Where the fork's own code lives

| Area | Files |
| --- | --- |
| Continuous placement | `tools/AddTool.java`, `gui/main/ToolboxManip.java` |
| Quick rotate | `tools/QuickRotateTool.java`, `std/base/BaseLibrary.java` |
| Wire auto-snap | `tools/WiringTool.java` |
| Annotations | `std/annotate/`, `tools/AbstractAnnotateTool.java` and its two subclasses |
| Tidy wires (deferred, unregistered) | `circuit/WireTidier.java`, `tools/TidyWiresTool.java` |
| Dual file format | `file/PelerCompat.java`, `file/Loader.java`, `file/XmlWriter.java`, `proj/ProjectActions.java` |
| Component finder | `gui/find/ToolSearch.java`, `gui/find/FindToolDialog.java` |
| Settings isolation | `prefs/PelerPreferences.java`, `prefs/AppPreferences.java`, `Main.java` |
| Fork's About window | `gui/start/AboutPelerEdition.java` |

New user-visible strings need all 12 locales, in `src/main/resources/resources/logisim/strings/`.

## Open items

See the "Known open items" section at the end of the roadmap. The one to be careful with: the CJK
tofu bug in the project explorer is **deliberately unfixed** at the maintainer's instruction. Do not
fix it without being asked.
