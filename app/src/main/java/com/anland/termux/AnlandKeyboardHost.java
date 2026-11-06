package com.anland.termux;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
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
 * <p>Swiping the space bar cycles three plain Latin-letter templates (Termux QWERTY →
 * QWERTY US → Dvorak). The keyboard docks full-width at the bottom (the desktop is raised
 * above it so nothing is covered) and can be lifted into a floating, resizable position via
 * its top handle.</p>
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
    // Keyboard height as a fraction of the screen height, per orientation.
    private static final float DOCKED_HEIGHT_FRACTION_PORTRAIT = 0.42f;
    private static final float DOCKED_HEIGHT_FRACTION_LANDSCAPE = 0.55f;

    // The three swipe templates, in cycle order: plain Latin-letter layouts.
    private static final List<Integer> TEMPLATES = new ArrayList<>();
    static {
        TEMPLATES.add(R.xml.termux_launcher_qwerty);
        TEMPLATES.add(R.xml.latn_qwerty_us);
        TEMPLATES.add(R.xml.latn_dvorak);
    }

    private final AnlandKeyboardBridge mBridge;
    private Keyboard2View mKeyboard;
    private final View mDragHandle;
    private final View mResizeGrip;
    private final android.widget.TextView mMenuBtn;   // Gboard-style menu, top-left

    private int mTemplateIndex;

    private boolean mFloating;
    private int mOrientation;
    /** User-set floating width in dp; 0 means "auto" (bounded by parent). */
    private int mFloatingWidthDp;

    /** Notified when the keyboard docks/un-docks so the host can resize the surface. */
    public interface HostListener {
        void onDockChanged();
    }

    private HostListener mHostListener;

    public void setHostListener(HostListener listener) {
        mHostListener = listener;
    }

    // Settings reachable from the keyboard's config key, persisted per-device.
    private boolean mNumberRowEnabled;
    private boolean mHapticsEnabled;
    private String mThemeMode = "dark";
    // A user-edited layout string, or null to use the bundled template.
    private String mCustomLayout;

    public AnlandKeyboardHost(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        setVisibility(GONE);

        mBridge = new AnlandKeyboardBridge();
        mOrientation = context.getResources().getConfiguration().orientation;

        mTemplateIndex = 0;
        mFloating = false;
        mFloatingWidthDp = 0;

        SharedPreferences prefs = context.getSharedPreferences("anland_book_keyboard", Context.MODE_PRIVATE);
        mNumberRowEnabled = prefs.getBoolean("number_row", false);
        mHapticsEnabled = prefs.getBoolean("haptics", true);
        mThemeMode = prefs.getString("theme", "dark");
        mCustomLayout = prefs.getString("custom_layout", null);

        buildKeyboardView();

        mDragHandle = new View(context);
        mDragHandle.setBackgroundColor(0x55FFFFFF);

        mResizeGrip = new View(context);
        mResizeGrip.setBackgroundColor(0x88FFFFFF);
        mResizeGrip.setVisibility(View.GONE);

        mMenuBtn = new android.widget.TextView(context);
        mMenuBtn.setText("☰");
        mMenuBtn.setTextColor(0xFFFFFFFF);
        mMenuBtn.setTextSize(14);
        mMenuBtn.setGravity(Gravity.CENTER);
        mMenuBtn.setPadding(dp(8), dp(2), dp(8), dp(2));
        mMenuBtn.setBackgroundColor(0x55000000);
        mMenuBtn.setOnClickListener(v -> showSettingsMenu());

        // buildKeyboardView() already added mKeyboard as the first child.
        addView(mDragHandle, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(HANDLE_DP),
                Gravity.TOP));
        addView(mResizeGrip, new FrameLayout.LayoutParams(
                dp(GRIP_DP), dp(GRIP_DP), Gravity.BOTTOM | Gravity.END));
        addView(mMenuBtn, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));

        attachDrag(mDragHandle);
        attachResize(mResizeGrip);

        mBridge.setLayoutController(new AnlandKeyboardBridge.LayoutController() {
            @Override public void cycleLayout(int delta) {
                int n = TEMPLATES.size();
                if (n == 0) return;
                mTemplateIndex = (mTemplateIndex + delta + n) % n;
                rebuildAndRequestLayout();
                announceLayout();
            }
            @Override public void showNumeric() {
                // No numeric pad in the letter-only swipe ring; ignore.
            }
            @Override public void showText() {
                mTemplateIndex = TEMPLATES.indexOf(R.xml.termux_launcher_qwerty);
                if (mTemplateIndex < 0) mTemplateIndex = 0;
                rebuildAndRequestLayout();
                announceLayout();
            }
            @Override public void hideKeyboard() {
                setVisibility(View.GONE);
            }
            @Override public void openSettings() {
                showSettingsMenu();
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

    /** Cycle to the next template (Termux QWERTY → US → Dvorak → Termux QWERTY). */
    public void switchLayout() {
        int n = TEMPLATES.size();
        if (n == 0) return;
        mTemplateIndex = (mTemplateIndex + 1) % n;
        rebuildAndRequestLayout();
        announceLayout();
    }

    /** Open the keyboard settings popup, for the bottom-right settings control. */
    public void openSettings() {
        showSettingsMenu();
    }

    /** Show a short toast naming the just-selected template, matching termux-launcher's notice. */
    private void announceLayout() {
        Toast.makeText(getContext(), "Layout: " + getLayoutLabel(), Toast.LENGTH_SHORT).show();
    }

    // -----------------------------------------------------------------------------------
    // Layout building
    // -----------------------------------------------------------------------------------

    private void rebuildAndRequestLayout() {
        buildKeyboardView();
        requestLayout();
    }

    private void buildKeyboardView() {
        KeyboardData data;
        if (mCustomLayout != null) {
            // A user-edited layout overrides the bundled template until cleared.
            data = KeyboardData.load_string(mCustomLayout);
        } else {
            int resId = TEMPLATES.get(mTemplateIndex);
            data = loadTextLayout(resId);
        }
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
        b.addNumberRow = mNumberRowEnabled;
        b.hapticEnabled = mHapticsEnabled;
        b.keySoundEnabled = false;
        Config config = b.build();

        Theme.Palette palette = "light".equals(mThemeMode) ? buildLightPalette() : buildDarkPalette();
        Keyboard2View keyboard = new Keyboard2View(getContext(), config, palette);
        keyboard.setKeyboard(data);

        if (mKeyboard != null) {
            int idx = indexOfChild(mKeyboard);
            if (idx >= 0)
                removeViewAt(idx);
        }
        mKeyboard = keyboard;
        addView(mKeyboard, 0, new FrameLayout.LayoutParams(
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

    /** A light palette mirroring the dark one, for the theme toggle in keyboard settings. */
    private static Theme.Palette buildLightPalette() {
        return new Theme.Palette(
                0xFFE3E3E3, 0xFFCCCCCC, 0xFFD9D9D9, 0xFFDEDEDE, 0xFFE3E3E3,
                0xFF000000, 0xFF333333, 0xFF0066CC, 0xFF000000, 0xFF33CC33,
                0xFFAAAAAA, true, 0.6f, 5.0f, 1.0f);
    }

    /** Keyboard settings menu: template cycle, theme, number row, and haptics. */
    private void showSettingsMenu() {
        final PopupWindow popup = new PopupWindow(this);
        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xEE1B1B1B);

        TextView title = new TextView(getContext());
        title.setText("Keyboard");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(16);
        title.setPadding(dp(16), dp(14), dp(16), dp(6));
        panel.addView(title);

        // Clipboard / editing actions, Gboard-style at the top of the menu.
        LinearLayout clip = new LinearLayout(getContext());
        clip.setOrientation(LinearLayout.HORIZONTAL);
        clip.setGravity(Gravity.CENTER);
        clip.addView(menuAction("Cut", () -> { popup.dismiss(); sendClipChord(KeyEvent.KEYCODE_X); }));
        clip.addView(menuAction("Copy", () -> { popup.dismiss(); sendClipChord(KeyEvent.KEYCODE_C); }));
        clip.addView(menuAction("Paste", () -> { popup.dismiss(); sendClipChord(KeyEvent.KEYCODE_V); }));
        clip.addView(menuAction("All", () -> { popup.dismiss(); sendClipChord(KeyEvent.KEYCODE_A); }));
        panel.addView(clip);

        LinearLayout clip2 = new LinearLayout(getContext());
        clip2.setOrientation(LinearLayout.HORIZONTAL);
        clip2.setGravity(Gravity.CENTER);
        clip2.addView(menuAction("Undo", () -> { popup.dismiss(); sendClipChord(KeyEvent.KEYCODE_Z); }));
        clip2.addView(menuAction("Redo", () -> { popup.dismiss(); sendClipChord(KeyEvent.KEYCODE_Y); }));
        panel.addView(clip2);

        panel.addView(makeMenuRow("Layout", getLayoutLabel(), () -> {
            popup.dismiss();
            switchLayout();
        }));
        panel.addView(makeMenuRow("Theme", mThemeMode, () -> {
            popup.dismiss();
            mThemeMode = "dark".equals(mThemeMode) ? "light" : "dark";
            persistSetting("theme", mThemeMode);
            buildKeyboardView();
            requestLayout();
        }));
        panel.addView(makeMenuRow("Number row", mNumberRowEnabled ? "On" : "Off", () -> {
            popup.dismiss();
            mNumberRowEnabled = !mNumberRowEnabled;
            persistSetting("number_row", mNumberRowEnabled);
            buildKeyboardView();
            requestLayout();
        }));
        panel.addView(makeMenuRow("Haptics", mHapticsEnabled ? "On" : "Off", () -> {
            popup.dismiss();
            mHapticsEnabled = !mHapticsEnabled;
            persistSetting("haptics", mHapticsEnabled);
            buildKeyboardView();
            requestLayout();
        }));
        panel.addView(makeMenuRow("Edit layout", mCustomLayout != null ? "Custom" : getLayoutLabel(), () -> {
            popup.dismiss();
            showLayoutEditor();
        }));

        popup.setContentView(panel);
        popup.setWidth(dp(280));
        popup.setHeight(LinearLayout.LayoutParams.WRAP_CONTENT);
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        popup.showAtLocation(this, Gravity.CENTER, 0, -dp(40));
    }

    private View makeMenuRow(String label, String value, Runnable onClick) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView l = new TextView(getContext());
        l.setText(label);
        l.setTextColor(0xFFFFFFFF);
        l.setTextSize(15);
        row.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(getContext());
        v.setText(value);
        v.setTextColor(0xFF999999);
        v.setTextSize(14);
        row.addView(v);

        row.setOnClickListener(clk -> onClick.run());
        return row;
    }

    /** A compact Gboard-style toolbar action button. */
    private TextView menuAction(String label, Runnable onClick) {
        TextView b = new TextView(getContext());
        b.setText(label);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setOnClickListener(clk -> onClick.run());
        return b;
    }

    /** Send a Ctrl+key chord to the compositor (copy/paste/select-all). */
    private void sendClipChord(int androidKeyCode) {
        int key = KeyCodeMapper.getScanCode(androidKeyCode);
        int ctrl = KeyCodeMapper.getScanCode(KeyEvent.KEYCODE_CTRL_LEFT);
        if (key < 0 || ctrl < 0) return;
        Native.nativeSendKey(0, ctrl);
        Native.nativeSendKey(0, key);
        Native.nativeSendKey(1, key);
        Native.nativeSendKey(1, ctrl);
    }

    private String getLayoutLabel() {
        if (mCustomLayout != null) return "Custom";
        int resId = TEMPLATES.get(mTemplateIndex);
        if (resId == R.xml.termux_launcher_qwerty) return "QWERTY (Termux Launcher)";
        if (resId == R.xml.latn_qwerty_us) return "QWERTY (US)";
        if (resId == R.xml.latn_dvorak) return "Dvorak";
        return "Layout";
    }

    /** Read the active layout's XML source (the custom string or the bundled asset copy). */
    private String currentLayoutXml() {
        if (mCustomLayout != null)
            return mCustomLayout;
        int resId = TEMPLATES.get(mTemplateIndex);
        String asset;
        if (resId == R.xml.termux_launcher_qwerty) asset = "keyboard/termux_launcher_qwerty.xml";
        else if (resId == R.xml.latn_qwerty_us) asset = "keyboard/latn_qwerty_us.xml";
        else if (resId == R.xml.latn_dvorak) asset = "keyboard/latn_dvorak.xml";
        else asset = "keyboard/termux_launcher_qwerty.xml";
        try {
            java.io.InputStream in = getContext().getAssets().open(asset);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** A text dialog to edit the keyboard layout XML; Apply parses and hot-swaps it. */
    private void showLayoutEditor() {
        final android.widget.EditText editor = new android.widget.EditText(getContext());
        editor.setText(currentLayoutXml());
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTextColor(0xFFFFFFFF);
        editor.setBackgroundColor(0xFF101010);
        editor.setPadding(dp(12), dp(12), dp(12), dp(12));
        editor.setHorizontallyScrolling(false);

        android.widget.ScrollView scroll = new android.widget.ScrollView(getContext());
        scroll.addView(editor, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        new android.app.AlertDialog.Builder(getContext())
                .setTitle("Edit layout (XML)")
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("Reset", (d, w) -> {
                    mCustomLayout = null;
                    persistSetting("custom_layout", (String) null);
                    rebuildAndRequestLayout();
                    announceLayout();
                })
                .setPositiveButton("Apply", (d, w) -> {
                    String src = editor.getText().toString();
                    KeyboardData parsed = KeyboardData.load_string(src);
                    if (parsed == null) {
                        Toast.makeText(getContext(), "Layout parse failed", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    mCustomLayout = src;
                    persistSetting("custom_layout", src);
                    rebuildAndRequestLayout();
                    announceLayout();
                })
                .show();
    }

    private void persistSetting(String key, boolean value) {
        getContext().getSharedPreferences("anland_book_keyboard", Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).apply();
    }

    private void persistSetting(String key, String value) {
        getContext().getSharedPreferences("anland_book_keyboard", Context.MODE_PRIVATE)
                .edit().putString(key, value).apply();
    }

    // -----------------------------------------------------------------------------------
    // Orientation-aware measurement + docked/floating layout
    // -----------------------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int orient = getResources().getConfiguration().orientation;
        if (orient != mOrientation) {
            mOrientation = orient;
            buildKeyboardView();
        }

        int parentW = MeasureSpec.getSize(widthMeasureSpec);
        int desiredWidth;
        if (mFloating) {
            if (mFloatingWidthDp > 0) {
                desiredWidth = dp(mFloatingWidthDp);
            } else {
                desiredWidth = Math.round(Math.min(parentW * 0.62f, dp(560)));
            }
            desiredWidth = Math.max(desiredWidth, dp(MIN_FLOATING_WIDTH_DP));
        } else {
            desiredWidth = parentW;
        }
        if (parentW > 0 && desiredWidth > parentW)
            desiredWidth = parentW;

        int handleH = dp(HANDLE_DP);
        int exactW = MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY);
        int atMostH = MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.AT_MOST);

        mDragHandle.measure(exactW, MeasureSpec.makeMeasureSpec(handleH, MeasureSpec.EXACTLY));
        mKeyboard.measure(exactW, atMostH);
        mMenuBtn.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));

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

        int menuW = mMenuBtn.getMeasuredWidth();
        int menuH = mMenuBtn.getMeasuredHeight();
        // Place in the top drag-handle strip (not over the key rows) so it never covers keys.
        mMenuBtn.layout(dp(2), dp(2), dp(2) + menuW, dp(2) + menuH);

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
        if (mHostListener != null)
            mHostListener.onDockChanged();
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