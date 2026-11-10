# Contributing to CCA

Thanks for helping. CCA is a one-person project, so replies may take a while, but every issue and pull
request is read.

## Reporting a bug

[Open an issue](https://github.com/piitex/cca/issues/new/choose) and fill in the template. The most useful
things are your CCA version, operating system, and the steps to reproduce the problem. If CCA crashed or a
local model would not start, attach the log files from CCA's data folder (`server.log` and similar).
**Remove anything private first**: log files can contain file paths and, depending on the failure, parts of
your chats. Never post `secret.key`, API keys or Hugging Face tokens.

Security problems go through [SECURITY.md](SECURITY.md), not a public issue.

## Suggesting a feature

Open an issue first and describe what you want to do, not only the solution. For anything big, agree on
the approach before writing code so your time is not wasted.

## Building

You need JDK 25.

```bash
./mvnw package        # Windows: mvnw.cmd package
```

Run `me.piitex.cca.Main` to start the app. From source, CCA stores its data in `app/` inside the project
(git-ignored). Use a throwaway copy of your data there, not your real characters.

## Pull requests

* Keep each pull request to one change. Small ones are reviewed faster.
* Match the surrounding code style. Comments explain why, not what.
* Make sure `./mvnw package` passes.
* Test the change in the running app. Say in the description what you tried and on which operating system.
* Do not commit generated files, `app/`, `dist/`, `target/`, IDE settings, models, or any personal data.
* Say if AI helped write your change. That is fine, but you are responsible for what you submit.

By submitting a contribution you agree it is licensed under the project's [MIT License](LICENSE).

## Bundled content

Do not add images, fonts, character cards or other assets you do not have the right to redistribute.
Add the source and license of anything new to [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
