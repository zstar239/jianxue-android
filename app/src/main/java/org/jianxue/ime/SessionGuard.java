package org.jianxue.ime;

/** 每个输入框有独立代号，旧回调不能写入新输入框。 */
public final class SessionGuard {
    private long epoch;
    public long next() { return ++epoch; }
    public long current() { return epoch; }
    public boolean accepts(long value) { return value == epoch; }
}
