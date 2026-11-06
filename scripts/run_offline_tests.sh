#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
python3 tools/test_verify_packets.py
TEMP_CLASSES="$(mktemp -d)"
trap 'rm -rf "$TEMP_CLASSES"' EXIT
javac -encoding UTF-8 -d "$TEMP_CLASSES" \
    app/src/main/java/com/huajian/fieldrecorder/core/ZipPartitioner.java \
    app/src/main/java/com/huajian/fieldrecorder/speaker/SpeakerOverlap.java \
    tools/TestZipPartitioner.java tools/TestSpeakerOverlap.java tools/ParseJavaSources.java
java -cp "$TEMP_CLASSES" TestZipPartitioner
java -cp "$TEMP_CLASSES" TestSpeakerOverlap
# Parse ALL project Java code into javac AST without requiring Android classpath.
find app/src -name '*.java' -print > "$TEMP_CLASSES/sources.txt"
java -cp "$TEMP_CLASSES" ParseJavaSources $(cat "$TEMP_CLASSES/sources.txt")
python3 tools/check_project.py
