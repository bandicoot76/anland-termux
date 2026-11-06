package com.anland.termux;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A small floating control bar for the Anland Wayland surface.
 *
 * <p>Provides three actions that are otherwise awkward with only the embedded keyboard:</p>
 * <ul>
 *   <li><b>Keyboard</b> — show/hide the embedded floating keyboard,</li>
 *   <li><b>Copy</b> — send the Ctrl+C chord to the focused remote application,</li>
 *   <li><b>Paste</b> — send the Ctrl+V chord to the focused remote application.</li>
 * </ul>
 *
 * <p>The bar is draggable via its left edge handle and floats above the surface in both
 * portrait and landscape. It never touches native methods itself; actions are reported
 * through {@link NavListener}.</p>
 */
public final class AnlandNavBar extends FrameLayout {

    /** Host hook for the three bar actions. Never called on a null view. */
    public interface NavListener {
        void toggleKeyboard();
        void copy();
        void paste();
    }

    private static final int HANDLE_DP = 28;
    private static final int BUTTON_PAD_DP = 14;
    private static final int BUTTON_TEXT_SP = 13;

    private final LinearLayout mRow;
    private final View mDragHandle;

    public AnlandNavBar(Context context, final NavListener listener) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);

        mRow = new LinearLayout(context);
        mRow.setOrientation(LinearLayout.HORIZONTAL);
        mRow.setGravity(Gravity.CENTER_VERTICAL);
        mRow.setBackgroundColor(0xCC1B1B1B);
        mRow.setPadding(dp(6), dp(4), dp(6), dp(4));

        mRow.addView(makeButton(context, "\u2328", listener != null
                ? listener::toggleKeyboard : null));          // ⌨ keyboard
        addSpacer(mRow);
        mRow.addView(makeButton(context, "Copy", listener != null
                ? listener::copy : null));
        addSpacer(mRow);
        mRow.addView(makeButton(context, "Paste", listener != null
                ? listener::paste : null));

        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | Gravity.END);
        addView(mRow, rowLp);

        mDragHandle = new View(context);
        mDragHandle.setBackgroundColor(0x55FFFFFF);
        addView(mDragHandle, new FrameLayout.LayoutParams(dp(HANDLE_DP), dp(44), Gravity.START));
        attachDrag(mDragHandle);
    }

    private void addSpacer(LinearLayout row) {
        TextView s = new TextView(getContext());
        s.setText("  ");
        row.addView(s, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    private TextView makeButton(Context context, String label, final Runnable action) {
        TextView b = new TextView(context);
        b.setText(label);
        b.setTextSize(BUTTON_TEXT_SP);
        b.setTextColor(Color.WHITE);
        b.setTypeface(null, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(BUTTON_PAD_DP), dp(6), dp(BUTTON_PAD_DP), dp(6));
        b.setBackgroundColor(0x00000000);
        if (action != null) {
            b.setOnClickListener(v -> action.run());
        }
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        return b;
    }

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