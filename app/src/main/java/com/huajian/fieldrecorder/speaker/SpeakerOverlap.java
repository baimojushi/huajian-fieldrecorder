package com.huajian.fieldrecorder.speaker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pure Java and testable: conservative speaker assignment by interval overlap. */
public final class SpeakerOverlap {
    public static final class Span {
        public final long startMs, endMs;
        public final String id;
        public Span(long startMs, long endMs, String id) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.id = id;
        }
    }
    private SpeakerOverlap() { }
    public static String assign(long begin, long end, List<Span> diarized) {
        if (begin < 0 || end <= begin) return "unknown";
        Map<String, Long> amounts = new HashMap<>();
        for (Span s : diarized) {
            if (s.startMs < 0 || s.endMs <= s.startMs || s.id == null || !s.id.matches("S[0-9]{1,4}")) continue;
            long intersection = Math.max(0, Math.min(end, s.endMs) - Math.max(begin, s.startMs));
            if (intersection > 0) amounts.merge(s.id, intersection, Long::sum);
        }
        String bestId = "unknown";
        long best = 0, second = 0;
        for (Map.Entry<String, Long> e : amounts.entrySet()) {
            long n = e.getValue();
            if (n > best) { second = best; best = n; bestId = e.getKey(); }
            else if (n > second) second = n;
        }
        long duration = end - begin;
        // Do NOT assign when voice-activity coverage is weak or overlapping voices are substantial.
        return best * 100 >= duration * 65 && second * 100 < duration * 20 ? bestId : "unknown";
    }
}
