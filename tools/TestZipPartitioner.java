import com.huajian.fieldrecorder.core.ZipPartitioner;
import java.util.ArrayList;
import java.util.List;

public class TestZipPartitioner {
    public static void main(String[] args) {
        List<ZipPartitioner.Item> assets = new ArrayList<>();
        for (int i = 0; i < 110; i++) assets.add(new ZipPartitioner.Item("MEDIA/frames/frame_" + i, 12_000_000L, i * 3000));
        List<List<ZipPartitioner.Item>> groups = ZipPartitioner.plan(assets);
        check(groups.size() > 1, "no split");
        int visited = 0;
        for (List<ZipPartitioner.Item> g : groups) {
            long estimated = ZipPartitioner.RESERVED_PER_PART_BYTES;
            for (ZipPartitioner.Item f : g) { estimated += f.bytes + 512 + f.path.length() * 4; visited++; }
            check(estimated < ZipPartitioner.TARGET_LIMIT_BYTES, "target exceeded");
            check(estimated < ZipPartitioner.ABSOLUTE_LIMIT_BYTES, "hard limit exceeded");
        }
        check(visited == assets.size(), "asset lost");
        check(ZipPartitioner.plan(new ArrayList<>()).size() == 1, "empty case");
        try {
            ZipPartitioner.plan(java.util.Collections.singletonList(new ZipPartitioner.Item("huge", 381_000_000L, 0)));
            throw new AssertionError("too-large media accepted");
        } catch (IllegalArgumentException expected) { /* pass */ }
        System.out.println("PASS Java partitioner: " + visited + " entries, " + groups.size() + " parts, <380MB planned");
    }
    private static void check(boolean cond, String msg) { if (!cond) throw new AssertionError(msg); }
}
