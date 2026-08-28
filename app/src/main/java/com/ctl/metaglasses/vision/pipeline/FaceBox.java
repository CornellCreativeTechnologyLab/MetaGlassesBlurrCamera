package com.ctl.metaglasses.vision.pipeline;

import android.graphics.Rect;

/** Immutable face box in source-frame pixel coordinates. */
public final class FaceBox {
    public final int left;
    public final int top;
    public final int right;
    public final int bottom;

    public FaceBox(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public static FaceBox fromRect(Rect rect) {
        return new FaceBox(rect.left, rect.top, rect.right, rect.bottom);
    }

    public int width() {
        return Math.max(0, right - left);
    }

    public int height() {
        return Math.max(0, bottom - top);
    }

    public int area() {
        return width() * height();
    }

    public Rect toRect() {
        return new Rect(left, top, right, bottom);
    }

    public FaceBox clamp(int width, int height) {
        int l = Math.max(0, Math.min(left, width - 1));
        int t = Math.max(0, Math.min(top, height - 1));
        int r = Math.max(l + 1, Math.min(right, width));
        int b = Math.max(t + 1, Math.min(bottom, height));
        return new FaceBox(l, t, r, b);
    }

    public FaceBox pad(float fraction, int frameWidth, int frameHeight) {
        int padX = Math.round(width() * fraction);
        int padY = Math.round(height() * fraction);
        return new FaceBox(left - padX, top - padY, right + padX, bottom + padY)
                .clamp(frameWidth, frameHeight);
    }

    public float iou(FaceBox other) {
        int l = Math.max(left, other.left);
        int t = Math.max(top, other.top);
        int r = Math.min(right, other.right);
        int b = Math.min(bottom, other.bottom);
        int intersection = Math.max(0, r - l) * Math.max(0, b - t);
        int union = area() + other.area() - intersection;
        return union <= 0 ? 0f : intersection / (float) union;
    }
}
