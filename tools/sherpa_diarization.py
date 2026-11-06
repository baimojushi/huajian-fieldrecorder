#!/usr/bin/env python3
"""Offline speaker diarization using users' own local sherpa-onnx weights.

Example (no cloud requests):
  python3 tools/sherpa_diarization.py \
     --audio recording.wav \
     --seg-model /models/pyannote-3-0/model.onnx \
     --embed-model /models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx \
     --output speakers.json --offset-ms 0

Input audio must be a WAV readable by soundfile. For source MP4 use ffmpeg to create a
local WAV first and use --offset-ms if audio was deliberately cut from the video.
This script is not invoked inside Android; it produces JSON accepted by its import UI.
"""
import argparse
import json
import sys
from pathlib import Path


def cli():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--audio", type=Path, required=True, help="Local WAV, no cloud upload")
    p.add_argument("--seg-model", type=Path, required=True, help="Pyannote segmentation ONNX")
    p.add_argument("--embed-model", type=Path, required=True, help="3D-Speaker embedding ONNX")
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--offset-ms", type=int, default=0, help="Start time of extracted clip on full MP4 timeline")
    p.add_argument("--num-speakers", type=int, default=-1, help="-1 to cluster speakers automatically")
    p.add_argument("--threshold", type=float, default=0.5, help="Clustering threshold when count unknown")
    args = p.parse_args()
    for name in ("audio", "seg_model", "embed_model"):
        if not getattr(args, name).is_file():
            p.error(f"File not found: {getattr(args, name)}")
    if args.offset_ms < 0:
        p.error("--offset-ms must be >= 0")

    try:
        import librosa
        import numpy as np
        import sherpa_onnx
        import soundfile as sf
    except ImportError as ex:
        sys.exit("Install local dependencies first: pip install sherpa-onnx numpy soundfile librosa\n" + str(ex))

    config = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                model=str(args.seg_model), window_shift_ratio=0.1,
            ),
        ),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=str(args.embed_model)),
        clustering=sherpa_onnx.FastClusteringConfig(
            num_clusters=args.num_speakers, threshold=args.threshold,
        ),
        min_duration_on=0.3,
        min_duration_off=0.5,
    )
    if not config.validate():
        sys.exit("Invalid sherpa-onnx configuration or incompatible model weights")
    sd = sherpa_onnx.OfflineSpeakerDiarization(config)
    samples, rate = sf.read(str(args.audio), dtype="float32", always_2d=True)
    # Preserve mono by simple channel average, no personal identity metadata.
    mono = np.asarray(samples.mean(axis=1), dtype=np.float32)
    if rate != sd.sample_rate:
        mono = librosa.resample(mono, orig_sr=rate, target_sr=sd.sample_rate)
        mono = np.asarray(mono, dtype=np.float32)
    results = sd.process(mono).sort_by_start_time()
    segments = []
    for r in results:
        begin = args.offset_ms + round(float(r.start) * 1000)
        end = args.offset_ms + round(float(r.end) * 1000)
        if end <= begin:
            continue
        segments.append({
            "start_ms": begin,
            "end_ms": end,
            "speaker_id": f"S{int(r.speaker) + 1}",
        })
    data = {
        "schema": "huajian.speaker.diarization.v1.3",
        "engine": "sherpa-onnx / pyannote-segmentation + 3D-Speaker",
        "time_basis": "source_MP4_relative_milliseconds",
        "speaker_labels_are_anonymous": True,
        "audio_input_filename": args.audio.name,
        "offset_ms": args.offset_ms,
        "segments": segments,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Saved {len(segments)} diarization segments to {args.output}")
    print("Do not interpret S1/S2 as child/adult; listen before assigning identities.")


if __name__ == "__main__":
    cli()
