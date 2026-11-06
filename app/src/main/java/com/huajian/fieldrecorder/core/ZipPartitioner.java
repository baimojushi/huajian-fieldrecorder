package com.huajian.fieldrecorder.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure Java, intentionally usable without the Android SDK. */
public final class ZipPartitioner {
    public static final long ABSOLUTE_LIMIT_BYTES = 400_000_000L;
    public static final long TARGET_LIMIT_BYTES = 380_000_000L;
    public static final long RESERVED_PER_PART_BYTES = 4_000_000L;

    public static final class Item {
        public final String path;
        public final long bytes;
        public final long startMs;
        public Item(String path, long bytes, long startMs) {
            if (path == null || path.isEmpty() || bytes < 0) throw new IllegalArgumentException("bad item");
            this.path = path;
            this.bytes = bytes;
            this.startMs = startMs;
        }
    }

    private ZipPartitioner() { }

    /** Assign complete media files to ordered independent ZIPs; never slice compressed audio. */
    public static List<List<Item>> plan(List<Item> ordered) {
        if (ordered == null) throw new IllegalArgumentException("missing items");
        List<List<Item>> parts = new ArrayList<>();
        List<Item> group = new ArrayList<>();
        long current = RESERVED_PER_PART_BYTES;
        for (Item item : ordered) {
            if (item.bytes + RESERVED_PER_PART_BYTES >= TARGET_LIMIT_BYTES) {
                throw new IllegalArgumentException("Single media item too large: " + item.path);
            }
            // Reserve room for ZIP headers, repeated JSON, HTML, protocol, and non-shrinking media.
            long estimate = item.bytes + 512L + item.path.length() * 4L;
            if (!group.isEmpty() && current + estimate >= TARGET_LIMIT_BYTES) {
                parts.add(Collections.unmodifiableList(group));
                group = new ArrayList<>();
                current = RESERVED_PER_PART_BYTES;
            }
            group.add(item);
            current += estimate;
        }
        if (!group.isEmpty() || parts.isEmpty()) parts.add(Collections.unmodifiableList(group));
        return Collections.unmodifiableList(parts);
    }
}
