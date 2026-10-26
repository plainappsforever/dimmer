#!/usr/bin/env bash
# Release build for Google Play (launch step 2). Local and free; uploads nothing, signs nothing new.
# Usage: scripts/build-release.sh
#   - ./gradlew bundleRelease assembleRelease (R8 minify + resource shrinking on; signing from the owner's upload-key
#     properties file OUTSIDE the repo, see app/build.gradle.kts; missing file -> debug key + warning)
#   - copies dist/dimmer-<version>.aab (for Play) and dist/dimmer-<version>-release.apk (for emulator tests)
#   - prints the signer (must be "CN=Dimmer release key, O=Plain Apps" since 3.1.3, D-RB-1; never "CN=Android Debug"), the 16 KB alignment result
#     of every native library, and the permissions of the release APK (compare with docs/RELEASE_KIT.md section 6)
# Never prints a password. Needs JAVA_HOME (OpenJDK 17) and ANDROID_HOME as in CLAUDE.md.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
bt="$ANDROID_HOME/build-tools/36.0.0"

./gradlew bundleRelease assembleRelease --console=plain 2>&1 | grep -E "WARNING: upload|BUILD|FAILURE|error:|What went wrong" || true
version=$(grep -m1 'versionName = ' app/build.gradle.kts | sed 's/.*"\(.*\)".*/\1/')
aab=app/build/outputs/bundle/release/app-release.aab
apk=app/build/outputs/apk/release/app-release.apk
[[ -f $aab && -f $apk ]] || { echo "release outputs missing"; exit 1; }
mkdir -p dist
cp "$aab" "dist/dimmer-$version.aab"
cp "$apk" "dist/dimmer-$version-release.apk"
echo "bundle: dist/dimmer-$version.aab ($(stat -f %z "dist/dimmer-$version.aab") bytes)"
echo "apk:    dist/dimmer-$version-release.apk ($(stat -f %z "dist/dimmer-$version-release.apk") bytes)"

echo "--- signer (APK, apksigner)"
"$bt/apksigner" verify --print-certs -v "dist/dimmer-$version-release.apk" | grep -E "^Verifies|v2 scheme|v3 scheme|certificate DN|certificate SHA-256"
echo "--- signer (AAB, jarsigner)"
keytool -printcert -jarfile "dist/dimmer-$version.aab" | grep -E "Owner:|SHA256:"
if keytool -printcert -jarfile "dist/dimmer-$version.aab" | grep -q "CN=Android Debug"; then
  echo "WARNING: DEBUG-signed bundle - Google Play will refuse it"
fi

echo "--- 16 KB page size: zip alignment of native libraries"
"$bt/zipalign" -c -P 16 -v 4 "dist/dimmer-$version-release.apk" | grep -E "\.so|Verification"
echo "--- 16 KB page size: ELF LOAD segment alignment (must be 2**14 or more)"
scratch=app/build/elf-check.so   # overwritten per library; inside the git-ignored build folder
for so in $(unzip -Z1 "dist/dimmer-$version-release.apk" | grep '\.so$'); do
  unzip -p "dist/dimmer-$version-release.apk" "$so" >"$scratch"
  echo "  $so $(objdump -p "$scratch" | awk '/LOAD/{print $NF}' | sort -u | tr '\n' ' ')"
done

echo "--- permissions in the release APK"
"$bt/aapt2" dump permissions "dist/dimmer-$version-release.apk"
