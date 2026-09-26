package com.zifang.z.skill.core.discover;

import java.io.IOException;

/**
 * 远端注册表的取数口子 — 单独抽出来是为了测试里能塞桩, 不把单测挂在网络上.
 */
public interface HttpFetcher {

    /**
     * @return 响应体文本
     * @throws IOException 网络/状态码失败
     */
    String get(String url) throws IOException;

    class Jdk implements HttpFetcher {

        private final int timeoutMs;

        public Jdk() {
            this(8000);
        }

        public Jdk(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        @Override
        public String get(String url) throws IOException {
            java.net.HttpURLConnection conn = null;
            try {
                java.net.URL u = new java.net.URL(url);
                conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                conn.setRequestProperty("Accept", "application/json");
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP " + code + " for " + url);
                }
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                java.io.InputStream in = conn.getInputStream();
                try {
                    byte[] chunk = new byte[8192];
                    int n;
                    long total = 0;
                    while ((n = in.read(chunk)) > 0) {
                        total += n;
                        if (total > 8L * 1024 * 1024) throw new IOException("响应体超过 8MB 上限");
                        buf.write(chunk, 0, n);
                    }
                } finally {
                    in.close();
                }
                return new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }
}
