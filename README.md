[![Logisim-evolution](docs/img/logisim-evolution-logo.png)](https://github.com/logisim-evolution/logisim-evolution)

---

# Logisim-evolution — Peler's Edition #

> ## This is an unofficial personal fork. ##
>
> **Essentially all of this software is the work of the
> [Logisim-evolution](https://github.com/logisim-evolution/logisim-evolution) developers and its
> contributors**, built on decades of effort by many people. This fork adds a handful of small
> workflow conveniences for one person's coursework, and nothing more.
>
> It is **not affiliated with, endorsed by, or supported by** the Logisim-evolution project.
>
> **If you are looking for Logisim-evolution, go to
> [the official project](https://github.com/logisim-evolution/logisim-evolution) — not here.**
> It is better maintained, better tested, properly released, and it is the software you actually
> want. Please give the upstream project your stars, your bug reports, and your credit.

---

* **Table of contents**
  * [What this fork changes](#what-this-fork-changes)
  * [Relationship to the upstream project](#relationship-to-the-upstream-project)
  * [Downloads](#downloads)
  * [Requirements](#requirements)
  * [Reporting problems](#reporting-problems)
  * [License and credits](#license-and-credits)

---

## What this fork changes ##

Based on the official Logisim-evolution **v4.1.0** release. Everything else behaves as upstream
does; the additions below are the entire difference.

**Continuous placement**
Double-click a component — in the toolbox or the toolbar — to keep placing it instead of re-picking
it each time. <kbd>Esc</kbd>, <kbd>Enter</kbd> or a right-click stops.

![Placing several gates in a row after one double-click](docs/img/peler-edition/ContinuousPlacement.gif)

**Quick Rotate**
Right-click a component to rotate it 90° clockwise. The original right-click menu moved to
<kbd>Ctrl</kbd>+left-click.

![Right-clicking a gate to rotate it in place](docs/img/peler-edition/QuickRotation.gif)

**Wire auto-snap**
While drawing a wire, endpoints snap to a nearby component pin, with a green ring marking the pin it
will attach to. Can be turned off in preferences.

![A wire end snapping onto a highlighted component pin](docs/img/peler-edition/AutoSwap.gif)

**Schematic annotations**
A new **Annotate** category for attaching free-text notes to a component, or to a wire endpoint
where it meets a component. Notes take multiple lines and follow whatever they are attached to when
it is moved, rotated, or deleted. Double-click an annotate tool to keep annotating.

![Adding a note above a gate with the annotate tool](docs/img/peler-edition/Annotation.gif)

The application is packaged as `logisim-evolution-peler` and its icon carries a red **P**, so it
installs alongside an official Logisim-evolution install rather than colliding with it. The
in-application **Help → About** window is left exactly as upstream ships it, crediting upstream;
this fork's own changes are described under **Help → About Peler's Edition**.

---

## Relationship to the upstream project ##

* This fork tracks official Logisim-evolution **releases**, not upstream's development branch, so
  it does not ship unreleased upstream work.
* No upstream functionality is removed or altered beyond the additions listed above.
* Upstream's copyright notices, credits, and attribution are left intact.
* Bug reports for **this build** belong in
  [this fork's issue tracker](https://github.com/PelerYuan/logisim-evolution-peler/issues).
  **Never report them to the upstream project** — they did not build this and cannot support it.
  If you can reproduce a problem in the official Logisim-evolution release, report it upstream instead,
  where it will actually get fixed for everyone.

---

## Downloads ##

Installable packages are on the
[releases page](https://github.com/PelerYuan/logisim-evolution-peler/releases). Each bundles its
own Java runtime, so Java does not need to be installed separately:

* `logisim-evolution-peler-<version>-amd64.msi` — Windows installer (Intel/AMD)
* `logisim-evolution-peler-<version>-windows-amd64.zip` — Windows, no installer
* `logisim-evolution-peler-<version>-x86_64.dmg` — macOS (Intel)
* `logisim-evolution-peler-<version>-aarch64.dmg` — macOS (Apple silicon)
* `logisim-evolution-peler_<version>_amd64.deb` — Debian/Ubuntu (x86-64)
* `logisim-evolution-peler_<version>_arm64.deb` — Debian/Ubuntu (ARM64)
* `logisim-evolution-peler-<version>-1.x86_64.rpm` — Fedora/RHEL/SUSE (x86-64)
* `logisim-evolution-peler-<version>-1.aarch64.rpm` — Fedora/RHEL/SUSE (ARM64)

Releases marked *pre-release* are development builds kept only for history; use the latest normal
release.

**Note for macOS users**: these packages are not signed with an Apple certificate. On first launch,
right-click (or <kbd>Ctrl</kbd>+click) the application icon in Finder and choose **Open**, then
confirm. See [Safely open apps on your Mac](https://support.apple.com/en-us/HT202491).

---

## Requirements ##

A Java application, so it runs anywhere with a Java runtime. The packages above bundle
[Java 21](https://adoptium.net/temurin/releases/); building from source requires it.

---

## Reporting problems ##

Please check first whether the problem also happens in the
[official Logisim-evolution release](https://github.com/logisim-evolution/logisim-evolution/releases).

* **Happens in the official release too** → report it
  [upstream](https://github.com/logisim-evolution/logisim-evolution/issues), so the fix reaches
  everyone.
* **Only happens here** → it is this fork's fault; report it in
  [this fork's issue tracker](https://github.com/PelerYuan/logisim-evolution-peler/issues).

---

## License and credits ##

* `Logisim-evolution` is copyrighted ©2001-2024 by the Logisim-evolution
  [developers](docs/credits.md). The overwhelming majority of this codebase is their work.
* This fork is released under the same license: the
  [GNU General Public License v3](https://www.gnu.org/licenses/gpl-3.0.en.html).
* Original Logisim was created by Carl Burch; Logisim-evolution is the continuation of that work by
  its developers and contributors. Full credits: [docs/credits.md](docs/credits.md) and the
  application's **Help → About** window.
