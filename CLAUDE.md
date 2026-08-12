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
extension, MIME type, preference node, package name, bundle identifiers and FPGA workspace
directory — and any new on-disk path must get the same treatment. The fork previously
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

Development happens on the Linux VM (Ubuntu 22.04). Temurin 21 is on the PATH via SDKMAN, so the
Gradle tasks need no `JAVA_HOME` juggling:

```bash
./gradlew check shadowJar
```

`~/DEV_ENV.md` is the environment cheat sheet and `devenv-verify` smoke-tests the toolchain.

Everything except publishing a release is local. `gh` is installed but the account login is the
maintainer's to do (`gh auth login`) — and `gh` needs the repo spelled out here, or it resolves the
working copy to upstream and 404s:

```bash
gh workflow run release.yml --repo PelerYuan/logisim-evolution-peler --ref main -f channel=dev -f platforms=windows
```

`platforms=windows` builds only the MSI and portable zip; a full dev build is mostly waiting. Stable
releases always build everything, and a pushed tag ignores the input.

Historical note: the fork was started on a Windows machine with no system JDK, where builds needed
`JAVA_HOME="C:/Users/peler/AppData/Local/Programs/Android Studio/jbr"` per command. That is where
"cannot be tested here" claims in older notes come from. Re-check such a claim before believing it —
most of them were about that machine, not about the code.

### Running and driving the application

There is a real Xorg session on `DISPLAY=:0`, so the application can be launched, clicked through
and screenshotted:

```bash
DISPLAY=:0 java -jar build/libs/logisim-evolution-peler-4.1.0-all.jar
```

Three things to know before automating it:

- **Send real pointer and key events.** `xdotool mousemove X Y click 1` and plain `xdotool key`
  go through XTEST and reach Swing — including `ctrl+f`, `Return`, `shift+Return` and `Escape`.
  Events addressed at a window — `xdotool key --window <id> alt+f` — are silently ignored. An
  earlier note concluded from that silence that "the keys never arrive at the JVM"; they do, and
  believing otherwise hid a real bug in the finder for weeks.
- **Screenshots need no extra package.** A five-line `java.awt.Robot` program run as
  `java Screenshot.java out.png` captures the screen; JDK 21 runs single-file sources directly.
- **A real X display is not headless**, so the `hotkeyMenuMask` hazard below does not apply to it.
- **Never close a window with `xdotool windowclose`.** Its manual page says it "will destroy the
  window, but will not try to kill the client controlling it": it calls `XDestroyWindow` rather
  than sending `WM_DELETE_WINDOW`, so the window disappears while Swing still believes it is
  visible — and every later `setVisible(true)` on it is then a silent no-op. This manufactured a
  convincing phantom bug (the Preferences window "could not be reopened", reproducing every time on
  an unmodified build) that cost a wrong report before it was caught. Click the real titlebar
  button, or send `_NET_CLOSE_WINDOW`. There is no `wmctrl` on this machine.

Do not run the application headless for probing. `AppPreferences.hotkeyMenuMask` degrades to
`ALT_DOWN_MASK` when headless, and any new hotkey preference then persists that wrong default into
the real store. Verified that a GUI run on this VM is safe by decoding `hotkeyEditUndo` afterwards:
it held `CTRL_DOWN_MASK`, not the degraded `ALT`.

## Releases

Two channels, deliberately kept apart.

**Dev** — the normal one while iterating. `channel=dev` republishes a single rolling `dev`
pre-release, so the releases page holds exactly one dev entry however many builds happen and the
download URL never changes. The publish job deletes the previous dev release and its tag first.

**Stable** — pushing a `v*` tag (or dispatching with `channel=stable`) publishes a permanent
release; then set its notes with `gh release edit --notes-file` and `--latest --prerelease=false`.

### Numbering

**Stable is `vX.Y.0` — always bump the major or the minor, never the patch.** The workflow fails the
run if a stable tag has a non-zero patch, on purpose.

**Dev is `X.Y.<github.run_number>`, where `X.Y` comes from the stable release GitHub marks latest.**
So with stable at v1.1.0, dev builds are `1.1.<run>`; cut v1.2.0 and they become `1.2.<run>` on
their own, with nothing to edit.

The reason is not tidiness. All channels share one `--win-upgrade-uuid`, so Windows Installer
compares these numbers *across* channels. The original scheme pinned dev at `1.0.<run>`, which made
every dev build sort below stable v1.1.0 and refuse to install over it — while being, by definition,
the newer code. Reserving the patch field for dev keeps dev ahead of the stable it was cut from, and
the next stable's minor bump keeps stable ahead of every dev build before it. A stable `1.1.1` would
land underneath dev `1.1.32`, which is what the tag check prevents.

The base is read from `repos/{owner}/{repo}/releases/latest`, **not** from git tags: this repository
still carries upstream's tags and the highest of those is `v4.1.0`. The dev release is a prerelease,
so that endpoint already ignores it.

`v1.0.0-peler.1` through `v1.0.20` were dev builds under the old scheme, kept as tags only. Nothing
publishes into `1.0.x` any more.

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
| Interactive HTML export (experimental) | `gui/htmlexport/`, `gui/generic/TikZInfo.java`, `resources/logisim/html/` |
| This edition's settings page | `gui/prefs/PelerOptions.java`, `tools/ContinuousPlacement.java` |

New user-visible strings need all 12 locales, in `src/main/resources/resources/logisim/strings/`.

**A new preference needs a control, not just a field.** `WIRE_AUTO_SNAP` sat in `AppPreferences`
for four days being read by `WiringTool` and written by nothing, while the README said it could be
turned off in preferences. Everything compiled and every test passed.
`PelerOptionsTest.testEveryPelerPreferenceIsReachableFromThePreferencesWindow` now fails the build
for any preference this edition declares that no panel under `gui/prefs/` mentions; add genuinely
internal ones to its exemption list with the reason rather than deleting the check.

## Open items

See the "Known open items" section at the end of the roadmap. The one to be careful with: the CJK
tofu bug in the project explorer is **deliberately unfixed** at the maintainer's instruction. Do not
fix it without being asked.
