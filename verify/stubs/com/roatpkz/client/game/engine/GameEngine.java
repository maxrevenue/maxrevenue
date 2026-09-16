package com.roatpkz.client.game.engine;

/**
 * Test stand-in for the client cycle class the agent prepends
 * {@code ClientHooks.onClientTick} onto. {@code clientTick} carries a branch,
 * a switch and a try/catch/finally so a patch that dropped the exception table
 * or StackMapTable cannot link at major version 55. {@code sentinel} is not a
 * hook target and proves no other method was touched.
 */
@SuppressWarnings("unused")
public class GameEngine {

    public int cycles;
    public int last;

    public void clientTick() {
        try {
            int n = cycles;
            switch (n & 3) {
                case 0:
                    last = n;
                    break;
                case 1:
                    last = n + 1;
                    break;
                case 2:
                    last = n + 2;
                    break;
                default:
                    last = n - 1;
                    break;
            }
            if (last > 1000) {
                cycles = 0;
            } else {
                cycles++;
            }
        } catch (RuntimeException ex) {
            last = -1;
        } finally {
            if (last == -99) cycles = 0;
        }
    }

    public int sentinel(int x) {
        try {
            if (x < 0) {
                return 0;
            } else if (x > 100) {
                return 100;
            }
            switch (x % 3) {
                case 0: return x;
                case 1: return x + 1;
                default: return x + 2;
            }
        } catch (RuntimeException ex) {
            return -1;
        }
    }
}
