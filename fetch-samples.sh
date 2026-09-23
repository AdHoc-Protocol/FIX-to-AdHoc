#!/usr/bin/env bash
# Downloads real SBE schemas and QuickFIX data dictionaries into samples/.
set -e
cd "$(dirname "$0")"
mkdir -p samples
cd samples

SBE=https://raw.githubusercontent.com/aeron-io/simple-binary-encoding/master
for p in \
    sbe-samples/src/main/resources/example-schema.xml \
    sbe-samples/src/main/resources/example-extension-schema.xml \
    sbe-samples/src/main/resources/common-types.xml \
    sbe-benchmarks/src/main/resources/fix-message-samples.xml \
    sbe-benchmarks/src/main/resources/car.xml \
    sbe-tool/src/test/resources/basic-types-schema.xml \
    sbe-tool/src/test/resources/composite-elements-schema.xml \
    sbe-tool/src/test/resources/group-with-data-schema.xml \
    sbe-tool/src/test/resources/FixBinary.xml \
    ; do
    curl -sSf -o "$(basename "$p")" "$SBE/$p"
done

QF=https://raw.githubusercontent.com/quickfix/quickfix/master/spec
for f in FIX42.xml FIX44.xml FIX50SP2.xml FIXT11.xml; do
    curl -sSf -o "$f" "$QF/$f"
done
ls -la
