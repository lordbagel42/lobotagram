package dev.lobotagram.extension;

/**
 * Smoke test for the extension pipeline.
 *
 * <p>The diagnostics patch looks this class up after the extension DEX is
 * merged, which proves javac to D8 to rve to rvp to merged-into-Instagram all
 * work. Nothing in a shipped build should call it.
 */
public final class Hello {
    private Hello() {
    }

    public static void hello(String s) {
        Lobo.i("hello " + s);
    }
}
