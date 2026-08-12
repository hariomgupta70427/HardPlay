#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# HardPlay — TDLib for Android build driver
#
# Produces libtdjni.so (+ org.drinkless.tdlib Java bindings) and installs them
# into the app module. Run inside a Linux environment (WSL2 Ubuntu is fine).
#
#   bash tools/build-tdlib.sh
#
# Deviations from TDLib's stock example/android scripts, and why:
#   * NDK 27.1 instead of 23.2  -> 16 KB page-size alignment, required by
#     Android 15+ devices. Stock NDK 23 output fails to load on those.
#   * OpenSSL 3.5.x instead of OpenSSL_1_1_1w -> 1.1.1 went EOL in Sep 2023 and
#     carries unpatched CVEs. Costs a little binary size; worth it for a
#     library that terminates our transport crypto.
#   * ABI list trimmed to arm64-v8a, armeabi-v7a, x86_64 (drops dead 32-bit
#     x86) -> roughly a quarter less build time, keeps emulator support.
#
# Expect 60-150 min cold. Everything is cached, so re-runs are far cheaper.
# ---------------------------------------------------------------------------
set -euo pipefail

WORKDIR="${TDLIB_WORKDIR:-/build}"
NDK_VERSION="${TDLIB_NDK_VERSION:-27.1.12297006}"
OPENSSL_VERSION="${TDLIB_OPENSSL_VERSION:-openssl-3.5.7}"
OPENSSL_FALLBACK="OpenSSL_1_1_1w"
ABIS="${TDLIB_ABIS:-arm64-v8a armeabi-v7a x86_64}"
# Where to drop the finished artifacts. Override when running outside WSL.
DEST_JNI="${HARDPLAY_JNI_DIR:-/mnt/d/Work/Github Repo/HardPlay/app/src/main/jniLibs}"
DEST_JAVA="${HARDPLAY_JAVA_DIR:-/mnt/d/Work/Github Repo/HardPlay/app/src/main/java}"

say() { printf '\n=== [%s] %s\n' "$(date -u +%H:%M:%S)" "$*"; }
die() { printf '\nBUILD-FAILED: %s\n' "$*" >&2; exit 1; }

say "TDLib build starting — ndk=$NDK_VERSION openssl=$OPENSSL_VERSION abis=$ABIS"

mkdir -p "$WORKDIR"
cd "$WORKDIR"

# --- 1. sources ------------------------------------------------------------
if [ ! -d td/.git ]; then
  say "Cloning tdlib/td"
  git clone -q --depth 1 https://github.com/tdlib/td.git
fi
cd td
TD_COMMIT="$(git rev-parse --short HEAD)"
TD_VERSION="$(sed -n 's/.*project(TDLib VERSION \([0-9.]*\).*/\1/p' CMakeLists.txt | head -1)"
say "TDLib $TD_VERSION @ $TD_COMMIT"
cd example/android

# Scripts ship with CRLF hazards when the tree is touched from Windows.
sed -i 's/\r$//' ./*.sh

# --- 2. trim the hardcoded ABI loops --------------------------------------
# Both scripts hardcode: for ABI in arm64-v8a armeabi-v7a x86_64 x86 ; do
for f in build-openssl.sh build-tdlib.sh; do
  if grep -q 'for ABI in arm64-v8a armeabi-v7a x86_64 x86 ;' "$f"; then
    sed -i "s/for ABI in arm64-v8a armeabi-v7a x86_64 x86 ;/for ABI in $ABIS ;/" "$f"
    say "Patched ABI loop in $f -> $ABIS"
  fi
done

# --- 3. environment check -------------------------------------------------
say "Checking build environment"
bash ./check-environment.sh || die "check-environment.sh reported missing tools"

# --- 4. Android SDK + NDK (script-local, does not touch the Windows SDK) ---
if [ ! -d "SDK/ndk/$NDK_VERSION" ]; then
  say "Fetching Android SDK + NDK $NDK_VERSION (~2-3 GB, several minutes)"
  rm -rf SDK
  bash ./fetch-sdk.sh SDK "$NDK_VERSION" || die "fetch-sdk.sh failed"
else
  say "SDK/NDK already present — skipping fetch"
fi

# --- 5. OpenSSL ------------------------------------------------------------
# build-openssl.sh refuses to run if the install dir exists, so gate on a
# sentinel: the first ABI's libcrypto.a.
FIRST_ABI="${ABIS%% *}"
if [ ! -f "third-party/openssl/$FIRST_ABI/lib/libcrypto.a" ]; then
  say "Building OpenSSL $OPENSSL_VERSION for: $ABIS"
  rm -rf third-party/openssl
  if ! bash ./build-openssl.sh SDK "$NDK_VERSION" third-party/openssl "$OPENSSL_VERSION"; then
    say "OpenSSL $OPENSSL_VERSION failed — retrying with $OPENSSL_FALLBACK"
    rm -rf third-party/openssl
    bash ./build-openssl.sh SDK "$NDK_VERSION" third-party/openssl "$OPENSSL_FALLBACK" \
      || die "OpenSSL build failed on both $OPENSSL_VERSION and $OPENSSL_FALLBACK"
    OPENSSL_VERSION="$OPENSSL_FALLBACK"
  fi
else
  say "OpenSSL already built — skipping"
fi

# --- 6. TDLib ------------------------------------------------------------
say "Building TDLib (Java interface, c++_static). This is the long pole."
bash ./build-tdlib.sh SDK "$NDK_VERSION" third-party/openssl c++_static Java \
  || die "build-tdlib.sh failed"

# --- 7. verify -----------------------------------------------------------
say "Verifying artifacts"
[ -d tdlib/libs ] || die "tdlib/libs missing"
[ -f tdlib/java/org/drinkless/tdlib/TdApi.java ] || die "TdApi.java missing"
[ -f tdlib/java/org/drinkless/tdlib/Client.java ] || die "Client.java missing"

READELF="SDK/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
for ABI in $ABIS; do
  SO="tdlib/libs/$ABI/libtdjni.so"
  [ -f "$SO" ] || die "missing $SO"
  printf '  %-13s %8s KB' "$ABI" "$(( $(stat -c%s "$SO") / 1024 ))"
  if [ "$ABI" = "arm64-v8a" ] && [ -x "$READELF" ]; then
    # 16 KB page alignment shows up as Align 0x4000 on LOAD segments.
    ALIGN="$($READELF --program-headers "$SO" | awk '/LOAD/{print $NF; exit}')"
    printf '  LOAD align=%s' "$ALIGN"
    [ "$ALIGN" = "0x4000" ] && printf ' (16KB OK)' || printf ' (WARNING: not 16KB)'
  fi
  printf '\n'
done

# --- 8. install into the app module ---------------------------------------
say "Installing into app module"
mkdir -p "$DEST_JNI" "$DEST_JAVA/org/drinkless/tdlib"
for ABI in $ABIS; do
  mkdir -p "$DEST_JNI/$ABI"
  cp -f "tdlib/libs/$ABI/libtdjni.so" "$DEST_JNI/$ABI/"
  # Only present when OpenSSL was built shared; harmless when absent.
  for extra in libcrypto.so libssl.so libc++_shared.so; do
    [ -f "tdlib/libs/$ABI/$extra" ] && cp -f "tdlib/libs/$ABI/$extra" "$DEST_JNI/$ABI/"
  done
done
cp -f tdlib/java/org/drinkless/tdlib/TdApi.java  "$DEST_JAVA/org/drinkless/tdlib/"
cp -f tdlib/java/org/drinkless/tdlib/Client.java "$DEST_JAVA/org/drinkless/tdlib/"

cat > "$DEST_JNI/../tdlib-build-info.txt" <<EOF
TDLib      $TD_VERSION ($TD_COMMIT)
OpenSSL    $OPENSSL_VERSION
NDK        $NDK_VERSION
STL        c++_static
Interface  Java (org.drinkless.tdlib)
ABIs       $ABIS
Built      $(date -u +"%Y-%m-%dT%H:%M:%SZ") UTC
EOF

say "BUILD-SUCCESS — TDLib $TD_VERSION installed into app module"
