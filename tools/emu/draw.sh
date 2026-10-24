#!/usr/bin/env bash
# draw.sh x1 y1 x2 y2 ... : one finger stroke through the points (each segment in 6 MOVE steps),
# e.g. a line in a TopoDroid sketch while the pencil tool is selected
source "$(dirname "$0")/env.sh"
px=$1; py=$2; adb shell input motionevent DOWN $px $py; shift 2
while [ $# -ge 2 ]; do
  for s in 1 2 3 4 5 6; do adb shell input motionevent MOVE $(( px + ($1-px)*s/6 )) $(( py + ($2-py)*s/6 )); done
  px=$1; py=$2; shift 2
done
adb shell input motionevent UP $px $py
