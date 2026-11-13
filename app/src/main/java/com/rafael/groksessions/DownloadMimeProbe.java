package com.rafael.groksessions;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Reads at most 64 bytes when a server labels a video as application/octet-stream. */
final class DownloadMimeProbe {
    private DownloadMimeProbe() {}

    static String inspect(String url, String userAgent, String cookies, String fallback) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            // Never forward the profile's cookie header to a redirected host.
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setRequestProperty("Range", "bytes=0-63");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (userAgent != null && !userAgent.isEmpty()) connection.setRequestProperty("User-Agent", header(userAgent));
            if (cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", header(cookies));
            int status = connection.getResponseCode();
            if (status != 200 && status != 206) return fallback;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream prefix = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[64];
                int count;
                while (prefix.size() < 64 && (count = input.read(buffer, 0, 64 - prefix.size())) != -1)
                    prefix.write(buffer, 0, count);
                String declared = DownloadFileNames.mediaType(connection.getContentType(), url);
                if (declared.equals("application/octet-stream") || declared.equals("binary/octet-stream")) declared = fallback;
                return DownloadFileNames.sniff(prefix.toByteArray(), declared);
            }
        } catch (Exception ignored) {
            return fallback;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String header(String value) { return value.replace("\r", "").replace("\n", ""); }
}
