package com.douyindanmaku.core.proto;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个极简的 protobuf 读取器。
 *
 * <h2>为什么不用 protobuf-java？</h2>
 * 我们需要的东西非常少——只是「按字段号把几个字段取出来」而已。
 * 引入 protobuf-java 会多出几百 KB 的依赖，还要打进 Minecraft 里，
 * 而这个文件不到 200 行就够了，而且能一眼看懂。
 *
 * <h2>protobuf 的基础知识（读代码前先看这段）</h2>
 * <pre>
 * 一条消息 = 很多个「字段」，每个字段的编码是：
 *     [key(varint)] [值]
 * 其中 key = (字段号 &lt;&lt; 3) | 线格式(wire type)
 *
 * 线格式（wire type）只有我们要用到的两种：
 *     0 = varint       —— 变长整数，用来存数字/布尔
 *     2 = 长度前缀      —— 先一个 varint 表示字节数，紧跟着那么多字节
 *                          （字符串、bytes、以及嵌套的「子消息」都是这种）
 *
 * 因为 protobuf 不记录字段类型，所以「这个字段到底是数字还是字符串」
 * 只能由调用方按字段号自己知道——这也是为什么这个类只负责
 * 「把字节切成 字段号 -&gt; 值」的映射，类型判断交给上层。
 * </pre>
 *
 * <h2>关于「跳过未知字段」</h2>
 * 抖音会不断往消息里加新字段，我们不可能认识全部字段号。
 * 所以遇到不认识的字段必须能正确跳过——本类通过支持全部 6 种线格式
 * 来保证这一点（其中 3、4 是已废弃的「分组」格式，实际不会出现，
 * 但为了健壮性还是处理掉）。
 */
public final class ProtobufReader {

    /** 线格式：varint。 */
    private static final int WIRE_VARINT = 0;
    /** 线格式：64 位定长。 */
    private static final int WIRE_FIXED64 = 1;
    /** 线格式：长度前缀。 */
    private static final int WIRE_LENGTH_DELIMITED = 2;
    /** 线格式：已废弃的分组开始。 */
    private static final int WIRE_START_GROUP = 3;
    /** 线格式：已废弃的分组结束。 */
    private static final int WIRE_END_GROUP = 4;
    /** 线格式：32 位定长。 */
    private static final int WIRE_FIXED32 = 5;

    /**
     * 长度前缀字段的原始字节。
     *
     * <p>为什么要包一层而不是直接用 {@code byte[]}：protobuf 不记录字段类型，
     * 「长度前缀」既可以装字符串、也可以装嵌套子消息、还可以装二进制。
     * 如果解析时统一返回 {@code byte[]}，那么 {@code getString} 就找不到
     * {@code String} 类型的值（这是个很容易踩的坑：所有字符串字段都变成空的，
     * 而且不报错）。包一层可以让「原始字节」和「已经转好的字符串」
     * 在类型上区分开，{@code getString} 遇到原始字节时再尝试解码。
     */
    public record RawBytes(byte[] value) {
        @Override
        public String toString() {
            return new String(value, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * 一条消息解出来的所有字段。
     *
     * <p>同一个字段号可能出现多次（protobuf 的 {@code repeated} 语义），
     * 所以值统一用列表装。
     */
    public static final class FieldSet {
        private final Map<Integer, List<Object>> fields;

        FieldSet(Map<Integer, List<Object>> fields) {
            this.fields = fields;
        }

        private List<Object> values(int fieldNumber) {
            return fields.get(fieldNumber);
        }

        /** 该字段号是否存在。 */
        public boolean has(int fieldNumber) {
            List<Object> values = values(fieldNumber);
            return values != null && !values.isEmpty();
        }

        /** 取第一个 varint 值；没有则返回 {@code fallback}。 */
        public long getLong(int fieldNumber, long fallback) {
            List<Object> values = values(fieldNumber);
            if (values == null) {
                return fallback;
            }
            for (Object value : values) {
                if (value instanceof Long asLong) {
                    return asLong;
                }
            }
            return fallback;
        }

        /** 取第一个 varint 值并转成 int。 */
        public int getInt(int fieldNumber, int fallback) {
            return (int) getLong(fieldNumber, fallback);
        }

        /** 取第一个字符串；没有则返回 {@code fallback}。 */
        public String getString(int fieldNumber, String fallback) {
            List<Object> values = values(fieldNumber);
            if (values == null) {
                return fallback;
            }
            for (Object value : values) {
                if (value instanceof String asString) {
                    return asString;
                }
                if (value instanceof RawBytes raw) {
                    // 长度前缀字段默认是原始字节，需要时才转字符串
                    return new String(raw.value(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            return fallback;
        }

        /** 取第一个 bytes；没有则返回 {@code fallback}。 */
        public byte[] getBytes(int fieldNumber, byte[] fallback) {
            List<Object> values = values(fieldNumber);
            if (values == null) {
                return fallback;
            }
            for (Object value : values) {
                if (value instanceof RawBytes raw) {
                    return raw.value();
                }
                if (value instanceof String asString) {
                    return asString.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            return fallback;
        }

        /** 取第一个嵌套子消息的原始字节；没有则返回 {@code null}。 */
        public byte[] getMessageBytes(int fieldNumber) {
            return getBytes(fieldNumber, null);
        }

        /** 把第一个嵌套子消息当成一条新消息继续解析；没有则返回 {@code null}。 */
        public FieldSet getMessage(int fieldNumber) {
            byte[] raw = getMessageBytes(fieldNumber);
            return raw == null ? null : parse(raw);
        }

        /** 把某个字段号下的所有嵌套子消息都解出来。 */
        public List<FieldSet> getMessages(int fieldNumber) {
            List<Object> values = values(fieldNumber);
            if (values == null) {
                return List.of();
            }
            List<FieldSet> result = new ArrayList<>(values.size());
            for (Object value : values) {
                if (value instanceof RawBytes raw) {
                    result.add(parse(raw.value()));
                } else if (value instanceof String asString) {
                    result.add(parse(asString.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                }
            }
            return result;
        }

        /**
         * 在整棵子树里递归找一个字段号，返回第一个命中的字符串。
         *
         * <p>用途：抖音的「用户等级」藏在图标 URL 里，而那个 URL 埋得比较深。
         * 与其把每一层嵌套都手写出来（抖音一改结构就崩），
         * 不如直接在子树里找。字段号是我们自己挑的特征字段，不会认错。
         */
        public String findDeepString(int fieldNumber) {
            return findDeepString(fieldNumber, 0);
        }

        private String findDeepString(int fieldNumber, int depth) {
            // 限制递归深度，防止畸形数据把栈打爆
            if (depth > MAX_DEPTH) {
                return null;
            }
            String direct = getString(fieldNumber, null);
            if (direct != null) {
                return direct;
            }
            for (List<Object> values : fields.values()) {
                for (Object value : values) {
                    if (value instanceof RawBytes raw) {
                        String found = parse(raw.value()).findDeepString(fieldNumber, depth + 1);
                        if (found != null) {
                            return found;
                        }
                    }
                }
            }
            return null;
        }

        /** 便于排查问题时把整棵子树 dump 成文本。 */
        @Override
        public String toString() {
            return fields.toString();
        }
    }

    /** 递归深度上限，防止畸形/恶意数据导致栈溢出。 */
    private static final int MAX_DEPTH = 24;

    private final byte[] data;
    private int position;

    /** 是否已经读到末尾。 */
    public boolean hasRemaining() {
        return position < data.length;
    }

    /**
     * 底层读取状态。
     *
     * <p>{@link #data} 是整块字节，{@link #position} 是当前游标。
     * 之所以要保存「当前位置」，是因为读长度的之前和之后都要动游标。
     */
    private ProtobufReader(byte[] data) {
        this.data = data;
        this.position = 0;
    }

    /**
     * 把一块字节解析成「字段号 -&gt; 值」的映射。
     *
     * <p>遇到不认识的字段号没关系，会被正常跳过；
     * 数据在中间被截断也不会抛异常，只是停止解析（网络数据不保证完整）。
     */
    public static FieldSet parse(byte[] data) {
        return parse(data, 0, data.length);
    }

    /** 解析 {@code data} 中 {@code [offset, offset+length)} 这一段。 */
    public static FieldSet parse(byte[] data, int offset, int length) {
        Map<Integer, List<Object>> fields = new LinkedHashMap<>();
        ProtobufReader reader = new ProtobufReader(data);
        reader.position = offset;
        int end = offset + length;

        try {
            while (reader.position < end) {
                long key = reader.readVarint();
                int fieldNumber = (int) (key >>> 3);
                int wireType = (int) (key & 0x7);
                // 字段号 0 是非法值，说明数据已经乱了，直接停
                if (fieldNumber == 0) {
                    break;
                }

                Object value = switch (wireType) {
                    case WIRE_VARINT -> reader.readVarint();
                    case WIRE_FIXED64 -> reader.readFixed(8);
                    case WIRE_LENGTH_DELIMITED -> reader.readLengthDelimited();
                    case WIRE_FIXED32 -> reader.readFixed(4);
                    case WIRE_START_GROUP, WIRE_END_GROUP -> null; // 已废弃，跳过
                    default -> null;                                // 未知线格式，跳过
                };

                if (value != null) {
                    fields.computeIfAbsent(fieldNumber, ignored -> new ArrayList<>(2)).add(value);
                }
            }
        } catch (IndexOutOfBoundsException | NegativeArraySizeException truncated) {
            // 数据不完整是网络场景的常态，把已经解析出来的部分返回即可
        }

        return new FieldSet(fields);
    }

    // ------------------------------------------------------------------
    //  下面是逐个基本类型的读取实现
    // ------------------------------------------------------------------

    /**
     * 读一个 varint（变长整数）。
     *
     * <p>编码方式：每个字节只用低 7 位存数据，最高位是「还有后续字节」标志。
     * 所以把每字节的低 7 位依次左移 7、14、21… 拼起来即可。
     */
    private long readVarint() {
        long result = 0L;
        int shift = 0;
        while (true) {
            if (position >= data.length) {
                throw new IndexOutOfBoundsException("varint 越界");
            }
            byte current = data[position++];
            result |= (long) (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return result;
            }
            shift += 7;
            // 64 位整数最多占 10 个字节，超过说明数据有问题
            if (shift > 63) {
                throw new IndexOutOfBoundsException("varint 过长");
            }
        }
    }

    /** 读一个长度前缀字段，返回包好的原始字节。 */
    private RawBytes readLengthDelimited() {
        int length = (int) readVarint();
        if (length < 0 || position + length > data.length) {
            throw new IndexOutOfBoundsException("长度越界");
        }
        byte[] result = new byte[length];
        System.arraycopy(data, position, result, 0, length);
        position += length;
        return new RawBytes(result);
    }

    /** 读定长字段（只用于跳过，值本身用不到）。 */
    private Long readFixed(int byteCount) {
        if (position + byteCount > data.length) {
            throw new IndexOutOfBoundsException("定长字段越界");
        }
        position += byteCount;
        return null;
    }

    // ------------------------------------------------------------------
    //  写入侧：抖音协议要求客户端回 ack 和心跳，所以也需要能编码
    // ------------------------------------------------------------------

    /** 把一个整数写成 varint 字节。 */
    public static void writeVarint(ByteArrayOutputStream out, long value) {
        while (true) {
            // 取低 7 位；如果剩下的部分不为 0，最高位置 1 表示「还有后续」
            if ((value & ~0x7FL) == 0) {
                out.write((int) value);
                return;
            }
            out.write((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    /** 写一个「长度前缀」字段的头部：key + 长度。 */
    public static void writeLengthDelimitedHeader(ByteArrayOutputStream out, int fieldNumber, int length) {
        writeVarint(out, ((long) fieldNumber << 3) | WIRE_LENGTH_DELIMITED);
        writeVarint(out, length);
    }

    /** 写一个 varint 字段的头部：key。 */
    public static void writeVarintHeader(ByteArrayOutputStream out, int fieldNumber) {
        writeVarint(out, ((long) fieldNumber << 3) | WIRE_VARINT);
    }
}
