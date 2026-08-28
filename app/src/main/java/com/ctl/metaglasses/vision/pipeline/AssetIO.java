package com.ctl.metaglasses.vision.pipeline;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

final class AssetIO {
    private AssetIO() {}

    static byte[] readAsset(Context context, String assetPath) throws Exception {
        try (InputStream in = context.getAssets().open(assetPath);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
}
