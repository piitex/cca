# Security policy

## Supported versions

Only the latest release gets security fixes.

## Reporting a vulnerability

Please do not report a security problem in a public issue.

Use GitHub's private reporting instead: open
<https://github.com/piitex/cca/security/advisories/new> (or the **Security** tab, then
**Report a vulnerability**). Only the maintainer can see the report.

If that page is not available, open an issue that says you have a security report and gives no details,
and the maintainer will arrange a private way to send it.

Please include the CCA version, your operating system, and the steps to reproduce the problem.

This is a one-person project, so there is no guaranteed response time. Reports are read and answered as
soon as the maintainer can.

## What counts

Problems in CCA's own code, for example:

* a way to read data that should be protected by the app password
* unsafe handling of an imported character card, archive, image or downloaded file
* sending your data somewhere you did not configure
* the installers or release files not matching the published checksums

## What does not

* Problems in a model, or what a model says. Models are not part of CCA.
* Problems in llama.cpp, RenGL or the other libraries CCA uses. Please report those to their projects.
* Someone who already has full access to your account and your unlocked computer. The app password is
  there to protect your files on disk, not to defend against malware running as you.
* Data you lose after forgetting the app password. It is not recoverable by design.

## Checking a download

Each release lists SHA-256 checksums. The installers are not code-signed, so compare the checksum of
your download with the one on the release page before running it.
