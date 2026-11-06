package com.anland.termux;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

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
    private Keyboard2View mKeyboard;
    private final View mDragHandle;
    private final View mResizeGrip;

    // Full catalogue of selectable text layouts (resource id + display label). Referencing
    // every R.xml.* here also keeps the resource shrinker (R8) from stripping them from the APK.
    private final List<LayoutEntry> mLayoutCatalog = new ArrayList<>();
    private final List<Integer> mLayoutRing = new ArrayList<>();
    private int mRingIndex;
    private boolean mModalNumeric;

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

    // Settings currently reachable from the keyboard's config key, persisted per-device.
    private boolean mNumberRowEnabled;
    private boolean mHapticsEnabled;
    private String mThemeMode = "dark";

    /** Immutable catalogue entry: a layout resource id and its user-facing label. */
    private static final class LayoutEntry {
        final int resId;
        final String label;

        LayoutEntry(int resId, String label) {
            this.resId = resId;
            this.label = label;
        }
    }

    private void addLayout(int resId, String label) {
        mLayoutCatalog.add(new LayoutEntry(resId, label));
    }

    public AnlandKeyboardHost(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        setVisibility(GONE);

        mBridge = new AnlandKeyboardBridge();
        mOrientation = context.getResources().getConfiguration().orientation;

        mLayoutCatalog.clear();
        addLayout(R.xml.termux_launcher_qwerty, "QWERTY (Termux Launcher)");
        addLayout(R.xml.latn_qwerty_us, "QWERTY (US)");
        addLayout(R.xml.latn_colemak, "Colemak");
        addLayout(R.xml.latn_dvorak, "Dvorak");
        addLayout(R.xml.arab_alt, "Arabic Alt");
        addLayout(R.xml.arab_pc, "Arabic PC");
        addLayout(R.xml.arab_pc_hindu, "Arabic PC (Hindu numerals)");
        addLayout(R.xml.latn_azerty_be, "AZERTY (Belgian)");
        addLayout(R.xml.latn_azerty_fr, "AZERTY (Français)");
        addLayout(R.xml.latn_bepo_fr, "BEPO (Français)");
        addLayout(R.xml.latn_bone, "Bone");
        addLayout(R.xml.arab_pc_ckb_fa, "Central Kurdish (سۆرانی) Persian Layout");
        addLayout(R.xml.hebr_1_il, "Hebrew 1");
        addLayout(R.xml.hebr_2_il, "Hebrew 2");
        addLayout(R.xml.arab_pc_ckb, "Kurdish (کوردی) QWERTY");
        addLayout(R.xml.latn_neo2, "Neo 2");
        addLayout(R.xml.cyrl_yxukeng_os, "Old Church Slavonic (Црькъвьнословѣньскъ ѩзыкъ)");
        addLayout(R.xml.arab_pc_ir, "Persian PC");
        addLayout(R.xml.latn_qwerty_apl, "QWERTY (APL)");
        addLayout(R.xml.latn_qwerty_az, "QWERTY (Azərbaycanca)");
        addLayout(R.xml.latn_qwerty_bqn, "QWERTY (BQN)");
        addLayout(R.xml.latn_qwerty_br, "QWERTY (Brasileiro)");
        addLayout(R.xml.latn_qwerty_cz, "QWERTY (Czech)");
        addLayout(R.xml.latn_qwerty_da, "QWERTY (Danish)");
        addLayout(R.xml.latn_qwerty_et, "QWERTY (eesti)");
        addLayout(R.xml.latn_qwerty_es, "QWERTY (Español)");
        addLayout(R.xml.latn_qwerty_fi, "QWERTY (Finnish)");
        addLayout(R.xml.grek_qwerty, "QWERTY (Greek)");
        addLayout(R.xml.latn_qwerty_haw, "QWERTY (Hawaiian)");
        addLayout(R.xml.latn_qwerty_ga, "QWERTY (Irish)");
        addLayout(R.xml.latn_qwerty_jp, "QWERTY (Japan)");
        addLayout(R.xml.latn_qwerty_lv, "QWERTY (Latvian)");
        addLayout(R.xml.latn_qwerty_lt, "QWERTY (Lietuviškai)");
        addLayout(R.xml.latn_qwerty_hu, "QWERTY (Magyar)");
        addLayout(R.xml.latn_qwerty_mt, "QWERTY (Malti)");
        addLayout(R.xml.latn_qwerty_no, "QWERTY (Norwegian)");
        addLayout(R.xml.latn_qwerty_uz, "QWERTY (Oʻzbekcha)");
        addLayout(R.xml.latn_qwerty_pl, "QWERTY (Polski)");
        addLayout(R.xml.latn_qwerty_kk, "QWERTY (Qazaqşa)");
        addLayout(R.xml.latn_qwerty_ro, "QWERTY (Română)");
        addLayout(R.xml.latn_qwerty_sk, "QWERTY (Slovak)");
        addLayout(R.xml.latn_qwerty_sr, "QWERTY (Srpski, latinica)");
        addLayout(R.xml.latn_qwerty_se, "QWERTY (Swedish)");
        addLayout(R.xml.latn_qwerty_tly, "QWERTY (Talysh New Latin)");
        addLayout(R.xml.latn_qwerty_tr, "QWERTY (Türkçe)");
        addLayout(R.xml.latn_qwerty_gb, "QWERTY (UK)");
        addLayout(R.xml.latn_qwerty_vi, "QWERTY (Vietnamese)");
        addLayout(R.xml.latn_qwerty_cy, "QWERTY (Welsh)");
        addLayout(R.xml.latn_qwerty_is, "QWERTY (Íslenska)");
        addLayout(R.xml.latn_qwertz, "QWERTZ");
        addLayout(R.xml.latn_qwertz_sq, "QWERTZ (Albanian)");
        addLayout(R.xml.latn_qwertz_cz_diacritics, "QWERTZ (Czech with diacritic keys)");
        addLayout(R.xml.latn_qwertz_cz, "QWERTZ (Czech)");
        addLayout(R.xml.latn_qwertz_de, "QWERTZ (Deutsch)");
        addLayout(R.xml.latn_qwertz_hu, "QWERTZ (Magyar)");
        addLayout(R.xml.latn_qwertz_sk, "QWERTZ (Slovak)");
        addLayout(R.xml.latn_qwertz_fr_ch, "QWERTZ (Swiss French)");
        addLayout(R.xml.latn_qwertz_cz_multifunctional, "QWERTZ Multifunctional (Czech)");
        addLayout(R.xml.latn_qzerty_it, "QZERTY (Italiano)");
        addLayout(R.xml.shaw_imperial_en, "Shaw Imperial");
        addLayout(R.xml.cyrl_yqukeng_tj, "Tajiki Persian (Тоҷикӣ)");
        addLayout(R.xml.arab_hamvaj_tly, "Talysh (تالشی همواج)");
        addLayout(R.xml.latin_kbdtuf_tr, "Turkish F");
        addLayout(R.xml.urdu_phonetic_ur, "Urdu Phonetic");
        addLayout(R.xml.latn_workman_us, "WORKMAN (US)");
        addLayout(R.xml.cyrl_jcuken_ru, "ЙЦУКЕН (Русский)");
        addLayout(R.xml.cyrl_jcuken_uk, "ЙЦУКЕН (Українська)");
        addLayout(R.xml.cyrl_jcuken_kk, "ЙЦУКЕН (Қазақша)");
        addLayout(R.xml.cyrl_jiuken, "ЙІУКЕН");
        addLayout(R.xml.cyrl_ueishsht, "УЕИШЩ (Български, БДС)");
        addLayout(R.xml.cyrl_fcuzhen_mn, "ФЦУЖЭН (Монгол)");
        addLayout(R.xml.cyrl_yaverti, "ЯВЕРТЪ");
        addLayout(R.xml.cyrl_yawerty, "ЯВЕРТЫ");
        addLayout(R.xml.cyrl_jcuken_as, "ЈЦУКЕН (Всисловѣнск)");
        addLayout(R.xml.cyrl_lynyertz_sr, "ЉЊЕРТЗ (Српски)");
        addLayout(R.xml.cyrl_lynyertdz_mk, "ЉЊЕРТЅ (Македонски)");
        addLayout(R.xml.armn_cpbsmo, "ՃՓԲՍՄՈ");
        addLayout(R.xml.armn_kvertc, "ՔՎԸՐՏՃ");
        addLayout(R.xml.deva_inscript, "देवनागरी (हिंदी)-1");
        addLayout(R.xml.deva_alt, "देवनागरी (हिंदी)-2");
        addLayout(R.xml.deva_phonetic_in, "हिन्दी फोनेटिक - Hindi Phonetic");
        addLayout(R.xml.beng_assamese, "অসমীয়া (Assamese)");
        addLayout(R.xml.beng_national, "বাংলা (জাতীয়)");
        addLayout(R.xml.beng_provat, "বাংলা (প্রভাত)");
        addLayout(R.xml.guj_phonetic_in, "ગુજરાતી ફોનેટિક - Gujarati Phonetic");
        addLayout(R.xml.tamil_default, "தமிழ்");
        addLayout(R.xml.kann_kannada, "ಕನ್ನಡ - Kannada");
        addLayout(R.xml.sinhala_phonetic, "සිංහල");
        addLayout(R.xml.georgian_mes, "ქართული (MES)");
        addLayout(R.xml.georgian_qwerty, "ქართული (QWERTY)");
        addLayout(R.xml.hang_dubeolsik_kr, "두벌식 (Korean)");

        // Default ring: the launcher layout, then the three most common alternatives.
        mLayoutRing.clear();
        Collections.addAll(mLayoutRing,
            R.xml.termux_launcher_qwerty,
            R.xml.latn_qwerty_us,
            R.xml.latn_colemak,
            R.xml.latn_dvorak);
        mRingIndex = 0;
        mModalNumeric = false;
        mFloating = false;
        mFloatingWidthDp = 0;

        SharedPreferences prefs = context.getSharedPreferences("anland_book_keyboard", Context.MODE_PRIVATE);
        mNumberRowEnabled = prefs.getBoolean("number_row", false);
        mHapticsEnabled = prefs.getBoolean("haptics", true);
        mThemeMode = prefs.getString("theme", "dark");

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
                announceLayout();
            }
            @Override public void showNumeric() {
                if (mModalNumeric) return;
                mModalNumeric = true;
                rebuildAndRequestLayout();
                announceLayout();
            }
            @Override public void showText() {
                if (!mModalNumeric) return;
                mModalNumeric = false;
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

    /** Cycle to the next text layout, for the always-visible switch-layout control. */
    public void switchLayout() {
        mModalNumeric = false;
        int n = mLayoutRing.size();
        if (n == 0) return;
        mRingIndex = (mRingIndex + 1) % n;
        buildKeyboardView();
        requestLayout();
        announceLayout();
    }

    /** Show a short toast naming the just-selected layout, matching termux-launcher's notice. */
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
        b.addNumberRow = mNumberRowEnabled;
        b.hapticEnabled = mHapticsEnabled;
        b.keySoundEnabled = false;
        Config config = b.build();

        Theme.Palette palette = "light".equals(mThemeMode) ? buildLightPalette() : buildDarkPalette();
        Keyboard2View keyboard = new Keyboard2View(getContext(), config, palette);
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

    /** A light palette mirroring the dark one, for the theme toggle in keyboard settings. */
    private static Theme.Palette buildLightPalette() {
        return new Theme.Palette(
                0xFFE3E3E3, 0xFFCCCCCC, 0xFFD9D9D9, 0xFFDEDEDE, 0xFFE3E3E3,
                0xFF000000, 0xFF333333, 0xFF0066CC, 0xFF000000, 0xFF33CC33,
                0xFFAAAAAA, true, 0.6f, 5.0f, 1.0f);
    }

    /**
     * Keyboard settings menu, mirroring the subset of termux-launcher's keyboard
     * preferences that apply to Anland: layout, theme, number row, and haptics.
     */
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

        panel.addView(makeMenuRow("Layout", getLayoutLabel(), () -> {
            popup.dismiss();
            showLayoutPicker();
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

        popup.setContentView(panel);
        popup.setWidth(dp(280));
        popup.setHeight(LinearLayout.LayoutParams.WRAP_CONTENT);
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        // Anchor near the top of the keyboard, centred.
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

    /** A scrollable list of every selectable layout, so settings can switch layouts anywhere. */
    private void showLayoutPicker() {
        final PopupWindow popup = new PopupWindow(this);
        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF01B1B1B);

        TextView title = new TextView(getContext());
        title.setText("Choose layout");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(16);
        title.setPadding(dp(16), dp(14), dp(16), dp(6));
        panel.addView(title);

        for (LayoutEntry entry : mLayoutCatalog) {
            final int resId = entry.resId;
            boolean selected = !mModalNumeric && mLayoutRing.get(mRingIndex) == resId;
            String mark = selected ? " \u2713" : "";
            panel.addView(makeMenuRow(entry.label, mark, () -> {
                popup.dismiss();
                selectLayout(resId);
            }));
        }

        // Wrap in a scroll container so all layouts fit on any screen.
        android.widget.ScrollView scroll = new android.widget.ScrollView(getContext());
        scroll.addView(panel);

        popup.setContentView(scroll);
        popup.setWidth(dp(320));
        popup.setHeight(dp(480));
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        popup.showAtLocation(this, Gravity.CENTER, 0, 0);
    }

    private String getLayoutLabel() {
        if (mModalNumeric)
            return "Numeric";
        int resId = mLayoutRing.get(mRingIndex);
        return labelFor(resId);
    }

    private String labelFor(int resId) {
        for (LayoutEntry e : mLayoutCatalog)
            if (e.resId == resId)
                return e.label;
        return "Custom";
    }

    /** Select a specific text layout (used by the settings layout picker). */
    private void selectLayout(int resId) {
        mModalNumeric = false;
        int idx = mLayoutRing.indexOf(resId);
        if (idx < 0)
            idx = 0;
        mRingIndex = idx;
        rebuildAndRequestLayout();
        announceLayout();
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