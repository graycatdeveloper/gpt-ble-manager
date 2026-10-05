# Publishing to Maven Central

The project supports local publication and an explicit Maven Central release mode.
No Central release has been performed as part of this setup. The README badge is
marked **publication pending** until the first release is available.

## 1. Register a namespace

Sign in to [Central Portal](https://central.sonatype.com/) using the GitHub account
`graycatdeveloper`, then check the namespace list. GitHub sign-up normally provides
the verified namespace `io.github.graycatdeveloper`. If you use a custom domain,
complete Sonatype's ownership verification for that namespace instead.
See [namespace registration](https://central.sonatype.org/register/namespace/).

Recommended release coordinates for this repository:

| Artifact | Coordinates |
| --- | --- |
| Kotlin Multiplatform entry point | `io.github.graycatdeveloper:gpt-ble-manager:<version>` |
| Windows JVM | `io.github.graycatdeveloper:gpt-ble-manager-windows:<version>` |
| Android | `io.github.graycatdeveloper:gpt-ble-manager-android:<version>` |

The Maven group is independent of the Kotlin package and Android namespace:
both remain `gpt.ble.manager`. Local builds keep the existing Maven group
`gpt.ble.manager` unless `-PmavenGroup=...` is supplied. A local group name alone
does not establish ownership of a Central namespace.

## 2. Configure publishing credentials and signing

Generate a publishing user token in
[Central Portal → User Tokens](https://central.sonatype.com/usertoken).
The resulting username/password pair is for publishing; it is not your GitHub
password. See [Sonatype's token instructions](https://central.sonatype.org/publish/generate-portal-token/).

Put the following properties in your user-level `%USERPROFILE%\.gradle\gradle.properties`,
outside the repository. Replace the placeholder values:

```properties
mavenCentralUsername=YOUR_PORTAL_TOKEN_USERNAME
mavenCentralPassword=YOUR_PORTAL_TOKEN_PASSWORD
signingInMemoryKeyPassword=YOUR_SIGNING_KEY_PASSPHRASE
```

Create a GPG signing key, retain its fingerprint, and publish its public key to a
supported keyserver. For example, with GnuPG installed:

```powershell
gpg --full-generate-key
gpg --list-secret-keys --keyid-format LONG
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_FINGERPRINT
```

Export the private key directly to a file outside the project, then make its contents
available to Gradle through an environment variable in the release terminal:

```powershell
$signingKeyFile = Join-Path $env:USERPROFILE '.gradle/gpt-ble-manager-signing.asc'
gpg --armor --output $signingKeyFile --export-secret-keys YOUR_FINGERPRINT
$env:ORG_GRADLE_PROJECT_signingInMemoryKey = Get-Content -Raw -LiteralPath $signingKeyFile
```

Do not commit the private key, passphrase, or Portal token. CI should supply these
values from its secret store. Central verifies signatures using the public key;
see [Sonatype's GPG guide](https://central.sonatype.org/publish/requirements/gpg/).

## 3. Choose and check the release

Use a version intended for public release, such as `0.2.3`, instead of the current
local label `0.2.3-local`. The commands below use `0.2.3` as an example, not as a
claim that it has been released. After publication, a Central version cannot be
replaced: fixes require a new version.
See [Central registration and release rules](https://central.sonatype.org/register/central-portal/).

Run the release build on Windows x64 with the JDK, Android SDK, Visual Studio C++
tools, and Windows SDK described in the [README](../README.md). Windows is required
to bundle the native DLL in the Windows JAR.

First, build and publish to a local inspection directory without credentials or signing:

```powershell
.\gradlew.bat :gpt-ble-manager:allTests :gpt-ble-manager:assemble :sample-windows:classes `
    "-PmavenGroup=io.github.graycatdeveloper" "-PreleaseVersion=0.2.3"

.\gradlew.bat :gpt-ble-manager:publish `
    "-PmavenGroup=io.github.graycatdeveloper" "-PreleaseVersion=0.2.3" `
    "-PlocalRepositoryPath=build/central-preview"
```

The output directory is relative to the library module, so this example writes to
`gpt-ble-manager/build/central-preview`. Inspect all three publications: POM metadata,
KMP target references, sources, documentation archives, and the bundled
`natives/windows-x64/gpt-ble-windows.dll`.

The publishing plugin version is in `gradle/libs.versions.toml`. The build supplies
the project URL, developer, SCM, and MIT metadata. Documentation archives contain
the maintained README and guides; they are not generated API reference pages.
Central also requires source/documentation archives, checksums, and signatures.
See [Central requirements](https://central.sonatype.org/publish/requirements/).

## 4. Upload, validate, and release

The Central mode requires an explicit group and a non-local version. After configuring
the token and signing key, upload with:

```powershell
.\gradlew.bat :gpt-ble-manager:publishToMavenCentral `
    "-PpublishToCentral=true" `
    "-PmavenGroup=io.github.graycatdeveloper" `
    "-PreleaseVersion=0.2.3"
```

This uploads a signed deployment for Portal validation. Automatic release is disabled.
Open [Central Portal → Deployments](https://central.sonatype.com/publishing/deployments),
inspect validation results, and select **Publish** when the deployment is ready.
The plugin also exposes `publishAndReleaseToMavenCentral`, which releases automatically;
the command above deliberately uses the manual Portal step.
See the [publishing plugin's release workflow](https://vanniktech.github.io/gradle-maven-publish-plugin/central/).

Upload the KMP entry point and both platform artifacts together from the same Windows
build. A Git push does not publish Maven artifacts. `publishToMavenLocal` writes only
to your machine's Maven cache, and ordinary `publish` uses the configured local directory.
Keep `-PpublishToCentral=true` for dedicated Central commands.

After publishing, remove the private key value from the terminal environment:

```powershell
Remove-Item Env:ORG_GRADLE_PROJECT_signingInMemoryKey
```

## 5. Update consumers and the README badge

After the release is available from Central, consumers can use:

```kotlin
repositories {
    mavenCentral()
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.graycatdeveloper:gpt-ble-manager:0.2.3")
        }
    }
}
```

Replace the README's pending badge with a live version badge for the verified group:

```markdown
[![Maven Central](https://img.shields.io/maven-central/v/io.github.graycatdeveloper/gpt-ble-manager)](https://central.sonatype.com/artifact/io.github.graycatdeveloper/gpt-ble-manager)
```

Also update the README dependency examples to the released group and version.
Until then, its examples continue to describe the working local publication.
See [Shields Maven Central badges](https://shields.io/badges/maven-central-version).
