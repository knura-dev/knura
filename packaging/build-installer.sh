#!/usr/bin/env bash
# Builds Knura's installer for this computer's system with the JDK's jpackage, Java included:
#   packaging/build-installer.sh <version> <deb|rpm|msi|pkg>
# The version may carry a suffix such as 0.6.0-alpha; installers get the numbers only.
# Linux: /opt/knura, a "knura" system service with settings in /var/lib/knura, and knurctl.
# Windows and Mac: a "Knura" app that starts knurd at sign-in and opens the setup page.
set -euo pipefail
cd "$(dirname "$0")/.."
version="$1"
type="$2"
numbers="${version%%-*}"
./gradlew --no-daemon -q :knurd:installDist :knurctl:installDist -Pversion="$version"

work=build/installer
rm -rf "$work"
mkdir -p "$work/input" "$work/out"
cp knurd/build/install/knurd/lib/*.jar "$work/input/"
for jar in knurctl/build/install/knurctl/lib/*.jar; do
  [ -e "$work/input/$(basename "$jar")" ] || cp "$jar" "$work/input/"
done
main_jar="$(cd "$work/input" && ls knurd-*.jar)"

# What knurd and knurctl use from the JDK (jdeps). jlink keeps bin/keytool, which makes the
# admin API's TLS certificate, because the native commands aren't stripped.
common=(
  --app-version "$numbers"
  --vendor "Knura"
  --copyright "Knura contributors, Apache-2.0"
  --description "Knura: NFC tags you can trust that can do anything"
  --input "$work/input"
  --main-jar "$main_jar"
  --dest "$work/out"
  --add-modules java.base,java.logging,java.net.http,java.smartcardio,jdk.httpserver,jdk.crypto.ec
  --jlink-options "--strip-debug --no-header-files --no-man-pages"
  --add-launcher knurctl=packaging/knurctl-launcher.properties
)

case "$type" in
  deb|rpm)
    resources="packaging/linux/$type"
    if [ "$type" = rpm ]; then
      resources="$work/rpm"
      mkdir -p "$resources"
      sed "s/@VERSION@/$numbers/" packaging/linux/rpm/knura.spec.in > "$resources/knura.spec"
    fi
    jpackage "${common[@]}" --type "$type" --name knura --main-class io.knura.knurd.Main \
      --install-dir /opt --linux-package-name knura --linux-app-category net \
      --icon packaging/linux/knura.png \
      --resource-dir "$resources" --app-content packaging/linux/knura.service \
      ${JPACKAGE_TEMP:+--temp "$JPACKAGE_TEMP"}
    ;;
  msi)
    jpackage "${common[@]}" --type msi --name Knura --main-class io.knura.knurd.Desktop \
      --add-launcher knurd=packaging/knurd-launcher.properties \
      --icon packaging/windows/knura.ico --win-menu --win-menu-group Knura --win-shortcut \
      --win-per-user-install --win-upgrade-uuid 6f3b8c1e-2d4a-4b7e-9c55-4b2a7a1d9e10
    ;;
  pkg)
    jpackage "${common[@]}" --type pkg --name Knura --main-class io.knura.knurd.Desktop \
      --add-launcher knurd=packaging/knurd-launcher.properties \
      --icon packaging/mac/knura.icns --mac-package-identifier io.knura.app --mac-package-name Knura
    ;;
  *)
    echo "type must be deb, rpm, msi or pkg" >&2
    exit 2
    ;;
esac
ls -l "$work/out"
