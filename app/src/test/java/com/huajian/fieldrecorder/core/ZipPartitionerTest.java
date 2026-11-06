package com.huajian.fieldrecorder.core;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class ZipPartitionerTest {
    @Test public void splitsBefore400mb() {
        List<ZipPartitioner.Item> files = new ArrayList<>();
        for (int i = 0; i < 100; i++) files.add(new ZipPartitioner.Item("MEDIA/audio/" + i, 11_000_000L, i * 120_000));
        List<List<ZipPartitioner.Item>> packs = ZipPartitioner.plan(files);
        assertTrue(packs.size() > 1);
        int count = 0;
        for (List<ZipPartitioner.Item> p : packs) {
            long size = ZipPartitioner.RESERVED_PER_PART_BYTES;
            for (ZipPartitioner.Item f : p) { count++; size += f.bytes + 512 + f.path.length() * 4L; }
            assertTrue(size < ZipPartitioner.ABSOLUTE_LIMIT_BYTES);
        }
        assertEquals(100, count);
    }
    @Test public void emptyMetadataOnly() { assertEquals(1, ZipPartitioner.plan(Collections.emptyList()).size()); }
    @Test(expected=IllegalArgumentException.class) public void rejectsHugeSingleFile() {
        ZipPartitioner.plan(Collections.singletonList(new ZipPartitioner.Item("big", 390_000_000L, 0)));
    }
}
