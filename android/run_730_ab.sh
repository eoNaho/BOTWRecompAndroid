#!/bin/bash
# android/run_730_ab.sh [seconds, default 150]: the Adreno 730 options (docs/NOTAS-ADRENO-730.md) one after another on
# $ANDROID_SERIAL through run_cfgs.sh (same scripted run each: the save list's first entry loaded, fps / GPU ms / render
# thread ms / page-ins / PSS for the last 16 s). "base" runs between the others: a phone that heats up drifts more than
# most options change, so the bases show how much of a difference is just heat - compare each option with the base
# beside it, not with the first one. LARECOMP_A730=none matches no option (an empty value would leave ",," in the list).
# Needs a Turnip built for the A7xx loaded in Settings > Files > Vulkan driver. One option per run: run_cfgs.sh
# separates variables with commas, so "inplace,lrz" is "all" here.
# Extra variables for every run: COMMON=LARECOMP_X=1,LARECOMP_Y=2 android/run_730_ab.sh
set -uo pipefail
cd "$(dirname "$0")/.."
SECS=${1:-150}
exec bash android/run_cfgs.sh "$SECS" \
    "base1:LARECOMP_A730=none" \
    "inplace:LARECOMP_A730=inplace" \
    "base2:LARECOMP_A730=none" \
    "lrz:LARECOMP_A730=lrz" \
    "base3:LARECOMP_A730=none" \
    "all:LARECOMP_A730=all" \
    "base4:LARECOMP_A730=none" \
    "renderbig:LARECOMP_A730=none,LARECOMP_RENDER_BIG=2" \
    "base5:LARECOMP_A730=none" \
    "uclamp:LARECOMP_A730=none,LARECOMP_UCLAMP_MIN=512" \
    "base6:LARECOMP_A730=none" \
    "diag:LARECOMP_A730=none,LARECOMP_FRAME_TIMELINE=1,LARECOMP_LOG_GPU_IDLE=1"
