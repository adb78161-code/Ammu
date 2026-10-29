package com.ammu.assistant;

/** JNI boundary for the bundled llama.cpp/Qwen3 engine. */
final class QwenNativeEngine implements AutoCloseable {
    static final String VERSION = "llama.cpp 7df8b876b91208a86a6d792e1140b3deec14535b";

    static { System.loadLibrary("ammu_qwen"); }

    interface TokenCallback { void onToken(String token); }

    private long nativeHandle;

    synchronized boolean load(String modelPath, int contextLength) {
        nativeHandle = nativeLoad(modelPath, contextLength);
        return nativeHandle != 0;
    }

    synchronized String generate(String chatPrompt, int maxTokens, TokenCallback callback) {
        if (nativeHandle == 0) throw new IllegalStateException("Qwen model is not loaded");
        return nativeGenerate(nativeHandle, chatPrompt, maxTokens, callback);
    }

    synchronized void cancel() { if (nativeHandle != 0) nativeCancel(nativeHandle); }

    @Override public synchronized void close() {
        if (nativeHandle != 0) { nativeClose(nativeHandle); nativeHandle = 0; }
    }

    private static native long nativeLoad(String modelPath, int contextLength);
    private static native String nativeGenerate(long handle, String prompt, int maxTokens, TokenCallback callback);
    private static native void nativeCancel(long handle);
    private static native void nativeClose(long handle);
}
