#!/bin/bash
OUTPUT_DIR="$1"
echo "수집 시작" > "$OUTPUT_DIR/collect_result.txt"
echo "서버1: 정상" >> "$OUTPUT_DIR/collect_result.txt"
echo "서버2: 정상" >> "$OUTPUT_DIR/collect_result.txt"
exit 0
