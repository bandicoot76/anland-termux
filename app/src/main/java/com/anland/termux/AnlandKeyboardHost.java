package com.anland.termux;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import juloo.keyboard2.Config;
import juloo.keyboard2.Keyboard2View;
import juloo.keyboard2.KeyboardData;
import juloo.keyboard2.LayoutModifier;
import juloo.keyboard2.Theme;

/**
 * A floating {@link Keyboard2View} (Unexpected Keyboard) hosted over Anland's Wayland surface.
 *
 * <p>Replaces the former single-purpose virtual keyboard with the full embedded
 * keyboard: docked, floating (drag via the top handle) and, through the keyboard's own
 * gesture, usable with its Fn layer, extra-keys row and QWERTY (US) layout. Key events are
 * translated to evdev scancodes by {@link AnlandKeyboardBridge}.</p>
 */
public final class AnlandKeyboardHost extends FrameLayout {

    private static final String TAG = "AnlandKeyboard";

    private final Keyboard2View mKeyboard;
    private final Config mConfig;

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

        addView(mKeyboard, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Give the keyboard a stable floating width: most of the display's width, capped
        // so it stays reachable in landscape, then let Keyboard2View compute its own height
        // against an AT_MOST spec so the fraction cap in Config applies.
        int parentW = MeasureSpec.getSize(widthMeasureSpec);
        float density = getResources().getDisplayMetrics().density;
        int desiredWidth = Math.round(Math.min(parentW * 0.62f, density * 560f));
        if (desiredWidth <= 0)
            desiredWidth = MeasureSpec.getSize(widthMeasureSpec);
        desiredWidth = Math.max(desiredWidth, Math.round(density * 280f));
        if (parentW > 0 && desiredWidth > parentW)
            desiredWidth = parentW;

        int exactW = MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY);
        int atMostH = MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.AT_MOST);

        // Measure the keyboard child at the chosen width; height follows its layout.
        mKeyboard.measure(exactW, atMostH);
        setMeasuredDimension(
                resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(mKeyboard.getMeasuredHeight(), heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        mKeyboard.layout(0, 0, width, height);
    }

    /** The embedded keyboard view, exposed for optional later layout tweaks. */
    public Keyboard2View getKeyboardView() {
        return mKeyboard;
    }

    /** Loads the bundled QWERTY (US) layout with the standard bottom row. */
    private KeyboardData loadDefaultLayout() {
        KeyboardData data = KeyboardData.load(getResources(), R.xml.latn_qwerty_us);
        if (data == null) {
            throw new IllegalStateException("Bundled latn_qwerty_us layout failed to load");
        }
        // Compose the full keyboard: standard bottom row (Ctrl/Fn/Space/Enter + arrows),
        // no extra number row.
        LayoutModifier.LayoutOptions options = new LayoutModifier.LayoutOptions(true, false, true);
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
        // (keyboardBackground, keyBackground, actionKeyBackground, spaceBarBackground,
        //  activatedKeyBackground, labelColor, subLabelColor, activatedLabelColor,
        //  pressedLabelColor, lockedModifierColor, borderColor, borderEnabled, borderWidth,
        //  borderRadius, opacity)
        return new Theme.Palette(keyboard, key, actionKey, spaceKey, activatedKey,
                label, subLabel, activatedLabel, pressedLabel, locked,
                border, true, 1.0f, 5.0f, 0.97f);
    }

    // -----------------------------------------------------------------------------------
    // Floating drag handle
    // -----------------------------------------------------------------------------------

    private boolean mDragging;
    private float mLastX;
    private float mLastY;

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // The top 24dp acts as a drag handle: it floats/positions the keyboard instead
        // of feeding a key underneath it.
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            int handle = dp(24);
            if (ev.getY() < handle) {
                mDragging = true;
                mLastX = ev.getRawX();
                mLastY = ev.getRawY();
                bringToFront();
                return true;
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mDragging) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - mLastX;
                    float dy = ev.getRawY() - mLastY;
                    setX(getX() + dx);
                    setY(getY() + dy);
                    mLastX = ev.getRawX();
                    mLastY = ev.getRawY();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mDragging = false;
                    return true;
            }
            return true;
        }
        return super.onTouchEvent(ev);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** Creates the layout params used by MainActivity to host this keyboard. */
    public static FrameLayout.LayoutParams hostParams(int widthPx, int heightPx) {
        return new FrameLayout.LayoutParams(widthPx, heightPx, Gravity.NO_GRAVITY);
    }
}