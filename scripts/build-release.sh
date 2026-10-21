#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
: "${OMYMONEY_KEYSTORE:?Set the private release keystore path}"
: "${OMYMONEY_KEYSTORE_PASSWORD_FILE:?Set the private password-file path}"
: "${OMYMONEY_KEY_ALIAS:=omymoney}"
: "${ANDROID_BUILD_TOOLS_VERSION:=36.0.0}"
BUILD_TOOLS="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS_VERSION"
ANALYZER="$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"
OUTPUT="${OMYMONEY_OUTPUT_DIR:-$ROOT/outputs}"
mkdir -p "$OUTPUT"
./gradlew --console=plain :app:assembleRelease
"$BUILD_TOOLS/zipalign" -f -P 16 4 app/build/outputs/apk/release/app-release-unsigned.apk "$OUTPUT/OMyMoney-aligned.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$OMYMONEY_KEYSTORE" --ks-key-alias "$OMYMONEY_KEY_ALIAS" \
    --ks-pass "file:$OMYMONEY_KEYSTORE_PASSWORD_FILE" --out "$OUTPUT/OMyMoney.apk" "$OUTPUT/OMyMoney-aligned.apk"
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$OUTPUT/OMyMoney.apk" > "$OUTPUT/signature.txt"
"$BUILD_TOOLS/zipalign" -c -P 16 4 "$OUTPUT/OMyMoney.apk"
"$ANALYZER" manifest print "$OUTPUT/OMyMoney.apk" > "$OUTPUT/release-manifest.xml"
RULES_PATH=$("$ANALYZER" resources value --config default --name data_extraction_rules --type xml "$OUTPUT/OMyMoney.apk")
"$ANALYZER" resources xml --file "$RULES_PATH" "$OUTPUT/OMyMoney.apk" > "$OUTPUT/data-extraction-rules.xml"
RULES_ID=$(ruby -e 'rows=File.readlines(ARGV.fetch(0)).map(&:split); row=rows.find { |r| r[0..2] == %w[int xml data_extraction_rules] }; abort "Missing extraction resource" unless row; puts row.fetch(3)' app/build/intermediates/runtime_symbol_list/release/processReleaseResources/R.txt)
ruby scripts/test-release-verifier.rb
ruby scripts/verify-release.rb "$OUTPUT/release-manifest.xml" "$RULES_ID" "$OUTPUT/data-extraction-rules.xml"
shasum -a 256 "$OUTPUT/OMyMoney.apk" > "$OUTPUT/OMyMoney.apk.sha256"
rm "$OUTPUT/OMyMoney-aligned.apk"
printf '%s\n' "Verified signed APK: $OUTPUT/OMyMoney.apk"
