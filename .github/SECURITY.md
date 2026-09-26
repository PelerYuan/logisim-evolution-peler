# Security

This is an unofficial personal fork of
[Logisim-evolution](https://github.com/logisim-evolution/logisim-evolution). Almost all of the code
here is upstream's; the split below follows the same rule as this fork's
[bug-reporting guidance](../README.md#reporting-problems) — where a vulnerability lives decides who
should hear about it.

## Where to report

- **The vulnerability is in code this fork changed or added** — the custom-component system, the
  embedded MCP server, the file-format handling, or anything else listed under "Where the fork's own
  code lives" in [`CLAUDE.md`](../CLAUDE.md) — report it **here**, privately if at all possible.
  If this repository's **Security** tab offers **Report a vulnerability** (GitHub's private
  vulnerability reporting), use that. If it does not, open a regular issue that says only that a
  security-relevant report exists without exploit detail, and the maintainer will follow up with a
  private channel — do not post exploit details or proof-of-concept code in a public issue.
- **The vulnerability is in unmodified upstream code** — report it to
  [the upstream project](https://github.com/logisim-evolution/logisim-evolution/security), following
  their own [security policy](https://github.com/logisim-evolution/logisim-evolution/blob/main/.github/SECURITY.md),
  so the fix reaches every downstream user, not just this fork's. This fork does not maintain its own
  copy of upstream's PGP key or reporting process; use theirs directly.
- **Not sure which side it's on?** Report it here. Distinguishing the two is this fork maintainer's
  job, not the reporter's.

Because this repository's socket-listening feature (the embedded MCP server, see `mcp/` and
[`docs/peler-edition/mcp/`](../docs/peler-edition/mcp/)) is entirely this fork's own addition and is
off by default, any vulnerability in it is always a fork-specific report, never an upstream one.

## What to include

* Type of issue (e.g. buffer overflow, SQL injection, cross-site scripting, etc.)
* Full paths of source file(s) related to the manifestation of the issue
* The location of the affected source code (tag/branch/commit or direct URL)
* Any special configuration required to reproduce the issue
* Step-by-step instructions to reproduce the issue
* Proof-of-concept or exploit code (if possible)
* Impact of the issue, including how an attacker might exploit the issue

This information helps triage a report faster.

## Preferred languages

English or Chinese are both fine for reports against this fork.
