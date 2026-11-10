# Changelog

All notable changes are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/),
and the project uses [Semantic Versioning](https://semver.org/).

## [1.0] - Unreleased

First public release.

### Added
- Character cards: create, edit, import and export (SillyTavern- and BackyardAI-style PNG cards)
- Users (personas), lorebooks, chats and folders
- Local models through llama.cpp, with in-app download of the backend and models from Hugging Face
- Connection to an OpenAI-compatible server or cloud endpoint
- Hardware probe with setting suggestions
- App password that encrypts characters, users, chats, images and stored keys
- Themes, animated backgrounds and a spell checker
- Installers for Windows (x64), Linux (x64, arm64; `.deb` and `.rpm`) and macOS (x64, arm64)

### Notes
- The installers are not code-signed. See the README for how to get past SmartScreen and Gatekeeper.
- Linux and macOS builds have had little testing.
