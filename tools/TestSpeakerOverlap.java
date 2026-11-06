import com.huajian.fieldrecorder.speaker.SpeakerOverlap;
import java.util.Arrays;
import java.util.List;

public final class TestSpeakerOverlap {
    private static void eq(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError(expected+" != "+actual);
    }
    private static SpeakerOverlap.Span s(long b, long e, String who) {
        return new SpeakerOverlap.Span(b,e,who);
    }
    public static void main(String[] args) {
        List<SpeakerOverlap.Span> one = Arrays.asList(s(100,950,"S1"));
        eq("S1", SpeakerOverlap.assign(0,1000,one));
        eq("unknown", SpeakerOverlap.assign(0,1000,Arrays.asList(s(500,800,"S1"))));
        eq("unknown", SpeakerOverlap.assign(0,1000,Arrays.asList(s(100,850,"S1"),s(300,700,"S2"))));
        eq("unknown", SpeakerOverlap.assign(-1,1000,one));
        eq("unknown", SpeakerOverlap.assign(1000,1000,one));
        eq("unknown", SpeakerOverlap.assign(0,1000,Arrays.asList(s(0,1000,"C1"))));
        eq("S2", SpeakerOverlap.assign(1000,2000,Arrays.asList(s(1000,2000,"S2"),s(1800,1850,"S1"))));
        System.out.println("PASS SpeakerOverlap: 7 cases, conservative unknown on weak/overlap evidence");
    }
}
