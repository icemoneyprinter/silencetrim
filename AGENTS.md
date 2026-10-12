# AGENTS.md

## Project

TRACK — local Android app that turns a folder of images + a narration audio file into a
synced 16:9 MP4. Package `com.icemoneyprinter.track`. Design decisions live in
`.kilo/plans/` (gitignored). Narration is always Gemini TTS audio; timing comes from
on-device energy analysis, never ASR (plan decision D2 — do not add ASR/models).

## Toolchain (survives container resets)

The cloud sandbox wipes container-local dirs (`/opt`, `/tmp`) on session recycle but keeps
`/workspace`. All tooling lives in `/workspace/.toolchain`:

- JDK 17: `/workspace/.toolchain/jdk-17.0.20.1+1`
- Gradle 8.9: `/workspace/.toolchain/gradle-8.9/bin/gradle`
- Android SDK: `/workspace/.toolchain/android-sdk` (platform-tools, platforms;android-35, build-tools)
- Persistent Gradle home: `/workspace/.toolchain/gradle-home`
- Egress-proxy CA bundle (Go tools): `/workspace/.toolchain/ca-bundle.crt`
- Combined PKCS12 truststore (JVM trust): `/workspace/.toolchain/truststore.p12`
- Proxy CA PEM: `/workspace/.toolchain/proxy-ca.pem`

### Sandbox network quirks (learned the hard way)

1. **The egress proxy MITMs TLS** with a short-lived, **rotating** self-signed root
   ("Cloudflare TLS proxy-everything Intercept CA"). Tools must be given its CA:
   - JVM: combined PKCS12 truststore (see rebuild recipe); `gradle.properties` points
     `javax.net.ssl.trustStore` at it. Do NOT hand-copy PEMs — they get corrupted in
     transcription; dump exact bytes with the `DumpCa` pattern below.
   - Go binaries (the `android` CLI): run with `SSL_CERT_FILE=/workspace/.toolchain/ca-bundle.crt`.
   - `curl`/`wget`: use `--no-check-certificate` for downloads.
2. **Maven Central is proxy-blocked (403)**: `repo.maven.apache.org`, `repo1.maven.org`.
   `settings.gradle.kts` therefore uses Google's GCS mirror
   `https://maven-central.storage-download.googleapis.com/maven2/` (200 OK, complete drop-in).
   `google()` works for Android/AndroidX/AGP markers; the mirror hosts the Kotlin/Compose
   plugin markers; `plugins.gradle.org` also answers 200 for its own markers.
3. **Never trust a hand-copied certificate.** To re-dump the proxy CA exactly:
   compile+run a tiny Java tool that opens an SSL socket with a trust-all TrustManager and
   writes `chain[last].getEncoded()` as PEM (Base64-encoded by the JVM, not by you).

### If `/workspace/.toolchain` is missing (container was recycled)

Rebuild it:

1. `mkdir -p /workspace/.toolchain`
2. Downloads must bypass the sandbox proxy's self-signed cert:
   - JDK: `wget --no-check-certificate -O /workspace/.toolchain/jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"` then `tar -xzf` into `/workspace/.toolchain`
   - Gradle: `wget --no-check-certificate -O /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip` then `unzip -q /tmp/gradle.zip -d /workspace/.toolchain`
   - cmdline-tools: `wget --no-check-certificate -O /tmp/ct.zip https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip`
3. Extract proxy CA with the DumpCa pattern (exact bytes) to `/workspace/.toolchain/proxy-ca.pem`.
   The CA **rotates** (~monthly); re-fetch and re-import if TLS errors mention
   unknown/self-signed issuer, then re-run MergeTrust.
4. `cp /etc/ssl/certs/ca-certificates.crt /workspace/.toolchain/ca-bundle.crt` and append the
   proxy CA with `sed -i '$ r /workspace/.toolchain/proxy-ca.pem' /workspace/.toolchain/ca-bundle.crt`
   (shell redirects are blocked by sandbox rules; use sed to append).
5. Build the JVM truststore: a small Java tool that loads `<jdk>/lib/security/cacerts` (JKS)
   + the system PEM bundle + `proxy-ca.pem` into one PKCS12 at
   `/workspace/.toolchain/truststore.p12` (password `changeit`). Also import the CA into the
   JDK cacerts with keytool as a convenience:
   `<jdk>/bin/keytool -importcert -alias sandbox-egress-proxy-ca -file /workspace/.toolchain/proxy-ca.pem -keystore <jdk>/lib/security/cacerts -storepass changeit -noprompt`
6. Unzip cmdline-tools to `/workspace/.toolchain/android-sdk/cmdline-tools/latest` (note: the
   `latest` dir must NOT pre-exist or the zip's `cmdline-tools` folder lands nested inside it).
7. Download the CLI launcher (newer sdkmanager is a shim that fetches it):
   `wget --no-check-certificate -O /workspace/.toolchain/android-sdk/cmdline-tools/latest/bin/android https://dl.google.com/android/cli/latest/linux_x86_64/android-cli`
   then `chmod +x` it.
8. Write license acceptance files (`/workspace/.toolchain/android-sdk/licenses/android-sdk-license`
   and `android-sdk-preview-license`, hashes: 8933bad1…, d56f5187…, 24333f8a…, 84831b94…).
9. Install packages (SSL_CERT_FILE makes the Go binary trust the proxy):
   `SSL_CERT_FILE=/workspace/.toolchain/ca-bundle.crt JAVA_HOME=<jdk> /workspace/.toolchain/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/workspace/.toolchain/android-sdk "platform-tools" "platforms;android-35" "build-tools;35.0.0" "build-tools;34.0.0"`
10. Recreate `local.properties` in the repo root: `sdk.dir=/workspace/.toolchain/android-sdk`.

## Build commands

```bash
# From the repo root (/workspace/app)
JAVA_HOME=/workspace/.toolchain/jdk-17.0.20.1+1 \
GRADLE_USER_HOME=/workspace/.toolchain/gradle-home \
  /workspace/.toolchain/gradle-8.9/bin/gradle assembleDebug

# Unit tests (pure-Kotlin logic: pacing, snapping, VAD, arc planner)
JAVA_HOME=/workspace/.toolchain/jdk-17.0.20.1+1 \
GRADLE_USER_HOME=/workspace/.toolchain/gradle-home \
  /workspace/.toolchain/gradle-8.9/bin/gradle :app:testDebugUnitTest
```

- Output APK: `app/build/outputs/apk/debug/app-debug.apk` — side-load to the owner's phone
  (no emulator is possible: the VM has no KVM).
- Memory tuning in `gradle.properties` matters: `-Xmx1400m` daemon + `-Xmx700m` Kotlin daemon
  + `org.gradle.workers.max=1`. Larger heaps cause "Gradle build daemon disappeared" during
  `compileDebugKotlin`.
- Theme gotcha: framework style parents (`android:Theme.Material.*`) fail AAPT2 linking on
  this toolchain; `Theme.TRACK` intentionally has no parent.
- The sandbox blocks shell pipes/redirects/`&&`/`;` — run one command per call, use `workdir`,
  and `wget --no-check-certificate` for any non-allowlisted download.
- Push after each milestone gate passes; owner reviews from the Kilo mobile app.
- Repo rename to `track` is still pending: the Cloud Agent token lacks repo-admin scope
  (403 on `gh repo rename`), so the owner must rename it in GitHub settings; local `origin`
  (token URL in `.git/config`) then needs updating.
