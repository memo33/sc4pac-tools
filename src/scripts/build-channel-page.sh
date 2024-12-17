#!/bin/sh
# Build channel page for inclusion in website. Usage: ./src/scripts/build-channel-page.sh
set -e
rm -rf web/target/website
mkdir -p web/target/website/channel
sbt web/fullLinkJS
cp -p web/target/scala-3.4.2/sc4pac-web-opt/main.js \
    web/channel/styles.css \
    web/channel/index.html \
    ./web/target/website/channel/
