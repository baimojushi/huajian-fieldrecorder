package com.huajian.fieldrecorder.speaker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Safely aligns external/local open-source diarization spans with Qwen VAD spans.
 * A diarization file NEVER authorizes child/adult identity claims. 
 */
public final class SpeakerFusion {
    private SpeakerFusion() { }

    /** Expected diarization JSON: {schema,engine,segments:[{start_ms,end_ms,speaker_id,confidence?}]}. */
    public static JSONObject merge(JSONObject asr, JSONObject diarization) throws Exception {
        if (!"completed".equals(asr.optString("status")))
            throw new IllegalArgumentException("ASR 未完成，不能关联说话人");
        JSONArray voices = diarization.optJSONArray("segments");
        if (voices == null) throw new IllegalArgumentException("声纹文件缺少 segments 数组");
        JSONArray words = asr.getJSONArray("segments");
        JSONArray rows = new JSONArray();
        for (int i = 0; i < words.length(); i++) {
            JSONObject transcript = new JSONObject(words.getJSONObject(i).toString());
            long begin = transcript.optLong("start_ms", -1);
            long end = transcript.optLong("end_ms", -1);
            List<SpeakerOverlap.Span> intervals = new ArrayList<>();
            if (begin >= 0 && end > begin) {
                for (int k = 0; k < voices.length(); k++) {
                    JSONObject speaker = voices.optJSONObject(k);
                    if (speaker == null) continue;
                    long voiceStart = speaker.optLong("start_ms", -1);
                    long voiceEnd = speaker.optLong("end_ms", -1);
                    String voiceId = speaker.optString("speaker_id", "unknown");
                    if (voiceStart < 0 || voiceEnd <= voiceStart ||
                            voiceEnd - voiceStart > 24L * 60 * 60 * 1000 ||
                            !voiceId.matches("S[1-9][0-9]{0,3}")) continue;
                    intervals.add(new SpeakerOverlap.Span(voiceStart, voiceEnd, voiceId));
                }
            }
            String candidate = SpeakerOverlap.assign(begin, end, intervals);
            boolean confidentMatch = !"unknown".equals(candidate);
            transcript.put("speaker_id", confidentMatch ? candidate : "unknown");
            transcript.put("speaker_assignment", confidentMatch ? "dominant_time_overlap_candidate" : "ambiguous_or_unavailable");
            transcript.put("speaker_identity_human_verified", false);
            transcript.put("candidate_requires_listening", true);
            rows.put(transcript);
        }
        JSONObject merged = new JSONObject();
        merged.put("schema", "huajian.fused_speech.v1.3");
        merged.put("source_asr_model", asr.optString("model"));
        merged.put("diarization_engine", diarization.optString("engine", "unspecified"));
        merged.put("diarization_source", "local_or_imported_json_not_cloud_asr");
        merged.put("speaker_id_is_not_child_adult_role", true);
        merged.put("segments", rows);
        return merged;
    }
}
