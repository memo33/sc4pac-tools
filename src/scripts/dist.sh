#!/bin/sh
# Create a distributable zip file. Usage: sh ./src/scripts/dist.sh
set -e
sbt assembly
mkdir -p target/dist
VERSION=$(sed build.sbt -ne 's/^ThisBuild \/ version := .\(.*\)./\1/p')
OUT="target/dist/sc4pac-${VERSION}.zip"
rm -f "$OUT"
zip --junk-paths "$OUT" target/scala-3.3.0/sc4pac-cli.jar src/scripts/sc4pac src/scripts/sc4pac.bat README.md

# cicdec was manually installed at ./cicdec/ and is bundled with our software
zip -r "$OUT" cicdec
