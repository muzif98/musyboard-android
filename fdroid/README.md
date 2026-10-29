# F-Droid

`io.github.muzif98.musyboard.yml` is the recipe to submit to
[fdroiddata](https://gitlab.com/fdroid/fdroiddata), in the canonical format of
`fdroid rewritemeta`: it goes into `metadata/` as it is. Tested with
fdroidserver (`fdroid build -v -l io.github.muzif98.musyboard`): build
successful, scanner clean.

## Already in place

- Own `applicationId` (`io.github.muzif98.musyboard`), distinct from HeliBoard.
- Cryptographic core as the `core/` submodule, pinned to a commit: the build
  compiles it from source with `cargo-ndk`; no prebuilt `.so` in either
  repository.
- No Maven repository other than Google and Maven Central.
- Licenses: app GPL-3.0, core GPL-3.0-only, all dependencies compatible.
- Store texts in `fastlane/metadata/android/` (English and Italian).
- No INTERNET permission: no network anti-feature.

## Still to do

1. **Test the recipe** with fdroidserver or the fdroiddata pipeline. The
   delicate points are rustup and cargo-ndk on the PATH of the Gradle build,
   which invokes `cargo` directly.
2. **Test the release build** on a phone: F-Droid distributes that one, signed
   with its own key. Users of APKs from GitHub releases will have to move their
   identity with backup export/import.
3. Consider **reproducible builds**, if F-Droid should distribute the APK signed
   with the project's own key.
