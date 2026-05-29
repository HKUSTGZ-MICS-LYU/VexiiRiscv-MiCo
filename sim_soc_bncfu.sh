#!/usr/bin/env bash

if [ "$#" -lt 1 ] || [ "$#" -gt 9 ]; then
    echo "Usage: $0 <elf> [bitnet-qtype] [vlen] [maclen] [reg-depth] [with-q2] [pipe] [quant-width] [with-q8]"
    exit 1
fi

ELF_PATH="$1"
BITNET_QTYPE="${2:-${BITNET_QTYPE:-1.5b}}"
BNCFU_VLEN="${3:-${BNCFU_VLEN:-256}}"
BNCFU_WIDTH="${4:-${BNCFU_WIDTH:-${BNCFU_VLEN}}}"
BNCFU_REG_DEPTH="${5:-${BNCFU_REG_DEPTH:-2}}"
BNCFU_WITH_Q2="${6:-${BNCFU_WITH_Q2:-0}}"
BNCFU_PIPE="${7:-${BNCFU_PIPE:-0}}"
BNCFU_QUANT_WIDTH="${8:-${BNCFU_QUANT_WIDTH:-${BNCFU_Q8_WIDTH:-${BNCFU_Q2T_WIDTH:-$([[ ${BNCFU_VLEN} -lt 128 ]] && echo ${BNCFU_VLEN} || echo 128)}}}}"
BNCFU_WITH_Q8="${9:-${BNCFU_WITH_Q8:-0}}"
BNCFU_WITH_Q2T="${BNCFU_WITH_Q2T:-1}"
BNCFU_Q8_COMPARE_PIPE="${BNCFU_Q8_COMPARE_PIPE:-0}"

BNCFU_Q2_ARG=""
if [ "${BNCFU_WITH_Q2}" = "1" ] || [ "${BNCFU_WITH_Q2}" = "true" ]; then
    BNCFU_Q2_ARG="--bitnet-cfu-with-q2"
fi

BNCFU_PIPE_ARG=""
if [ "${BNCFU_PIPE}" = "1" ] || [ "${BNCFU_PIPE}" = "true" ]; then
    BNCFU_PIPE_ARG="--bitnet-cfu-pipe"
fi

BNCFU_Q2T_ARG="--bitnet-cfu-with-q2t"
if [ "${BNCFU_WITH_Q2T}" = "0" ] || [ "${BNCFU_WITH_Q2T}" = "false" ]; then
    BNCFU_Q2T_ARG="--bitnet-cfu-without-q2t"
fi

BNCFU_Q8_ARG=""
if [ "${BNCFU_WITH_Q8}" = "1" ] || [ "${BNCFU_WITH_Q8}" = "true" ]; then
    BNCFU_Q8_ARG="--bitnet-cfu-with-q8"
fi

BNCFU_Q8_COMPARE_PIPE_ARG=""
if [ "${BNCFU_Q8_COMPARE_PIPE}" = "1" ] || [ "${BNCFU_Q8_COMPARE_PIPE}" = "true" ]; then
    BNCFU_Q8_COMPARE_PIPE_ARG="--bitnet-cfu-q8-compare-pipe"
fi

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
    --mico-bitnet-cfu \
    --bitnet-cfu-qtype ${BITNET_QTYPE} \
    --bitnet-cfu-len ${BNCFU_VLEN} \
    --bitnet-cfu-width ${BNCFU_WIDTH} \
    --bitnet-cfu-quant-width ${BNCFU_QUANT_WIDTH} \
    --bitnet-cfu-reg-depth ${BNCFU_REG_DEPTH} \
    --bitnet-cfu-bus-width 64 \
    ${BNCFU_Q2_ARG} \
    ${BNCFU_Q2T_ARG} \
    ${BNCFU_Q8_ARG} \
    ${BNCFU_Q8_COMPARE_PIPE_ARG} \
    ${BNCFU_PIPE_ARG} "
