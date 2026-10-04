#!/usr/bin/env bash
# Downloads real SBE schemas and QuickFIX data dictionaries into samples/ and writes samples/sources.txt.
set -e
cd "$(dirname "$0")"
mkdir -p samples
cd samples

SBE=https://raw.githubusercontent.com/aeron-io/simple-binary-encoding/master
SBE_FILES="
    sbe-samples/src/main/resources/example-schema.xml
    sbe-samples/src/main/resources/example-extension-schema.xml
    sbe-samples/src/main/resources/common-types.xml
    sbe-benchmarks/src/main/resources/fix-message-samples.xml
    sbe-benchmarks/src/main/resources/car.xml
    sbe-tool/src/test/resources/basic-types-schema.xml
    sbe-tool/src/test/resources/composite-elements-schema.xml
    sbe-tool/src/test/resources/group-with-data-schema.xml
    sbe-tool/src/test/resources/FixBinary.xml
"
QF=https://raw.githubusercontent.com/quickfix/quickfix/master/spec
QF_FILES="FIX42.xml FIX44.xml FIX50SP2.xml FIXT11.xml"

# The page of a file in its repository, for a reader: raw.githubusercontent.com gives the bare text.
page() {
    case "$1" in
        https://raw.githubusercontent.com/*)
            local p="${1#https://raw.githubusercontent.com/}"
            local owner="${p%%/*}"; p="${p#*/}"
            local repo="${p%%/*}"; p="${p#*/}"
            echo "https://github.com/$owner/$repo/blob/$p" ;;
        *) echo "$1" ;;
    esac
}
{
    echo "# Where every sample comes from: <path in samples/> <page of the original>. Written by fetch-samples.sh;"
    echo "# the converter links these pages in the headers of the descriptions."
    for p in $SBE_FILES; do printf '%-32s %s\n' "$(basename "$p")" "$(page "$SBE/$p")"; done
    for f in $QF_FILES; do printf '%-32s %s\n' "$f" "$(page "$QF/$f")"; done
} > sources.txt

for p in $SBE_FILES; do
    curl -sSf -o "$(basename "$p")" "$SBE/$p"
done
for f in $QF_FILES; do
    curl -sSf -o "$f" "$QF/$f"
done
ls -la
