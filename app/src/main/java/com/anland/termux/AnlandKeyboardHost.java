package com.anland.termux;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import juloo.keyboard2.Config;
import juloo.keyboard2.KeyValue;
import juloo.keyboard2.Keyboard2View;
import juloo.keyboard2.KeyboardData;
import juloo.keyboard2.LayoutModifier;
import juloo.keyboard2.Theme;

/**
 * The embedded {@link Keyboard2View} (Unexpected Keyboard) hosted over Anland's Wayland surface.
 *
 * <p>Behaves like the termux-launcher keyboard in two modes:</p>
 * <ul>
 *   <li><b>Docked</b> (default): full-width at the bottom. The space bar / Ctrl-key left-right
 *       swipes <b>cycle the layout ring</b> (terminal QWERTY → US QWERTY → Colemak → Dvorak),
 *       with a numeric pad reached from the Ctrl key.</li>
 *   <li><b>Floating</b>: lift the keyboard by its top handle. In this mode a <b>resize grip</b>
 *       on the bottom-right corner lets the user resize the floating keyboard freely; the height
 *       follows the keyboard's own aspect ratio. Releasing it near the bottom re-docks it.</li>
 * </ul>
 *
 * <p>The {@link Config} is rebuilt on orientation change so landscape sizes its rows for
 * landscape rather than keeping the portrait row height.</p>
 *
 * <p>Key events are translated to evdev scancodes by {@link AnlandKeyboardBridge}.</p>
 */
public final class AnlandKeyboardHost extends FrameLayout {

    private static final String TAG = "AnlandKeyboard";
    private static final int HANDLE_DP = 28;
    private static final int GRIP_DP = 30;
    private static final float DOCK_RELEASE_FRACTION = 0.15f;
    private static final float LIFT_THRESHOLD_DP = 12f;
    private static final int MIN_FLOATING_WIDTH_DP = 240;
    private static final int MAX_FLOATING_WIDTH_DP = 640;
    // Keyboard height as a fraction of the screen height, per orientation. Portrait keeps
    // roughly the launcher's 42% share; landscape (a short and wide screen) gets more so the
    // rows stay tall enough to hit, tuned for a 1080x2400 class device like the ROG Phone 8.
    private static final float DOCKED_HEIGHT_FRACTION_PORTRAIT = 0.42f;
    private static final float DOCKED_HEIGHT_FRACTION_LANDSCAPE = 0.55f;

    private final AnlandKeyboardBridge mBridge;
    private final Theme.Palette mPalette;
    private Keyboard2View mKeyboard;
    private final View mDragHandle;
    private final View mResizeGrip;

    private final List<Integer> mLayoutRing;
    private int mRingIndex;
    private boolean mModalNumeric;

    private boolean mFloating;
    private int mOrientation;
    /** User-set floating width in dp; 0 means "auto" (bounded by parent). */
    private int mFloatingWidthDp;

    public AnlandKeyboardHost(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        setVisibility(GONE);

        mBridge = new AnlandKeyboardBridge();
        mPalette = buildDarkPalette();
        mOrientation = context.getResources().getConfiguration().orientation;

        mLayoutRing = new ArrayList<>();
        Collections.addAll(mLayoutRing,
            R.xml.termux_launcher_qwerty,
            R.xml.latn_qwerty_us,
            R.xml.latn_colemak,
            R.xml.latn_dvorak);
        mRingIndex = 0;
        mModalNumeric = false;
        mFloating = false;
        mFloatingWidthDp = 0;

        buildKeyboardView();

        mDragHandle = new View(context);
        mDragHandle.setBackgroundColor(0x55FFFFFF);

        mResizeGrip = new View(context);
        mResizeGrip.setBackgroundColor(0x88FFFFFF);
        mResizeGrip.setVisibility(View.GONE);

        // buildKeyboardView() already added mKeyboard as the first child.
        addView(mDragHandle, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(HANDLE_DP),
                Gravity.TOP));
        addView(mResizeGrip, new FrameLayout.LayoutParams(
                dp(GRIP_DP), dp(GRIP_DP), Gravity.BOTTOM | Gravity.END));

        attachDrag(mDragHandle);
        attachResize(mResizeGrip);

        mBridge.setLayoutController(new AnlandKeyboardBridge.LayoutController() {
            @Override public void cycleLayout(int delta) {
                mModalNumeric = false;
                int n = mLayoutRing.size();
                if (n == 0) return;
                mRingIndex = (mRingIndex + delta + n) % n;
                rebuildAndRequestLayout();
            }
            @Override public void showNumeric() {
                if (mModalNumeric) return;
                mModalNumeric = true;
                rebuildAndRequestLayout();
            }
            @Override public void showText() {
                if (!mModalNumeric) return;
                mModalNumeric = false;
                rebuildAndRequestLayout();
            }
            @Override public void hideKeyboard() {
                setVisibility(View.GONE);
            }
        });
    }

    /** The embedded keyboard view, exposed for optional later layout tweaks. */
    public Keyboard2View getKeyboardView() {
        return mKeyboard;
    }

    /** Whether the keyboard is lifted off the dock (resizable) rather than docked at the bottom. */
    public boolean isFloating() {
        return mFloating;
    }

    /** Cycle to the next text layout, for the always-visible switch-layout control. */
    public void switchLayout() {
        mModalNumeric = false;
        int n = mLayoutRing.size();
        if (n == 0) return;
        mRingIndex = (mRingIndex + 1) % n;
        buildKeyboardView();
        requestLayout();
    }

    // -----------------------------------------------------------------------------------
    // Layout building
    // -----------------------------------------------------------------------------------

    private void rebuildAndRequestLayout() {
        buildKeyboardView();
        requestLayout();
    }

    private void buildKeyboardView() {
        KeyboardData data = mModalNumeric
                ? KeyboardData.load(getResources(), R.xml.numeric)
                : loadTextLayout(mLayoutRing.get(mRingIndex));
        if (data == null)
            data = KeyboardData.load(getResources(), R.xml.termux_launcher_qwerty);
        if (data == null)
            throw new IllegalStateException("No keyboard layout could be loaded");

        // Build the config with a height fraction per orientation so the keyboard is sized
        // to the screen (portrait vs landscape) rather than using a fixed row height.
        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        Config.Builder b = new Config.Builder(getResources(), mBridge);
        b.maxKeyboardHeightFraction = landscape
                ? DOCKED_HEIGHT_FRACTION_LANDSCAPE
                : DOCKED_HEIGHT_FRACTION_PORTRAIT;
        Config config = b.build();

        Keyboard2View keyboard = new Keyboard2View(getContext(), config, mPalette);
        keyboard.setKeyboard(data);

        int insertAt = 0;
        if (mKeyboard != null) {
            int idx = indexOfChild(mKeyboard);
            if (idx >= 0)
                removeViewAt(idx);
            insertAt = 0;
        }
        mKeyboard = keyboard;
        addView(mKeyboard, insertAt, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM));
    }

    private KeyboardData loadTextLayout(int resId) {
        KeyboardData data = KeyboardData.load(getResources(), resId);
        if (data == null)
            return null;
        Map<KeyValue, KeyboardData.PreferredPos> extraKeys = new HashMap<>();
        String[] names = {"esc", "tab", "capslock", "alt", "meta", "compose"};
        for (String name : names) {
            KeyValue kv = KeyValue.getKeyByName(name);
            if (kv != null)
                extraKeys.put(kv, KeyboardData.PreferredPos.DEFAULT);
        }
        LayoutModifier.LayoutOptions options =
                new LayoutModifier.LayoutOptions(false, false, true, extraKeys);
        return LayoutModifier.modify(data, options, getResources());
    }

    /** A neutral dark material palette that matches Anland's HUD colour language. */
    private static Theme.Palette buildDarkPalette() {
        return new Theme.Palette(
                0xFF1B1B1B, 0xFF333333, 0xFF262626, 0xFF2B2B2B, 0xFF1B1B1B,
                0xFFFFFFFF, 0xFFCCCCCC, 0xFF3399FF, 0xFFFFFFFF, 0xFF33CC33,
                0xFF404040, true, 1.0f, 5.0f, 0.97f);
    }

    // -----------------------------------------------------------------------------------
    // Orientation-aware measurement + docked/floating layout
    // -----------------------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int orient = getResources().getConfiguration().orientation;
        if (orient != mOrientation) {
            mOrientation = orient;
            // Rebuild Config so row height follows the new orientation.
            buildKeyboardView();
        }

        int parentW = MeasureSpec.getSize(widthMeasureSpec);
        int desiredWidth;
        if (mFloating) {
            if (mFloatingWidthDp > 0) {
                desiredWidth = dp(mFloatingWidthDp);
            } else {
                // Initial floating size: a bounded share of the width — never the docked
                // full width, and independent of the portrait/landscape row height.
                desiredWidth = Math.round(Math.min(parentW * 0.62f, dp(560)));
            }
            desiredWidth = Math.max(desiredWidth, dp(MIN_FLOATING_WIDTH_DP));
        } else {
            desiredWidth = parentW; // docked = full width
        }
        if (parentW > 0 && desiredWidth > parentW)
            desiredWidth = parentW;

        int handleH = dp(HANDLE_DP);
        int exactW = MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY);
        int atMostH = MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.AT_MOST);

        mDragHandle.measure(exactW, MeasureSpec.makeMeasureSpec(handleH, MeasureSpec.EXACTLY));
        mKeyboard.measure(exactW, atMostH);

        int totalH = handleH + mKeyboard.getMeasuredHeight();
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

        int grip = dp(GRIP_DP);
        mResizeGrip.layout(width - grip, height - grip, width, height);
    }

    // -----------------------------------------------------------------------------------
    // Drag (lift float / re-dock) + Resize grip
    // -----------------------------------------------------------------------------------

    private void attachDrag(final View handle) {
        handle.setOnTouchListener(new OnTouchListener() {
            private float startRawX, startRawY;
            private float dockX, dockY;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawX = ev.getRawX();
                        startRawY = ev.getRawY();
                        dockX = getX();
                        dockY = getY();
                        bringToFront();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = ev.getRawX() - startRawX;
                        float dy = ev.getRawY() - startRawY;
                        if (!mFloating && Math.hypot(dx, dy) > dp(LIFT_THRESHOLD_DP)) {
                            setFloating(true);
                        }
                        if (mFloating) {
                            setX(dockX + dx);
                            setY(dockY + dy);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        if (!mFloating) return true;
                        View parent = (View) getParent();
                        float bottomY = (parent != null) ? parent.getHeight() : 0f;
                        boolean nearBottom = bottomY > 0
                                && (getY() + getHeight()) > bottomY * (1.0f - DOCK_RELEASE_FRACTION);
                        if (nearBottom)
                            dock();
                        else
                            clampFloatingPosition();
                        return true;
                    }
                }
                return true;
            }
        });
    }

    private void attachResize(final View grip) {
        grip.setOnTouchListener(new OnTouchListener() {
            private float startRawX;
            private int startWidthPx;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawX = ev.getRawX();
                        startWidthPx = getWidth();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (!mFloating) return true;
                        float dx = ev.getRawX() - startRawX;
                        int newWpx = startWidthPx + Math.round(dx);
                        float density = getResources().getDisplayMetrics().density;
                        int newWdp = Math.round(newWpx / density);
                        newWdp = Math.max(MIN_FLOATING_WIDTH_DP,
                                Math.min(MAX_FLOATING_WIDTH_DP, newWdp));
                        mFloatingWidthDp = newWdp;
                        // Re-layout at the new width; height follows the keyboard ratio.
                        requestLayout();
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

    private void setFloating(boolean floating) {
        if (mFloating == floating)
            return;
        mFloating = floating;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        if (floating) {
            lp.gravity = Gravity.NO_GRAVITY;
            lp.width = FrameLayout.LayoutParams.WRAP_CONTENT;
            lp.height = FrameLayout.LayoutParams.WRAP_CONTENT;
        } else {
            lp.gravity = Gravity.BOTTOM;
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.height = FrameLayout.LayoutParams.WRAP_CONTENT;
        }
        setLayoutParams(lp);
        mResizeGrip.setVisibility(floating ? View.VISIBLE : View.GONE);
    }

    private void clampFloatingPosition() {
        View parent = (View) getParent();
        if (parent == null) return;
        float x = Math.max(0, Math.min(getX(), parent.getWidth() - getWidth()));
        float y = Math.max(0, Math.min(getY(), parent.getHeight() - getHeight()));
        setX(x);
        setY(y);
    }

    /** Re-applies docked positioning: full-width at the bottom, clearing any free offset. */
    private void dock() {
        setFloating(false);
        setX(0f);
        setY(0f);
        setTranslationX(0f);
        setTranslationY(0f);
        View parent = (View) getParent();
        if (parent != null)
            parent.requestLayout();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}