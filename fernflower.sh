#!/usr/bin/env sh

here="$(dirname "$(readlink -f "$0")")"
fernflower="${here}/fernflower.jar"

java -jar "$fernflower" -rsy=1 "$1" "$2"