import com.suancaiyu.mosilalight.Protocol;
import java.util.Arrays;

public final class ProtocolTest {
    static int count;
    static void expect(byte[] actual, String expected) {
        if (!Protocol.hex(actual).equals(expected)) throw new AssertionError(Protocol.hex(actual) + " != " + expected);
        if (actual[0] == 0x69 && (actual[2] & 255) != actual.length - 3) throw new AssertionError("Length mismatch");
        count++;
    }
    static void invalid(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { count++; return; }
        throw new AssertionError("Invalid parameter accepted");
    }
    public static void main(String[] args) {
        expect(Protocol.rgb(0xffff0000,0),"69 96 05 02 FF 00 00 00");
        expect(Protocol.rgb(0xff00ff00,0),"69 96 05 02 00 FF 00 00");
        expect(Protocol.rgb(0xff0000ff,255),"69 96 05 02 00 00 FF FF");
        expect(Protocol.rgb(0xffff0000,Protocol.DEFAULT_COLOR_INTENSITY),"69 96 05 02 FF 00 00 FF");
        expect(Protocol.power(false,0xff000000,0),"69 96 06 01 00 00 00 00 00");
        expect(Protocol.power(true,0xff123456,17),"69 96 06 01 01 12 34 56 11");
        expect(Protocol.mode(0,5,true,true),"69 96 06 03 00 00 06 01 01");
        expect(Protocol.mode(212,10,false,true),"69 96 06 03 00 D4 01 00 01");
        expect(Protocol.startup(0,50,0xff6400ff),"69 96 07 04 00 32 64 00 FF FF");
        expect(Protocol.frame(5,1,0),"69 96 03 05 01 00");
        expect(Protocol.frame(6,0,1),"69 96 03 06 00 01");
        expect(Protocol.frame(7,1),"69 96 02 07 01");
        expect(Protocol.frame(8,0),"69 96 02 08 00");
        expect(Protocol.frame(9,0,1),"69 96 03 09 00 01");
        expect(Protocol.frame(10,18),"69 96 02 0A 12");
        expect(Protocol.lineOrder(1),"91 01");
        expect(Protocol.lineOrder(6),"91 06");
        invalid(()->Protocol.frame(256)); invalid(()->Protocol.frame(1,-1));
        invalid(()->Protocol.mode(213,5,true,true)); invalid(()->Protocol.mode(0,0,true,true));
        invalid(()->Protocol.startup(0,101,0)); invalid(()->Protocol.lineOrder(0));
        System.out.println("PASS: " + count + " protocol vectors / boundary checks");
    }
}
