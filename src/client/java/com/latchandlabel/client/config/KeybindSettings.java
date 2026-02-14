package com.latchandlabel.client.config;

import org.lwjgl.glfw.GLFW;

public final class KeybindSettings {
    private static int openPickerKeyCode = GLFW.GLFW_KEY_B;

    private KeybindSettings() {
    }

    public static int openPickerKeyCode() {
        return openPickerKeyCode;
    }

    public static void setOpenPickerKeyCode(int keyCode) {
        openPickerKeyCode = keyCode;
    }
}
