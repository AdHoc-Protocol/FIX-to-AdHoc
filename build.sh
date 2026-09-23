#!/usr/bin/env bash
# Compiles the converter and converts every schema in samples/ into AdHoc/.
set -e
cd "$(dirname "$0")"
rm -rf out
javac -encoding UTF-8 --release 17 -d out src/org/unirail/adhoc/*.java src/org/unirail/*.java
java -Dfile.encoding=UTF-8 -cp out org.unirail.FIX2AdHoc "${1:-samples}" "${2:-AdHoc}"
