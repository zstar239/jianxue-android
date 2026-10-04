package org.jianxue.ime;

/** 使用 Android 常量的数值，让隐私边界也能在普通 JVM 上验证。 */
public final class EditorPolicy {
    private EditorPolicy() {}
    public static boolean password(int type) {
        int inputClass = type & 0xf, variation = type & 0xff0;
        return (inputClass == 1 && (variation == 0x80 || variation == 0x90 || variation == 0xe0))
                || (inputClass == 2 && variation == 0x10);
    }
    public static boolean privateInput(int type, int options, boolean manual) {
        return manual || password(type) || (options & 0x1000000) != 0;
    }
    public static boolean numeric(int type) { int kind = type & 0xf; return kind == 2 || kind == 3 || kind == 4; }
    public static boolean ascii(int type) {
        int variation = type & 0xff0;
        return password(type) || ((type & 0xf) == 1 && (variation == 0x10 || variation == 0x20 || variation == 0xd0));
    }
}
