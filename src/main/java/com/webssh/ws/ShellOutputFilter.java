package com.webssh.ws;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Shell 输出过滤器 —— 从终端原始输出中提取工作目录标记并过滤注入命令的回显。
 *
 * <p>
 * 设计思路：
 * <ul>
 * <li>连接建立后会向 Shell 注入 CWD 追踪命令，这些命令的回显需要从输出中剥离</li>
 * <li>Shell 每次执行命令后会输出 {@code \002__WEBSSH_CWD__:/path\003} 标记</li>
 * <li>过滤器负责识别并提取这些标记，同时将其从用户可见输出中移除</li>
 * </ul>
 *
 * <p>
 * 使用内部缓冲区处理跨数据块边界的标记（标记可能被拆分到两次 read 调用中）。
 */
public final class ShellOutputFilter {

    /** STX (0x02) —— CWD 标记起始字节 */
    static final byte MARKER_START = 0x02;
    /** ETX (0x03) —— CWD 标记结束字节 */
    static final byte MARKER_END = 0x03;
    /** 工作目录标记前缀的 ASCII 字节序列 */
    static final byte[] MARKER_PREFIX = "__WEBSSH_CWD__:".getBytes(StandardCharsets.US_ASCII);

    /** 完整标记的最小长度：STX + prefix + ETX（路径可以为空） */
    private static final int MIN_MARKER_LENGTH = 1 + MARKER_PREFIX.length + 1;

    private final int bufferLimit;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final List<byte[]> pendingInitEchoLines = new ArrayList<>();

    /**
     * @param bufferLimit 缓冲区上限（字节），超过后降级为不过滤透传
     */
    public ShellOutputFilter(int bufferLimit) {
        this.bufferLimit = bufferLimit;
    }

    public void reset() {
        buffer.reset();
        pendingInitEchoLines.clear();
    }

    public void armInitialization(List<String> commands) {
        reset();
        for (String command : commands) {
            pendingInitEchoLines.add(command.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 消费一段 shell 输出并解析为"可见输出 + cwd 更新"。
     * <p>
     * 解析策略：
     * 1) 在缓冲区中同时查找 cwd 标记与待过滤回显；
     * 2) 将普通文本写入 visible；
     * 3) 命中 cwd 标记时提取路径并从可见输出中移除；
     * 4) 命中待过滤回显时整段跳过；
     * 5) 末尾残留不完整片段回写 buffer，等待下一批数据拼接。
     * </p>
     */
    public ShellOutputChunk consume(byte[] data, int len) {
        if (len <= 0) {
            return ShellOutputChunk.EMPTY;
        }
        buffer.write(data, 0, len);

        if (buffer.size() > bufferLimit) {
            byte[] overflow = buffer.toByteArray();
            buffer.reset();
            pendingInitEchoLines.clear();
            return new ShellOutputChunk(overflow, List.of());
        }

        byte[] current = buffer.toByteArray();
        ByteArrayOutputStream visible = new ByteArrayOutputStream(current.length);
        List<String> cwdPaths = new ArrayList<>();
        int offset = 0;

        while (offset < current.length) {
            int nextMarker = indexOf(current, MARKER_START, offset);
            int nextEcho = nextPendingEchoPosition(current, offset);
            int eventPos = earliestPositive(nextMarker, nextEcho);

            if (eventPos < 0) {
                // 没有找到任何事件
                if (!pendingInitEchoLines.isEmpty()) {
                    // 初始化阶段：仅输出完整行，保留半行等待下次拼接
                    int lastLB = lastLineBreak(current, offset, current.length);
                    if (lastLB >= offset) {
                        visible.write(current, offset, lastLB + 1 - offset);
                        offset = lastLB + 1;
                        continue;
                    }
                } else {
                    // 正常阶段：检查末尾是否有潜在的部分 STX 标记
                    int safeEnd = findSafeFlushEnd(current, offset);
                    if (safeEnd > offset) {
                        visible.write(current, offset, safeEnd - offset);
                    }
                    offset = safeEnd;
                }
                break;
            }

            // 输出事件位置之前的普通文本
            if (eventPos > offset) {
                if (eventPos == nextEcho) {
                    // echo 事件前只输出到最近的换行（避免半行被误判）
                    int lastLB = lastLineBreak(current, offset, eventPos);
                    if (lastLB >= offset) {
                        visible.write(current, offset, lastLB + 1 - offset);
                    }
                } else {
                    visible.write(current, offset, eventPos - offset);
                }
                offset = eventPos;
            }

            if (offset >= current.length) {
                break;
            }

            // 处理初始化回显
            if (offset == nextEcho) {
                offset = consumeEcho(current, offset, visible);
                continue;
            }

            // 处理 CWD 标记
            if (offset == nextMarker) {
                int result = consumeMarker(current, offset, cwdPaths, visible);
                if (result < 0) {
                    // 标记不完整，保留到下次
                    offset = -result;
                    break;
                }
                offset = result;
            }
        }

        buffer.reset();
        if (offset < current.length) {
            buffer.write(current, offset, current.length - offset);
        }
        if (visible.size() == 0 && cwdPaths.isEmpty()) {
            return ShellOutputChunk.EMPTY;
        }
        return new ShellOutputChunk(visible.toByteArray(), cwdPaths);
    }

    /**
     * 在正常阶段（无待过滤回显），计算可以安全输出的末尾位置。
     * 如果缓冲区末尾恰好是 STX 字节（可能是标记的开头），保留它等下次拼接。
     */
    private static int findSafeFlushEnd(byte[] data, int offset) {
        if (data.length <= offset) {
            return offset;
        }
        // 从末尾向前扫描，如果最后一个字节是 STX，保留它
        // （STX 在正常终端输出中极少出现，保留一个字节的代价可忽略）
        if (data[data.length - 1] == MARKER_START) {
            return data.length - 1;
        }
        return data.length;
    }

    /**
     * 消费一条初始化回显。返回消费后的新 offset。
     */
    private int consumeEcho(byte[] current, int offset, ByteArrayOutputStream visible) {
        byte[] line = pendingInitEchoLines.get(0);
        if (!startsWith(current, offset, line)) {
            // 防御性处理：定位到疑似位置但不完整匹配时按普通字节透传
            visible.write(current[offset]);
            return offset + 1;
        }
        offset += line.length;
        // 跳过尾随的 CR/LF
        while (offset < current.length && (current[offset] == '\r' || current[offset] == '\n')) {
            offset++;
        }
        pendingInitEchoLines.remove(0);
        return offset;
    }

    /**
     * 消费一个 CWD 标记。
     *
     * @return 正数表示消费后的新 offset；负数表示标记不完整（取绝对值为应保留的起始 offset）
     */
    private static int consumeMarker(byte[] current, int offset, List<String> cwdPaths,
            ByteArrayOutputStream visible) {
        // 检查 prefix 是否完整
        if (current.length < offset + 1 + MARKER_PREFIX.length) {
            return -offset; // 不完整，保留
        }
        if (!startsWith(current, offset + 1, MARKER_PREFIX)) {
            // STX 碰撞但非合法标记，按普通字节处理
            visible.write(current[offset]);
            return offset + 1;
        }
        int pathStart = offset + 1 + MARKER_PREFIX.length;
        int pathEnd = indexOf(current, MARKER_END, pathStart);
        if (pathEnd < 0) {
            return -offset; // ETX 未到达，保留
        }
        if (pathEnd > pathStart) {
            cwdPaths.add(new String(current, pathStart, pathEnd - pathStart, StandardCharsets.UTF_8));
        }
        return pathEnd + 1;
    }

    // ---- 字节数组工具方法 ----

    /** 查找下一条待过滤初始化回显在 data 中的位置。 */
    private int nextPendingEchoPosition(byte[] data, int fromIndex) {
        if (pendingInitEchoLines.isEmpty()) {
            return -1;
        }
        return indexOf(data, pendingInitEchoLines.get(0), fromIndex);
    }

    /** 返回两个位置中最早的有效位置（忽略 -1）。 */
    static int earliestPositive(int a, int b) {
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    /** 在指定区间逆向查找最后一个换行符位置。 */
    static int lastLineBreak(byte[] data, int fromInclusive, int toExclusive) {
        for (int i = toExclusive - 1; i >= fromInclusive; i--) {
            if (data[i] == '\n' || data[i] == '\r') {
                return i;
            }
        }
        return -1;
    }

    /** 从 fromIndex 起查找某个字节。 */
    static int indexOf(byte[] data, byte value, int fromIndex) {
        for (int i = Math.max(fromIndex, 0); i < data.length; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }

    /** 从 fromIndex 起查找字节模式。 */
    static int indexOf(byte[] data, byte[] pattern, int fromIndex) {
        if (pattern.length == 0) {
            return Math.max(fromIndex, 0);
        }
        for (int i = Math.max(fromIndex, 0); i <= data.length - pattern.length; i++) {
            if (startsWith(data, i, pattern)) {
                return i;
            }
        }
        return -1;
    }

    /** 判断从 offset 开始是否匹配给定字节模式。 */
    static boolean startsWith(byte[] data, int offset, byte[] pattern) {
        if (offset < 0 || offset + pattern.length > data.length) {
            return false;
        }
        for (int i = 0; i < pattern.length; i++) {
            if (data[offset + i] != pattern[i]) {
                return false;
            }
        }
        return true;
    }
}
