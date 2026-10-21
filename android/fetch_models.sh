#!/usr/bin/env bash
# Downloads the offline speech engine (sherpa-onnx) and the built-in voice packs
# into the app before building. Used by GitHub Actions; you can run it locally too.
#   - speech recognition: streaming Zipformer 20M (English), int8
#   - voices: KittenTTS nano v0.8 int8 (8 voices) + English espeak-ng data
set -euo pipefail
cd "$(dirname "$0")"
V=1.13.8
B=https://github.com/k2-fsa/sherpa-onnx/releases/download
A=app/src/main/assets
TMP=$(mktemp -d)
mkdir -p app/libs "$A/asr" "$A/tts"

if [ ! -s app/libs/sherpa-onnx.aar ]; then
  echo "sherpa-onnx $V"
  curl -fsSL -o app/libs/sherpa-onnx.aar "$B/v$V/sherpa-onnx-$V.aar"
fi

if [ ! -s "$A/asr/encoder.int8.onnx" ]; then
  echo "speech recognition model"
  curl -fsSL "$B/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2" | tar xj -C "$TMP"
  d="$TMP/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
  cp "$d/encoder-epoch-99-avg-1.int8.onnx" "$A/asr/encoder.int8.onnx"
  cp "$d/decoder-epoch-99-avg-1.onnx" "$A/asr/decoder.onnx"
  cp "$d/joiner-epoch-99-avg-1.int8.onnx" "$A/asr/joiner.int8.onnx"
  cp "$d/tokens.txt" "$A/asr/tokens.txt"
fi

if [ ! -s "$A/tts/model.int8.onnx" ]; then
  echo "voice pack"
  curl -fsSL "$B/tts-models/kitten-nano-en-v0_8-int8.tar.bz2" | tar xj -C "$TMP"
  d="$TMP/kitten-nano-en-v0_8-int8"
  cp "$d/model.int8.onnx" "$d/voices.bin" "$d/tokens.txt" "$A/tts/"
  # English-only espeak-ng data (the full set is 19 MB)
  rm -rf "$A/tts/espeak-ng-data" && mkdir -p "$A/tts/espeak-ng-data"
  ( cd "$d/espeak-ng-data" && cp -r phontab phonindex phondata phondata-manifest intonations en_dict lang voices "$OLDPWD/$A/tts/espeak-ng-data/" )
fi
rm -rf "$TMP"
du -sh app/libs/sherpa-onnx.aar "$A/asr" "$A/tts"
