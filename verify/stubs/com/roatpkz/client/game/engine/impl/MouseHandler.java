package com.roatpkz.client.game.engine.impl;

import java.awt.event.MouseEvent;

/**
 * Test stand-in with the same name as the client class the agent hooks.
 *
 * <p>Every input method carries a branch, a switch and a try/catch/finally, so
 * a patch that dropped the exception table or the StackMapTable could not link
 * at major version 55 - which is exactly the failure the old hand-rolled
 * rewriter hid. mouseReleased is deliberately not a hook target and acts as a
 * control that proves no other method was touched.
 */
@SuppressWarnings("unused")
public class MouseHandler {

    public int clicks;
    public int lastX;

    public void mousePressed(MouseEvent e) {
        try {
            int button = (e == null) ? -1 : e.getButton();
            switch (button) {
                case 1:
                    clicks++;
                    break;
                case 2:
                    clicks += 2;
                    break;
                case 3:
                    clicks += 3;
                    break;
                default:
                    clicks += 0;
                    break;
            }
            if (clicks > 100) {
                lastX = -1;
            } else {
                lastX = (e == null) ? 0 : e.getX();
            }
        } catch (RuntimeException ex) {
            lastX = -2;
        } finally {
            if (lastX == -99) clicks = 0;
        }
    }

    public void mouseMoved(MouseEvent e) {
        try {
            int x = (e == null) ? 0 : e.getX();
            if (x < 0) {
                lastX = 0;
            } else if (x > 1000) {
                lastX = 1000;
            } else {
                lastX = x;
            }
            switch (lastX % 3) {
                case 0: clicks += 0; break;
                case 1: clicks += 1; break;
                default: clicks += 2; break;
            }
        } catch (Throwable t) {
            lastX = -3;
        }
    }

    public void mouseDragged(MouseEvent e) {
        try {
            boolean dragging = e != null;
            if (dragging) {
                lastX += 1;
            } else {
                lastX -= 1;
            }
            switch (clicks & 7) {
                case 0: break;
                case 1: clicks++; break;
                case 2: clicks += 2; break;
                default: clicks += 0; break;
            }
        } catch (RuntimeException ex) {
            lastX = -4;
        } finally {
            if (clicks < 0) clicks = 0;
        }
    }

    public void mouseReleased(MouseEvent e) {
        try {
            int button = (e == null) ? -1 : e.getButton();
            if (button == 1) {
                clicks += 10;
            } else {
                clicks += 1;
            }
        } catch (RuntimeException ex) {
            clicks = 0;
        }
    }
}
