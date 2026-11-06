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
 * <p>The keyboard delivers <em>resolved</em> {@link KeyValue}s on
 * {@link Config.IKeyEventHandler#key_up(KeyValue, Pointers.Modifiers)} with the modifiers
 * that were held when the key was released — the same contract termux-launcher relies on:
 * {@code key_down} carries only sliders; the full, modifier-resolved action fires on
 * {@code key_up}. A resolved value is a final {@code Char} (Shift already applied), an
 * Android {@code Keyevent} (F-keys, arrows, Tab, Esc, Enter, and letters/keys converted
 * under Ctrl/Alt), an {@code Editing} action (space / backspace), an {@code Event}
 * (Enter / CapsLock), a {@code String}, or a {@code Macro} (the Fn-layer ^C / ^D).</p>
 *
 * <p>Anland's display consumer expects <em>raw Linux evdev scancodes</em> on
 * {@link Native#nativeSendKey(int, int)} — exactly what {@link KeyCodeMapper} produces
 * from an Android key code — and UTF-8 text on {@link Native#nativeSendTextInput(byte[])}.
 * This class performs that conversion, reproducing each key as a self-contained
 * down/up chord with the held modifiers wrapped around it (a sticky Ctrl reads as
 * Ctrl+key on the Wayland side).</p>
 */
public final class AnlandKeyboardBridge implements Config.IKeyEventHandler {

    private static final String TAG = "AnlandKeyboard";

    // Linux evdev modifier scancodes (mirrors KeyCodeMapper / linux input-event-codes.h).
    private static final int EV_LEFTSHIFT = 42;
    private static final int EV_LEFTCTRL = 29;
    private static final int EV_LEFTALT = 56;
    private static final int EV_LEFTMETA = 125;

    // -----------------------------------------------------------------------------------
    // Char -> (Android base key code, needs-shift) reverse maps for the US QWERTY base row.
    // A Char arrives already Shift-resolved by the keyboard (uppercase / symbol), so the
    // shift requirement is derived purely from the character, never from a held Shift latch.
    // -----------------------------------------------------------------------------------
    private static final Map<Character, Integer> CHAR_TO_KEYCODE = new HashMap<>();
    private static final Map<Character, Integer> SHIFTED_CHAR_TO_KEYCODE = new HashMap<>();

    static {
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
        // The full action (with resolved modifiers) fires on key_up, matching termux-launcher.
        // Sliders are continuous cursor gestures; they are not wired for the Wayland surface.
    }

    @Override
    public void key_up(KeyValue value, Pointers.Modifiers mods) {
        if (value == null)
            return;
        boolean ctrl = mods != null && mods.has(KeyValue.Modifier.CTRL);
        boolean alt = mods != null && mods.has(KeyValue.Modifier.ALT);
        boolean shift = mods != null && mods.has(KeyValue.Modifier.SHIFT);
        boolean meta = mods != null && mods.has(KeyValue.Modifier.META);
        dispatchStroke(value, ctrl, alt, shift, meta);
    }

    @Override
    public void mods_changed(Pointers.Modifiers mods) {
        // Modifier state is consumed directly from key_up's `mods`; nothing to cache.
    }

    @Override
    public void suggestion_entered(String text) {
        if (text != null && !text.isEmpty())
            Native.nativeSendTextInput(text.getBytes(StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------------------------
    // Dispatch
    // -----------------------------------------------------------------------------------

    private void dispatchStroke(KeyValue v, boolean ctrl, boolean alt, boolean shift, boolean meta) {
        switch (v.getKind()) {
            case Char:
                strokeChar(v.getChar(), ctrl, alt, meta);
                break;
            case Keyevent:
                strokeKeyevent(v.getKeyevent(), ctrl, alt, shift, meta);
                break;
            case Editing:
                if (v.getEditing() == KeyValue.Editing.SPACE_BAR)
                    strokeKeyevent(KeyEvent.KEYCODE_SPACE, ctrl, alt, shift, meta);
                else if (v.getEditing() == KeyValue.Editing.BACKSPACE)
                    strokeKeyevent(KeyEvent.KEYCODE_DEL, ctrl, alt, shift, meta);
                break;
            case Event:
                if (v.getEvent() == KeyValue.Event.ACTION)
                    strokeKeyevent(KeyEvent.KEYCODE_ENTER, ctrl, alt, shift, meta);
                else if (v.getEvent() == KeyValue.Event.CAPS_LOCK)
                    tapKeyevent(KeyEvent.KEYCODE_CAPS_LOCK);
                break;
            case String:
                Native.nativeSendTextInput(v.getString().getBytes(StandardCharsets.UTF_8));
                break;
            case Macro:
                strokeMacro(v.getMacro());
                break;
            // Modifier: latch-state only; reproduced inside each chord.
            // Compose/Placeholder/Slider/Hangul/Stateful/Launcher_tool: not forwarded.
            default:
                break;
        }
    }

    private void strokeChar(char c, boolean ctrl, boolean alt, boolean meta) {
        boolean[] shift = new boolean[1];
        int evdev = charToEvdev(c, shift);
        if (evdev < 0) {
            Native.nativeSendTextInput(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
            return;
        }
        chord(evdev, ctrl, alt, meta, shift[0]);
    }

    private void strokeKeyevent(int keyCode, boolean ctrl, boolean alt, boolean shift, boolean meta) {
        int evdev = KeyCodeMapper.getScanCode(keyCode);
        if (evdev < 0) {
            Log.w(TAG, "Unmapped keyevent keycode=" + keyCode);
            return;
        }
        chord(evdev, ctrl, alt, meta, shift);
    }

    /** One-shot down/up tap of a keyevent scancode (no modifiers). */
    private void tapKeyevent(int keyCode) {
        int evdev = KeyCodeMapper.getScanCode(keyCode);
        if (evdev < 0)
            return;
        Native.nativeSendKey(0, evdev);
        Native.nativeSendKey(1, evdev);
    }

    /** Expands a macro such as the Fn-layer ^C = [Ctrl, c] into one modifier-chord. */
    private void strokeMacro(KeyValue[] keys) {
        boolean ctrl = false, alt = false, shift = false, meta = false;
        KeyValue target = null;
        for (KeyValue k : keys) {
            if (k == null)
                continue;
            if (k.getKind() == KeyValue.Kind.Modifier) {
                switch (k.getModifier()) {
                    case CTRL: ctrl = true; break;
                    case ALT: alt = true; break;
                    case SHIFT: shift = true; break;
                    case META: meta = true; break;
                    default: break;
                }
            } else {
                target = k;
            }
        }
        if (target == null)
            return;

        if (target.getKind() == KeyValue.Kind.Char) {
            strokeChar(target.getChar(), ctrl, alt, meta);
        } else if (target.getKind() == KeyValue.Kind.Keyevent) {
            strokeKeyevent(target.getKeyevent(), ctrl, alt, shift, meta);
        } else if (target.getKind() == KeyValue.Kind.Editing) {
            int code = target.getEditing() == KeyValue.Editing.SPACE_BAR
                    ? KeyEvent.KEYCODE_SPACE
                    : target.getEditing() == KeyValue.Editing.BACKSPACE
                        ? KeyEvent.KEYCODE_DEL : -1;
            if (code >= 0)
                strokeKeyevent(code, ctrl, alt, shift, meta);
        }
    }

    // -----------------------------------------------------------------------------------
    // Emission
    // -----------------------------------------------------------------------------------

    /** Resolved evdev scancode for a char + whether Shift must be held. -1 if unmappable. */
    private int charToEvdev(char c, boolean[] needsShift) {
        Integer base = CHAR_TO_KEYCODE.get(c);
        boolean shift = false;
        if (base == null) {
            base = SHIFTED_CHAR_TO_KEYCODE.get(c);
            shift = base != null;
        }
        if (base == null)
            return -1;
        needsShift[0] = shift;
        return KeyCodeMapper.getScanCode(base);
    }

    /** Down+up a scancode with the given modifiers held around it. */
    private void chord(int evdev, boolean ctrl, boolean alt, boolean meta, boolean shift) {
        if (ctrl) Native.nativeSendKey(0, EV_LEFTCTRL);
        if (alt)  Native.nativeSendKey(0, EV_LEFTALT);
        if (meta) Native.nativeSendKey(0, EV_LEFTMETA);
        if (shift) Native.nativeSendKey(0, EV_LEFTSHIFT);
        Native.nativeSendKey(0, evdev);
        Native.nativeSendKey(1, evdev);
        if (shift) Native.nativeSendKey(1, EV_LEFTSHIFT);
        if (meta) Native.nativeSendKey(1, EV_LEFTMETA);
        if (alt)  Native.nativeSendKey(1, EV_LEFTALT);
        if (ctrl) Native.nativeSendKey(1, EV_LEFTCTRL);
    }
}