package com.roatpkz.common;

/**
 * Test stand-in for the debug printer whose println(String) is silenced.
 * sentinel(int) is the untouched control.
 */
@SuppressWarnings("unused")
public class DebugPrintStream {

    private long lines;

    public void println(String s) {
        try {
            if (s == null) {
                lines++;
            } else {
                lines += s.length();
            }
        } catch (RuntimeException ex) {
            lines = -1;
        }
    }

    public int sentinel(int x) {
        try {
            switch (x & 3) {
                case 0: return x;
                case 1: return x + 1;
                case 2: return x + 2;
                default: return x + 3;
            }
        } catch (RuntimeException ex) {
            return -1;
        }
    }
}
