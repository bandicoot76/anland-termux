package com.anland.termux;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.HashMap;
import java.util.Map;

import juloo.keyboard2.Config;
import juloo.keyboard2.KeyValue;
import juloo.keyboard2.Keyboard2View;
import juloo.keyboard2.KeyboardData;
import juloo.keyboard2.LayoutModifier;
import juloo.keyboard2.Theme;

/**
 * A floating {@link Keyboard2View} (Unexpected Keyboard) hosted over Anland's Wayland surface.
 *
 * <p>Loads the same {@code termux_launcher_qwerty} terminal-first layout the launcher ships:
 * a Ctrl/Fn/Alt/Space/Compose/Enter bottom row plus a Fn layer (F1-F12, navigation, ^C/^D).
 * A dedicated drag handle sits above the keyboard (never overlapping keys), so the keyboard
 * can be dragged to any position in both portrait and landscape. Key events are translated to
 * evdev scancodes by {@link AnlandKeyboardBridge}.</p>
 */
public final class AnlandKeyboardHost extends FrameLayout {

    private static final String TAG = "AnlandKeyboard";
    private static final int HANDLE_DP = 28;

    private final Keyboard2View mKeyboard;
    private final Config mConfig;
    private final View mDragHandle;

    public AnlandKeyboardHost(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        setVisibility(GONE);

        AnlandKeyboardBridge bridge = new AnlandKeyboardBridge();
        mConfig = new Config(context.getResources(), bridge);

        Theme.Palette palette = buildDarkPalette();
        mKeyboard = new Keyboard2View(context, mConfig, palette);
        mKeyboard.setKeyboard(loadDefaultLayout());

        mDragHandle = new View(context);
        mDragHandle.setBackgroundColor(0x55FFFFFF);

        // Keyboard fills the host; the handle is a thin strip pinned to the top.
        addView(mKeyboard, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM));
        addView(mDragHandle, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(HANDLE_DP),
                Gravity.TOP));

        attachDrag(mDragHandle);
    }

    /**
     * Loads the terminal-first QWERTY layout with its {@code loc}-slot extra keys
     * (Esc, Tab, CapsLock, Alt, Meta, Compose) enabled — the subset that produces real
     * evdev keystrokes on the Wayland surface. Launcher-only {@code tool:} keys and
     * clipboard/text-editing keys are deliberately left out.
     */
    private KeyboardData loadDefaultLayout() {
        KeyboardData data = KeyboardData.load(getResources(), R.xml.termux_launcher_qwerty);
        if (data == null) {
            throw new IllegalStateException("Bundled termux_launcher_qwerty layout failed to load");
        }

        Map<KeyValue, KeyboardData.PreferredPos> extraKeys = new HashMap<>();
        String[] names = {"esc", "tab", "capslock", "alt", "meta", "compose"};
        for (String name : names) {
            KeyValue kv = KeyValue.getKeyByName(name);
            if (kv != null)
                extraKeys.put(kv, KeyboardData.PreferredPos.DEFAULT);
        }

        // The layout declares its own bottom row (bottom_row="false"), so there is no
        // built-in row to add; the number row is also off, matching the launcher default.
        LayoutModifier.LayoutOptions options = new LayoutModifier.LayoutOptions(false, false, true, extraKeys);
        return LayoutModifier.modify(data, options, getResources());
    }

    /** A neutral dark material palette that matches Anland's HUD colour language. */
    private static Theme.Palette buildDarkPalette() {
        int keyboard = 0xFF1B1B1B;
        int key = 0xFF333333;
        int actionKey = 0xFF262626;
        int spaceKey = 0xFF2B2B2B;
        int activatedKey = 0xFF1B1B1B;
        int label = 0xFFFFFFFF;
        int subLabel = 0xFFCCCCCC;
        int activatedLabel = 0xFF3399FF;
        int pressedLabel = 0xFFFFFFFF;
        int locked = 0xFF33CC33;
        int border = 0xFF404040;
        return new Theme.Palette(keyboard, key, actionKey, spaceKey, activatedKey,
                label, subLabel, activatedLabel, pressedLabel, locked,
                border, true, 1.0f, 5.0f, 0.97f);
    }

    // -----------------------------------------------------------------------------------
    // Measurement: handle strip on top + keyboard below, self-sizing height.
    // -----------------------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int parentW = MeasureSpec.getSize(widthMeasureSpec);
        float density = getResources().getDisplayMetrics().density;
        int desiredWidth = Math.round(Math.min(parentW * 0.62f, density * 560f));
        desiredWidth = Math.max(desiredWidth, Math.round(density * 280f));
        if (parentW > 0 && desiredWidth > parentW)
            desiredWidth = parentW;

        int handleH = dp(HANDLE_DP);
        int exactW = MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY);
        int atMostH = MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.AT_MOST);

        mKeyboard.measure(exactW, atMostH);
        int keyboardH = mKeyboard.getMeasuredHeight();
        int totalH = handleH + keyboardH;

        setMeasuredDimension(
                resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(totalH, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        int handleH = dp(HANDLE_DP);
        mDragHandle.layout(0, 0, width, handleH);
        mKeyboard.layout(0, handleH, width, height);
    }

    /** The embedded keyboard view, exposed for optional later layout tweaks. */
    public Keyboard2View getKeyboardView() {
        return mKeyboard;
    }

    // -----------------------------------------------------------------------------------
    // Drag: the handle moves the whole host (works identically in either orientation).
    // -----------------------------------------------------------------------------------

    private void attachDrag(final View handle) {
        handle.setOnTouchListener(new OnTouchListener() {
            private float lastRawX;
            private float lastRawY;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastRawX = ev.getRawX();
                        lastRawY = ev.getRawY();
                        bringToFront();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = ev.getRawX() - lastRawX;
                        float dy = ev.getRawY() - lastRawY;
                        setX(getX() + dx);
                        setY(getY() + dy);
                        lastRawX = ev.getRawX();
                        lastRawY = ev.getRawY();
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return true;
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}