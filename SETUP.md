# Installation

The easiest way to install Baritone is to install it as a Forge/Neoforge/Fabric mod. This fork is built for Minecraft 1.21.11 with Fabric.

Once Baritone is installed, look [here](USAGE.md) for instructions on how to use it.

## Prebuilt official releases
Releases are made rarely and are not always up to date with the latest features and bug fixes.

Link to the releases page: [Releases](https://github.com/cabaletta/baritone/releases)

This fork is built for **Minecraft 1.21.11** with Fabric. Older versions for Minecraft 1.12–1.20 are available in the [releases](https://github.com/cabaletta/baritone/releases) page.

Any official release will be GPG signed by leijurv (44A3EA646EADAC6A). Please verify that the hash of the file you download is in `checksums.txt` and that `checksums_signed.asc` is a valid signature by that public keys of `checksums.txt`. 

The build is fully deterministic and reproducible, and you can verify that by running `docker build --no-cache -t cabaletta/baritone .` yourself and comparing the shasum. This works identically on GitHub Actions, Mac, and Linux (if you have docker on Windows, I'd be grateful if you could let me know if it works there too).


## Artifacts

Building Baritone will create the final artifacts in the ``dist`` directory. These are the same as the artifacts created in the [releases](https://github.com/cabaletta/baritone/releases).

**The Forge, NeoForge and Fabric releases can simply be added as a Forge/Neoforge/Fabric mods.**

If another one of your other mods has a Baritone integration, you want `baritone-api-*-VERSION.jar`.
If you want to report a bug and spare us some effort, you want `baritone-unoptimized-*-VERSION.jar`.
Otherwise, you want `baritone-standalone-*-VERSION.jar`

Here's what the various qualifiers mean
- **API**: Only the non-api packages are obfuscated. This should be used in environments where other mods would like to use Baritone's features.
- **Standalone**: Everything is obfuscated. Other mods cannot use Baritone, but you get a bit of extra performance.
- **Unoptimized**: Nothing is obfuscated. This shouldn't be used in production, but is really helpful for crash reports.

- **No loader**: Loadable as a launchwrapper tweaker against vanilla Minecraft using a custom `version.json`.
- **Forge/Neoforge/Fabric**: Loadable as a standard mod using the respective loader. The fabric build may or may not work on Quilt.

If you build from source you will also find mapping files in the `dist` directory. These contain the renamings done by ProGuard and are useful if you want to read obfuscated stack traces.

## Build it yourself
- Clone or download Baritone

  ![Image](https://i.imgur.com/kbqBtoN.png)
  - If you choose to download, make sure you download the correct branch and extract the ZIP archive.
- Follow one of the instruction sets below, based on your preference

## Command Line
On Mac OSX and Linux, use `./gradlew` instead of `gradlew`.

The recommended Java versions by Minecraft version are
| Minecraft version             | Java version  |
|-------------------------------|---------------|
| 1.12.2 - 1.16.5               | 8             |
| 1.17.1                        | 16            |
| 1.18.2 - 1.20.4               | 17            |
| 1.20.5 - 1.21.11              | 21            |

Download java: https://adoptium.net/

To check which java version you are using do `java -version` in a command prompt or terminal.

### Building Baritone

These tasks depend on the minecraft version, but are (for the most part) standard for building mods.

For more details, see [the build ci action](/.github/workflows/gradle_build.yml) of the branch you want to build.

For this fork, `gradlew build` builds the Fabric mod. The project uses Unimined for multi-loader setup.

## IntelliJ
- Open the project in IntelliJ as a Gradle project
- Refresh the Gradle project (or, to be safe, just restart IntelliJ)
- The project should work out of the box with the Unimined plugin

## Github Actions
Most branches have a CI workflow at `.github/workflows/gradle_build.yml`. If you fork this repository and enable actions for your fork
you can push a dummy commit to trigger it and have GitHub build Baritone for you.

If the commit you want to build is less than 90 days old, you can also find the corresponding workflow run in
[this list](https://github.com/cabaletta/baritone/actions/workflows/gradle_build.yml) and download the artifacts from there.
