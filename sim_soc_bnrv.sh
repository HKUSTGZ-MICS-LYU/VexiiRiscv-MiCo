#!/usr/bin/env bash
# set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 3 ]; then
    echo "Usage: $0 <elf> [bitnet-qtype] [bitnet-version]"
    exit 1
fi

ELF_PATH="$1"
BITNET_QTYPE="${2:-${BITNET_QTYPE:-1b}}"
BITNET_VERSION="${3:-${BITNET_VERSION:-32}}"

sbt "runMain vexiiriscv.soc.mico.MiCoSocSim \
    --load-elf ${ELF_PATH} \
    --with-rvc \
    --with-rvf \
    --with-rvm \
    --decoders 2 \
    --lanes 2 \
    --with-aligner-buffer \
    --with-dispatcher-buffer \
    --with-ras \
    --with-btb \
    --with-gshare \
    --with-late-alu \
    --regfile-async \
    --fetch-l1 \
    --fetch-l1-ways 2 \
    --lsu-l1 \
    --lsu-l1-ways 2 \
    --allow-bypass-from 0 \
    --div-radix 4 \
    --sparse-mem \
    --sparse-mem-lat 4 \
    --bitnet \
    --bitnet-qtype ${BITNET_QTYPE} \
    --bitnet-version ${BITNET_VERSION} "
