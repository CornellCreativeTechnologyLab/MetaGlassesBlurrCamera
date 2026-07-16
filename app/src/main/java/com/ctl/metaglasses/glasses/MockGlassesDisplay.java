package com.ctl.metaglasses.glasses;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.ctl.metaglasses.R;

/**
 * Mock glasses display that simulates rendering on wearable device.
 * Displays large text centered on a dark background to simulate glasses output.
 */
public class MockGlassesDisplay extends FrameLayout {

    private TextView glassesText;

    public MockGlassesDisplay(@NonNull Context context) {
        super(context);
        init();
    }

    public MockGlassesDisplay(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MockGlassesDisplay(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setBackgroundColor(0xFF1a1a1a); // Dark background like glasses
        glassesText = new TextView(getContext());
        glassesText.setTextSize(120);
        glassesText.setTextColor(0xFFFFFFFF); // White text
        glassesText.setTextAlignment(TEXT_ALIGNMENT_CENTER);
        addView(glassesText, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
        ));
    }

    public void displayLetter(String letter) {
        glassesText.setText(letter);
    }

    public void clear() {
        glassesText.setText("");
    }
}
