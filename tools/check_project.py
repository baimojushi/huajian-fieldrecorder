#!/usr/bin/env python3
"""Non-Android checks; only compile/real-device tests can validate Android runtime behavior."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
checks = 0
for xml in (root / 'app/src/main/res').rglob('*.xml'):
    ET.parse(xml)
    checks += 1
ET.parse(root / 'app/src/main/AndroidManifest.xml')
checks += 1
critical = [
    'app/src/main/assets/GPT6_PROTOCOL.md',
    'app/src/main/assets/viewer.html',
    'app/src/main/java/com/huajian/fieldrecorder/MainActivity.java',
    'app/src/main/java/com/huajian/fieldrecorder/SessionStore.java',
    'app/src/main/java/com/huajian/fieldrecorder/export/AudioSegmenter.java',
    'app/src/main/java/com/huajian/fieldrecorder/export/FrameSampler.java',
    'app/src/main/java/com/huajian/fieldrecorder/export/PacketExporter.java',
    '.github/workflows/android-debug.yml',
]
for file in critical:
    assert (root / file).is_file(), f'missing {file}'
    checks += 1
text = (root / critical[6]).read_text()
for token in ['400_000_000L', 'ZipPartitioner.ABSOLUTE_LIMIT_BYTES', 'SHA-256', 'part_manifest.json', 'events_all.json', 'VIEWER/index.html']:
    # Exact 400_000_000L is defined in ZipPartitioner.java, not PacketExporter.java.
    if token == '400_000_000L':
        assert token in (root / 'app/src/main/java/com/huajian/fieldrecorder/core/ZipPartitioner.java').read_text()
    else:
        assert token in text, token
    checks += 1
# Android resource references should resolve in the project's own resource folders.
java = '\n'.join(path.read_text() for path in (root / 'app/src/main/java').rglob('*.java'))
xml_text = '\n'.join(path.read_text() for path in (root / 'app/src/main/res').rglob('*.xml'))
ids = set(re.findall(r'android:id="@\+id/([^" ]+)', xml_text))
for name in set(re.findall(r'(?<!android\.)R\.id\.([A-Za-z0-9_]+)', java)):
    assert name in ids, f'unknown Android id: {name}'
    checks += 1
colors = set(re.findall(r'<color name="([^"]+)', (root / 'app/src/main/res/values/colors.xml').read_text()))
for name in set(re.findall(r'R\.color\.([A-Za-z0-9_]+)', java)):
    assert name in colors, f'unknown color: {name}'
    checks += 1
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()
assert 'android.permission.INTERNET' in manifest, 'DashScope requires INTERNET permission'
assert 'android:usesCleartextTraffic="false"' in manifest
assert 'android:allowBackup="false"' in manifest
checks += 2
# Validate v1.3 ASR/emotion, manual secret entry and conservative speaker adapter wiring.
base = root / 'app/src/main/java/com/huajian/fieldrecorder'
websocket = (base / 'asr/Qwen3RealtimeClient.java').read_text()
vault = (base / 'security/ApiKeyVault.java').read_text()
fusion = (base / 'speaker/SpeakerFusion.java').read_text()
ui = (root / 'app/src/main/res/layout/activity_main.xml').read_text()
for token in ('qwen3-asr-flash-realtime', 'session.update', 'input_audio_buffer.append',
              'conversation.item.input_audio_transcription.completed', 'input_audio_buffer.speech_started',
              'input_audio_buffer.speech_stopped', 'session.finish', 'server_vad_tail_silence_padding_ms'):
    assert token in websocket, f'missing ASR token {token}'
    checks += 1
for token in ('AndroidKeyStore', 'AES/GCM/NoPadding', 'GCMParameterSpec', 'SharedPreferences', 'clear('):
    assert token in vault, f'missing secure key setting: {token}'
    checks += 1
for token in ('api_key_input', 'api_region', 'consent_cloud_asr', 'btn_qwen_analyze', 'btn_import_speaker'):
    assert token in ui, f'missing UI: {token}'
    checks += 1
for token in ('dominant_time_overlap_candidate', 'speaker_identity_human_verified', 'S[1-9][0-9]{0,3}'):
    assert token in fusion, f'missing speaker safety marker {token}'
    checks += 1
for token in ('SPEECH/qwen3_asr_emotion.json','SPEECH/speaker_fusion.json','api_credentials_in_packet',
              'huajian.media_timeline.v1.3'):
    assert token in text, f'missing export token {token}'
    checks += 1
for key_re in (r'(?im)^(?:DASHSCOPE_API_KEY|API_KEY|api_key)\s*=\s*sk-',r'Bearer\s+sk-[A-Za-z0-9]{16,}'):
    for path in root.rglob('*.java'):
        assert not re.search(key_re, path.read_text()), f'potential embedded secret in {path}'
checks += 2
print(f'PASS static: {checks} resources/files/constants checked; Android SDK compile NOT performed')
