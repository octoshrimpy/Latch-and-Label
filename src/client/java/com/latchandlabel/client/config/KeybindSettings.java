package com.latchandlabel.client.config;

import org.lwjgl.glfw.GLFW;

public final class KeybindSettings {
    private static int openPickerKeyCode = GLFW.GLFW_KEY_B;
    private static int findShortcutKeyCode = GLFW.GLFW_KEY_UNKNOWN;
    private static int moveToStorageKeyCode = GLFW.GLFW_KEY_UNKNOWN;

    private KeybindSettings() {
    }

    public static int openPickerKeyCode() {
        return openPickerKeyCode;
    }

    public static void setOpenPickerKeyCode(int keyCode) {
        openPickerKeyCode = keyCode;
    }

    public static int findShortcutKeyCode() {
        return findShortcutKeyCode;
    }

    public static void setFindShortcutKeyCode(int keyCode) {
        findShortcutKeyCode = keyCode;
    }

    public static int moveToStorageKeyCode() {
        return moveToStorageKeyCode;
    }

    public static void setMoveToStorageKeyCode(int keyCode) {
        moveToStorageKeyCode = keyCode;
    }
}
