package com.webssh.ws;

import java.util.List;

/**
 * Shell 输出过滤结果 —— 包含过滤后的可见输出和提取到的工作目录路径。
 *
 * @see ShellOutputFilter#consume(byte[], int)
 */
public final class ShellOutputChunk {
    static final ShellOutputChunk EMPTY = new ShellOutputChunk(new byte[0], List.of());

    private final byte[] visibleBytes;
    private final List<String> cwdPaths;

    ShellOutputChunk(byte[] visibleBytes, List<String> cwdPaths) {
        this.visibleBytes = visibleBytes;
        this.cwdPaths = cwdPaths;
    }

    public byte[] visibleBytes() {
        return visibleBytes;
    }

    public List<String> cwdPaths() {
        return cwdPaths;
    }
}
