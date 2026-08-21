package com.pingb.app;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 识别数据转换器
 * 把 PinGB WebView 传来的图纸数据转换为：
 * 1. MARD 色号数组（用于显示/校验）
 * 2. RGB 像素字节流（用于 BLE 发送，模拟 PixDotDot 的 sendImageData 输入）
 */
public final class DataConverter {

    public static final int MAX_BOARD = 104;

    /** 解析结果 */
    public static final class GridData {
        public final int n, m;
        public final int[] colors;    // ARGB 每格颜色
        public final String[] ids;    // MARD 色号
        public final byte[] rgbData;  // RGB 字节流（每格 3 字节，按行序）

        GridData(int n, int m, int[] colors, String[] ids, byte[] rgbData) {
            this.n = n;
            this.m = m;
            this.colors = colors;
            this.ids = ids;
            this.rgbData = rgbData;
        }
    }

    /**
     * 解析 PinGB 桥接数据
     * JSON 格式: {N, M, colorSystem, pixels: [[{key, color}|null,...],...]}
     */
    public static GridData parse(String json) throws Exception {
        // 容错：剥离 UTF-8 BOM 与前导空白（org.json 遇 BOM 会报
        // "A JSONObject text must begin with '{'"）
        if (json != null) {
            int i = 0;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '\uFEFF' || c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    i++;
                } else {
                    break;
                }
            }
            json = json.substring(i);
        }
        JSONObject root = new JSONObject(json);
        int n = root.optInt("N", 0);
        int m = root.optInt("M", 0);
        if (n <= 0 || m <= 0 || n > MAX_BOARD || m > MAX_BOARD) {
            throw new IllegalArgumentException("图纸尺寸无效: " + n + "x" + m);
        }

        JSONArray pixels = root.optJSONArray("pixels");
        int[] colors = new int[n * m];
        String[] ids = new String[n * m];
        byte[] rgb = new byte[n * m * 3];
        int unknown = 0;

        for (int row = 0; row < m; row++) {
            JSONArray rowArr = pixels != null ? pixels.optJSONArray(row) : null;
            for (int col = 0; col < n; col++) {
                int idx = row * n + col;
                JSONObject cell = rowArr != null ? rowArr.optJSONObject(col) : null;
                if (cell == null) {
                    colors[idx] = 0x00000000;
                    ids[idx] = "";
                    rgb[idx * 3] = 0;
                    rgb[idx * 3 + 1] = 0;
                    rgb[idx * 3 + 2] = 0;
                    continue;
                }
                String key = cell.optString("key", "");
                String hex = normalizeHex(cell.optString("color", key));
                if (hex.isEmpty()) {
                    colors[idx] = 0x00000000;
                    ids[idx] = "";
                    continue;
                }
                int argb = parseHex(hex);
                colors[idx] = argb;
                rgb[idx * 3] = (byte) ((argb >> 16) & 0xFF);
                rgb[idx * 3 + 1] = (byte) ((argb >> 8) & 0xFF);
                rgb[idx * 3 + 2] = (byte) (argb & 0xFF);
                String code = MardColorMapping.find(hex);
                if (code == null) {
                    unknown++;
                    code = "";
                }
                ids[idx] = code;
            }
        }
        return new GridData(n, m, colors, ids, rgb);
    }

    private static String normalizeHex(String raw) {
        if (raw == null) return "";
        String h = raw.trim();
        if (h.isEmpty()) return "";
        if (!h.startsWith("#")) h = "#" + h;
        if (h.length() == 4) {
            char r = h.charAt(1), g = h.charAt(2), b = h.charAt(3);
            h = "#" + r + r + g + g + b + b;
        }
        return h.length() == 7 ? h.toUpperCase() : "";
    }

    private static int parseHex(String hex) {
        try {
            return 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
        } catch (Exception e) {
            return 0;
        }
    }
}
