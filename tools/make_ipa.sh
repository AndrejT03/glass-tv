#!/bin/zsh
# Builds Glass's iPhone app (ios/) for iPhone hardware as ios/build/Glass.ipa: to be signed with one's
# own certificate when installing it (Sideloadly, AltStore, ESign, or Xcode's Devices window after
# signing). The bundle ids are com.andrej.glassremote and, for the quick-control widgets inside it,
# com.andrej.glassremote.widgets; a signing tool may change them to fit the certificate's
# provisioning profile. The app and the widgets share the app group group.com.andrej.glassremote (the
# TV's address and token, and what the widgets show): the IPA is signed ad hoc with that entitlement
# so the signing tool carries it over, and the widgets need a signature that keeps it.
set -euo pipefail
cd "$(dirname "$0")/../ios"
out=build
rm -rf "$out/Payload" "$out/Glass.ipa"
xcodebuild -project GlassRemote.xcodeproj -scheme GlassRemote -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath "$out/dd" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" -quiet build
mkdir -p "$out/Payload"
cp -R "$out/dd/Build/Products/Release-iphoneos/GlassRemote.app" "$out/Payload/"
# Inside out: the widgets first, then the app around them.
codesign -f -s - --entitlements GlassWidgets.entitlements "$out/Payload/GlassRemote.app/PlugIns/GlassWidgets.appex"
codesign -f -s - --entitlements GlassRemote.entitlements "$out/Payload/GlassRemote.app"
(cd "$out" && zip -qry Glass.ipa Payload && rm -rf Payload)
echo "$PWD/$out/Glass.ipa"
