package com.anland.termux;

import android.util.Log;
import android.view.KeyEvent;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import juloo.keyboard2.Config;
import juloo.keyboard2.KeyValue;
import juloo.keyboard2.Pointers;

/**
 * Bridges the embedded {@link juloo.keyboard2.Keyboard2View} (Unexpected Keyboard) into
 * Anland's native input pipeline.
 *
 * <p>The keyboard delivers <em>resolved</em> {@link KeyValue}s through
 * {@link Config.IKeyEventHandler}. A resolved value is either a final {@code Char}
 * (Shift already applied by the keyboard), an Android {@code Keyevent} (F-keys, arrows,
 * Tab, Esc, Enter, and letters converted under Ctrl/Alt), a modifier, an {@code Editing}
 * action (space / backspace), an {@code Event}, or a {@code String}.</p>
 *
 * <p>Anland's display consumer expects <em>raw Linux evdev scancodes</em> on
 * {@link Native#nativeSendKey(int, int)} — exactly what {@link KeyCodeMapper} produces
 * from an Android key code — and UTF-8 text on {@link Native#nativeSendTextInput(byte[])}.
 * This class performs that conversion.</p>
 *
 * <p>Modifier keys (Shift/Ctrl/Alt) are managed by the keyboard's own latch state and are
 * reported through {@link #mods_changed(Pointers.Modifiers)}. They are reproduced here as
 * a self-contained "chord" around each key press (modifier-down, key-down, key-up,
 * modifier-up), so a latched Ctrl behaves as a sticky Ctrl on the Wayland side without
 * needing to hold persistent key state.</p>
 */
public final class AnlandKeyboardBridge implements Config.IKeyEventHandler {

    private static final String TAG = "AnlandKeyboard";

    // Linux evdev modifier scancodes (mirrors KeyCodeMapper / input-event-codes.h).
    private static final int EV_KEY_LEFTSHIFT = 42;
    private static final int EV_KEY_LEFTCTRL = 29;
    private static final int EV_KEY_LEFTALT = 56;
    private static final int EV_KEY_LEFTMETA = 125;

    /** Current modifier latch state, as last reported by {@code mods_changed}. */
    private boolean mCtrl;
    private boolean mAlt;
    private boolean mShift;
    private boolean mMeta;

    // --- Char -> Android key code reverse map (US QWERTY base row) ---
    // The Value field is the base (unshifted) Android key code; the shift flag is held
    // separately in SHIFTED_CHARS. This is the canonical mapping the keyboard's own
    // `turn_into_keyevent` uses for letters/digits/symbols.
    private static final Map<Character, Integer> CHAR_TO_KEYCODE = new HashMap<>();
    private static final Map<Character, Integer> SHIFTED_CHAR_TO_KEYCODE = new HashMap<>();

    static {
        // Letters (key code of the base letter, case-insensitive here).
        char[] lower = "abcdefghijklmnopqrstuvwxyz".toCharArray();
        int[] codes = {
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_D,
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_H,
            KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_L,
            KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_P,
            KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_R, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_T,
            KeyEvent.KEYCODE_U, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_X,
            KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_Z,
        };
        for (int i = 0; i < lower.length; i++) {
            CHAR_TO_KEYCODE.put(lower[i], codes[i]);
            SHIFTED_CHAR_TO_KEYCODE.put(Character.toUpperCase(lower[i]), codes[i]);
        }

        // Unshifted symbols (base key).
        CHAR_TO_KEYCODE.put('`', KeyEvent.KEYCODE_GRAVE);
        CHAR_TO_KEYCODE.put('1', KeyEvent.KEYCODE_1);
        CHAR_TO_KEYCODE.put('2', KeyEvent.KEYCODE_2);
        CHAR_TO_KEYCODE.put('3', KeyEvent.KEYCODE_3);
        CHAR_TO_KEYCODE.put('4', KeyEvent.KEYCODE_4);
        CHAR_TO_KEYCODE.put('5', KeyEvent.KEYCODE_5);
        CHAR_TO_KEYCODE.put('6', KeyEvent.KEYCODE_6);
        CHAR_TO_KEYCODE.put('7', KeyEvent.KEYCODE_7);
        CHAR_TO_KEYCODE.put('8', KeyEvent.KEYCODE_8);
        CHAR_TO_KEYCODE.put('9', KeyEvent.KEYCODE_9);
        CHAR_TO_KEYCODE.put('0', KeyEvent.KEYCODE_0);
        CHAR_TO_KEYCODE.put('-', KeyEvent.KEYCODE_MINUS);
        CHAR_TO_KEYCODE.put('=', KeyEvent.KEYCODE_EQUALS);
        CHAR_TO_KEYCODE.put('[', KeyEvent.KEYCODE_LEFT_BRACKET);
        CHAR_TO_KEYCODE.put(']', KeyEvent.KEYCODE_RIGHT_BRACKET);
        CHAR_TO_KEYCODE.put('\\', KeyEvent.KEYCODE_BACKSLASH);
        CHAR_TO_KEYCODE.put(';', KeyEvent.KEYCODE_SEMICOLON);
        CHAR_TO_KEYCODE.put('\'', KeyEvent.KEYCODE_APOSTROPHE);
        CHAR_TO_KEYCODE.put(',', KeyEvent.KEYCODE_COMMA);
        CHAR_TO_KEYCODE.put('.', KeyEvent.KEYCODE_PERIOD);
        CHAR_TO_KEYCODE.put('/', KeyEvent.KEYCODE_SLASH);
        CHAR_TO_KEYCODE.put(' ', KeyEvent.KEYCODE_SPACE);

        // Shifted symbols (shift + base key).
        SHIFTED_CHAR_TO_KEYCODE.put('~', KeyEvent.KEYCODE_GRAVE);
        SHIFTED_CHAR_TO_KEYCODE.put('!', KeyEvent.KEYCODE_1);
        SHIFTED_CHAR_TO_KEYCODE.put('@', KeyEvent.KEYCODE_2);
        SHIFTED_CHAR_TO_KEYCODE.put('#', KeyEvent.KEYCODE_3);
        SHIFTED_CHAR_TO_KEYCODE.put('$', KeyEvent.KEYCODE_4);
        SHIFTED_CHAR_TO_KEYCODE.put('%', KeyEvent.KEYCODE_5);
        SHIFTED_CHAR_TO_KEYCODE.put('^', KeyEvent.KEYCODE_6);
        SHIFTED_CHAR_TO_KEYCODE.put('&', KeyEvent.KEYCODE_7);
        SHIFTED_CHAR_TO_KEYCODE.put('*', KeyEvent.KEYCODE_8);
        SHIFTED_CHAR_TO_KEYCODE.put('(', KeyEvent.KEYCODE_9);
        SHIFTED_CHAR_TO_KEYCODE.put(')', KeyEvent.KEYCODE_0);
        SHIFTED_CHAR_TO_KEYCODE.put('_', KeyEvent.KEYCODE_MINUS);
        SHIFTED_CHAR_TO_KEYCODE.put('+', KeyEvent.KEYCODE_EQUALS);
        SHIFTED_CHAR_TO_KEYCODE.put('{', KeyEvent.KEYCODE_LEFT_BRACKET);
        SHIFTED_CHAR_TO_KEYCODE.put('}', KeyEvent.KEYCODE_RIGHT_BRACKET);
        SHIFTED_CHAR_TO_KEYCODE.put('|', KeyEvent.KEYCODE_BACKSLASH);
        SHIFTED_CHAR_TO_KEYCODE.put(':', KeyEvent.KEYCODE_SEMICOLON);
        SHIFTED_CHAR_TO_KEYCODE.put('"', KeyEvent.KEYCODE_APOSTROPHE);
        SHIFTED_CHAR_TO_KEYCODE.put('<', KeyEvent.KEYCODE_COMMA);
        SHIFTED_CHAR_TO_KEYCODE.put('>', KeyEvent.KEYCODE_PERIOD);
        SHIFTED_CHAR_TO_KEYCODE.put('?', KeyEvent.KEYCODE_SLASH);
    }

    @Override
    public void key_down(KeyValue value, boolean is_swipe) {
        if (value == null)
            return;
        dispatchDown(value);
    }

    @Override
    public void key_up(KeyValue value, Pointers.Modifiers mods) {
        if (value == null)
            return;
        dispatchUp(value);
    }

    @Override
    public void mods_changed(Pointers.Modifiers mods) {
        if (mods == null) {
            mCtrl = mAlt = mShift = mMeta = false;
            return;
        }
        mCtrl = mods.has(KeyValue.Modifier.CTRL);
        mAlt = mods.has(KeyValue.Modifier.ALT);
        mShift = mods.has(KeyValue.Modifier.SHIFT);
        mMeta = mods.has(KeyValue.Modifier.META);
    }

    @Override
    public void suggestion_entered(String text) {
        if (text != null && !text.isEmpty())
            Native.nativeSendTextInput(text.getBytes(StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------------------------
    // Dispatch
    // -----------------------------------------------------------------------------------

    private void dispatchDown(KeyValue value) {
        switch (value.getKind()) {
            case Char:
                downChar(value.getChar());
                break;
            case Keyevent:
                downChord(value.getKeyevent());
                break;
            case Editing:
                downEditing(value.getEditing());
                break;
            case Event:
                downEvent(value.getEvent());
                break;
            case String:
                Native.nativeSendTextInput(value.getString().getBytes(StandardCharsets.UTF_8));
                break;
            // Modifiers are latch-state only; the chord reproduces them. Macro/Compose/
            // Placeholder/Slider/Hangul/Stateful/Launcher_tool need no evdev event here.
            default:
                break;
        }
    }

    private void dispatchUp(KeyValue value) {
        switch (value.getKind()) {
            case Char:
                upChar(value.getChar());
                break;
            case Keyevent:
                upChord(value.getKeyevent());
                break;
            case Editing:
                upEditing(value.getEditing());
                break;
            case Event:
                upEvent(value.getEvent());
                break;
            default:
                break;
        }
    }

    // --- Char ---
    // A char is already Shift-resolved by the keyboard, so shift is derived purely from the
    // char (never from the live modifier latch) to avoid a double-shift.

    private void downChar(char c) {
        if (sendCharChord(c))
            return; // Reproduced as an evdev chord.
        // Non-ASCII / unmappable: fall back to a UTF-8 text input.
        sendTextFallback(String.valueOf(c));
    }

    private void upChar(char c) {
        // A text fallback has no key-up; an evdev chord is released transiently inside
        // sendCharChord (down+up), so nothing to do here.
    }

    /** Sends a self-contained down/up chord for an ASCII char. Returns false if unmappable. */
    private boolean sendCharChord(char c) {
        Integer keyCode = CHAR_TO_KEYCODE.get(c);
        if (keyCode != null) {
            sendTap(keyCode, false);
            return true;
        }
        keyCode = SHIFTED_CHAR_TO_KEYCODE.get(c);
        if (keyCode != null) {
            sendTap(keyCode, true);
            return true;
        }
        return false;
    }

    // --- Keyevent ---
    // A Keyevent appears for F-keys, arrows, Tab, Esc, Enter, and letters converted under
    // Ctrl/Alt. It is reproduced as the raw key held within the live modifier latch.

    private void downChord(int keyCode) {
        int evdev = KeyCodeMapper.getScanCode(keyCode);
        if (evdev < 0) {
            Log.w(TAG, "Unmapped keyevent keycode=" + keyCode);
            return;
        }
        pressModifiers(true);
        Native.nativeSendKey(0, evdev);
    }

    private void upChord(int keyCode) {
        int evdev = KeyCodeMapper.getScanCode(keyCode);
        if (evdev < 0)
            return;
        Native.nativeSendKey(1, evdev);
        pressModifiers(false);
    }

    // --- Editing ---

    private void downEditing(KeyValue.Editing editing) {
        switch (editing) {
            case SPACE_BAR:
                Native.nativeSendKey(0, KeyCodeMapper.getScanCode(KeyEvent.KEYCODE_SPACE));
                break;
            case BACKSPACE:
                Native.nativeSendKey(0, KeyCodeMapper.getScanCode(KeyEvent.KEYCODE_DEL));
                break;
            default:
                break; // Clipboard / selection actions are not terminal input.
        }
    }

    private void upEditing(KeyValue.Editing editing) {
        switch (editing) {
            case SPACE_BAR:
                Native.nativeSendKey(1, KeyCodeMapper.getScanCode(KeyEvent.KEYCODE_SPACE));
                break;
            case BACKSPACE:
                Native.nativeSendKey(1, KeyCodeMapper.getScanCode(KeyEvent.KEYCODE_DEL));
                break;
            default:
                break;
        }
    }

    // --- Event ---

    private void downEvent(KeyValue.Event event) {
        switch (event) {
            case ACTION:
                Native.nativeSendKey(0, KeyCodeMapper.getScanCode(KeyEvent.KEYCODE_ENTER));
                break;
            case CAPS_LOCK:
                Native.nativeSendKey(0, KeyCodeMapper.getScanCode(KeyEvent.KEYCODE_CAPS_LOCK));
                Native.nativeSendKey(1, KeyCodeMapper.getScanCode(KeyEvent.KEYCODE_CAPS_LOCK));
                break;
            default:
                break; // Layout switches / method pickers are keyboard-internal.
        }
    }

    private void upEvent(KeyValue.Event event) {
        switch (event) {
            case ACTION:
                Native.nativeSendKey(1, KeyCodeMapper.getScanCode(KeyEvent.KEYCODE_ENTER));
                break;
            default:
                break;
        }
    }

    // -----------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------

    /** Down+up a base key code, optionally holding Shift. */
    private void sendTap(int keyCode, boolean withShift) {
        int evdev = KeyCodeMapper.getScanCode(keyCode);
        if (evdev < 0) {
            sendTextFallbackFromKeycode(keyCode);
            return;
        }
        if (withShift)
            Native.nativeSendKey(0, EV_KEY_LEFTSHIFT);
        Native.nativeSendKey(0, evdev);
        Native.nativeSendKey(1, evdev);
        if (withShift)
            Native.nativeSendKey(1, EV_KEY_LEFTSHIFT);
    }

    private void sendTextFallbackFromKeycode(int keyCode) {
        // Best-effort: recover the printable character from the virtual keyboard map.
        int c = android.view.KeyCharacterMap
                .load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
                .get(keyCode, 0);
        if (c != 0)
            sendTextFallback(String.valueOf((char) c));
    }

    private void sendTextFallback(String text) {
        Native.nativeSendTextInput(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Press or release the live modifier latch as evdev modifier keys. */
    private void pressModifiers(boolean down) {
        int action = down ? 0 : 1;
        if (mCtrl)
            Native.nativeSendKey(action, EV_KEY_LEFTCTRL);
        if (mAlt)
            Native.nativeSendKey(action, EV_KEY_LEFTALT);
        if (mShift)
            Native.nativeSendKey(action, EV_KEY_LEFTSHIFT);
        if (mMeta)
            Native.nativeSendKey(action, EV_KEY_LEFTMETA);
    }
}