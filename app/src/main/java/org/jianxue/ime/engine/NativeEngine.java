package org.jianxue.ime.engine;

/** JNI 只能由 EngineHost 的专用线程调用。 */
public final class NativeEngine {
    static { System.loadLibrary("jianxue"); }
    private NativeEngine() {}
    public static native long create(String dataDirectory, String userDirectory, String config);
    public static native String dispatch(long handle, String event);
    public static native void destroy(long handle);
}
