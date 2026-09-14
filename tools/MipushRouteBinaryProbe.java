import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Bounded, read-only diagnostic for a Thrift binary struct, not a production MiPush decoder.
 * https://github.com/apache/thrift/blob/master/doc/specs/thrift-binary-protocol.md
 * Only exact intent_uri map entries are decoded; opaque binary fields are never searched.
 * No input data is printed or persisted. main runs synthetic self-tests only.
 */
public final class MipushRouteBinaryProbe {
    private static final byte[] KEY = "intent_uri".getBytes(StandardCharsets.UTF_8);
    private final ByteBuffer input;
    private int remainingNodes = 4096;
    private final List<Candidate> candidates = new ArrayList<>();

    public static final class Candidate {
        public final String path;
        public final String route;
        Candidate(String path, String route) { this.path = path; this.route = route; }
    }

    private MipushRouteBinaryProbe(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > 1048576) throw invalid();
        input = ByteBuffer.wrap(bytes);
    }

    public static List<Candidate> parse(byte[] bytes) {
        MipushRouteBinaryProbe parser = new MipushRouteBinaryProbe(bytes);
        parser.value(12, 0, "root");
        if (parser.input.hasRemaining()) throw invalid();
        return parser.candidates;
    }

    private static IllegalArgumentException invalid() {
        // Never include input bytes, keys, routes or identifiers in exception text.
        return new IllegalArgumentException("Invalid or out-of-bounds binary struct");
    }

    private static void validType(int type) {
        if (type != 2 && type != 3 && type != 4 && type != 6 && type != 8 && type != 10
                && type != 11 && type != 12 && type != 13 && type != 14 && type != 15 && type != 16) throw invalid();
    }

    private void skip(int count) {
        if (count < 0 || count > input.remaining()) throw invalid();
        input.position(input.position() + count);
    }

    private int size(int max) {
        int count = input.getInt();
        if (count < 0 || count > max) throw invalid();
        return count;
    }

    private boolean matchingKey() {
        int length = size(input.remaining());
        if (length > input.remaining()) throw invalid();
        boolean matches = length == KEY.length;
        int start = input.position();
        if (matches) for (int i = 0; i < length; i++) matches &= input.get(start + i) == KEY[i];
        skip(length);
        return matches;
    }

    private String routeString() {
        int length = size(Math.min(input.remaining(), 65536));
        if (length > input.remaining()) throw invalid();
        ByteBuffer value = input.slice();
        value.limit(length);
        skip(length);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(value).toString();
        } catch (java.nio.charset.CharacterCodingException failure) { throw invalid(); }
    }

    private void value(int type, int depth, String path) {
        validType(type);
        if (depth > 24 || --remainingNodes < 0) throw invalid();
        switch (type) {
            case 2: int bool = input.get(); if (bool != 0 && bool != 1) throw invalid(); break;
            case 3: skip(1); break;
            case 4: case 10: skip(8); break;
            case 6: skip(2); break;
            case 8: skip(4); break;
            case 16: skip(16); break;
            case 11: skip(size(input.remaining())); break;
            case 12:
                while (true) {
                    int fieldType = input.get() & 255;
                    if (fieldType == 0) break;
                    int fieldId = input.getShort();
                    value(fieldType, depth + 1, path + "/" + fieldId);
                }
                break;
            case 13:
                int keyType = input.get() & 255;
                int valueType = input.get() & 255;
                validType(keyType); validType(valueType);
                int mapSize = size(4096);
                boolean seenRoute = false;
                for (int i = 0; i < mapSize; i++) {
                    if (keyType == 11 && valueType == 11) {
                        if ((remainingNodes -= 2) < 0) throw invalid();
                        if (matchingKey()) {
                            if (seenRoute || candidates.size() >= 16) throw invalid();
                            seenRoute = true;
                            candidates.add(new Candidate(path, routeString()));
                        } else skip(size(input.remaining()));
                    } else {
                        value(keyType, depth + 1, path + "/key");
                        value(valueType, depth + 1, path + "/value");
                    }
                }
                break;
            case 14: case 15:
                int element = input.get() & 255;
                validType(element);
                int length = size(4096);
                for (int i = 0; i < length; i++) value(element, depth + 1, path + "/element");
                break;
            default: throw invalid();
        }
    }

    private static void string(DataOutputStream out, String value) throws java.io.IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }

    private static byte[] fixture(boolean duplicate, boolean invalidUtf8) throws java.io.IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        // A misleading URI in an opaque binary field must not be treated as a map entry.
        out.writeByte(11); out.writeShort(1); string(out, "intent_uri bilibili://video/999");
        out.writeByte(12); out.writeShort(8);
        out.writeByte(13); out.writeShort(10);
        out.writeByte(11); out.writeByte(11); out.writeInt(duplicate ? 3 : 2);
        string(out, "unrelated"); string(out, "bilibili://video/999");
        string(out, "intent_uri");
        if (invalidUtf8) { out.writeInt(2); out.writeByte(0xc3); out.writeByte(0x28); }
        else string(out, "bilibili://video/123");
        if (duplicate) { string(out, "intent_uri"); string(out, "bilibili://video/456"); }
        out.writeByte(0); out.writeByte(0);
        return bytes.toByteArray();
    }

    private static void rejects(byte[] bytes) {
        try { parse(bytes); } catch (IllegalArgumentException | java.nio.BufferUnderflowException expected) { return; }
        throw new AssertionError("Malformed synthetic fixture accepted");
    }

    public static void main(String[] args) throws Exception {
        byte[] good = fixture(false, false);
        List<Candidate> result = parse(good);
        if (result.size() != 1 || !result.get(0).path.equals("root/8/10")
                || !result.get(0).route.equals("bilibili://video/123")) throw new AssertionError("Exact map extraction failed");
        for (int i = 0; i < good.length; i++) rejects(Arrays.copyOf(good, i));
        rejects(Arrays.copyOf(good, good.length + 1));
        rejects(fixture(true, false));
        rejects(fixture(false, true));
        rejects(new byte[]{11, 0, 1, -1, -1, -1, -1, 0});
        rejects(new byte[]{13, 0, 1, 11, 11, 127, -1, -1, -1, 0});
        rejects(new byte[]{1, 0, 1, 0});
        rejects(new byte[]{15, 0, 1, 0, 0, 0, 0, 0, 0});
        rejects(new byte[1048577]);
        byte[] nested = new byte[4 * 30 + 1];
        for (int i = 0; i < 30; i++) { nested[i * 3] = 12; nested[i * 3 + 2] = 1; }
        rejects(nested);
        if (!parse(new byte[]{0}).isEmpty()) throw new AssertionError("Empty struct");
        System.out.println("binaryParserSelfTest=PASS");
        System.out.println("truncationCases=" + good.length);
        System.out.println("realInputs=0");
    }
}
