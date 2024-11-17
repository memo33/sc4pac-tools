#!/bin/sh
# Create a distributable zip file. Usage: sh ./src/scripts/dist.sh
set -e
sbt assembly
mkdir -p target/dist
VERSION=$(sed build.sbt -ne 's/^ThisBuild \/ version := .\(.*\)./\1/p')
OUT="target/dist/sc4pac-${VERSION}.zip"
rm -f "$OUT"
zip --junk-paths "$OUT" target/scala-3.4.2/sc4pac-cli.jar src/scripts/sc4pac src/scripts/sc4pac.bat README.md

# download and install cicdec at ./cicdec/ if not present to bundle it with our software
if [ ! -e "cicdec" ]
then
    curl -L https://github.com/Bioruebe/cicdec/releases/download/3.0.1/cicdec.zip > target/cicdec.zip
    echo "551694690919e668625697be88fedac0b1cfefae321f2440ad48bc65920abe52  target/cicdec.zip" | sha256sum --check
    unzip -d cicdec target/cicdec.zip
fi
zip -r "$OUT" cicdec
