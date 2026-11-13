package com.rafael.groksessions;

import android.webkit.URLUtil;

import java.util.Locale;

final class DownloadFileNames {
    private DownloadFileNames() {}

    static String sniff(byte[] bytes, String fallback) {
        if (bytes.length >= 12 && text(bytes, 4, 4).equals("ftyp"))
            return text(bytes, 8, 4).equals("qt  ") ? "video/quicktime" : "video/mp4";
        if (bytes.length >= 4 && (bytes[0] & 255) == 0x1a && (bytes[1] & 255) == 0x45
                && (bytes[2] & 255) == 0xdf && (bytes[3] & 255) == 0xa3) return "video/webm";
        if (bytes.length >= 8 && (bytes[0] & 255) == 0x89 && text(bytes, 1, 3).equals("PNG")) return "image/png";
        if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 0xd8
                && (bytes[2] & 255) == 255) return "image/jpeg";
        if (bytes.length >= 5 && text(bytes, 0, 5).equals("%PDF-")) return "application/pdf";
        return fallback;
    }

    private static String text(byte[] bytes, int offset, int count) {
        return new String(bytes, offset, count, java.nio.charset.StandardCharsets.US_ASCII);
    }

    static String mediaType(String mime, String url) {
        String type = mime == null ? "" : mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!type.isEmpty() && !type.equals("application/octet-stream") && !type.equals("binary/octet-stream")) return type;
        String path = url == null ? "" : url.split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
        if (path.endsWith(".mp4") || path.endsWith(".m4v")) return "video/mp4";
        if (path.endsWith(".webm")) return "video/webm";
        if (path.endsWith(".mov")) return "video/quicktime";
        if (path.endsWith(".mp3")) return "audio/mpeg";
        return type.isEmpty() ? "application/octet-stream" : type;
    }

    static String choose(String url, String disposition, String mime) {
        String type = mediaType(mime, url);
        return correctExtension(BrowserDownloads.safeName(URLUtil.guessFileName(url, disposition, type)), type);
    }

    static String correctExtension(String name, String mime) {
        String extension;
        switch (mime) {
            case "video/mp4": extension = ".mp4"; break;
            case "video/webm": extension = ".webm"; break;
            case "video/quicktime": extension = ".mov"; break;
            case "audio/mpeg": extension = ".mp3"; break;
            case "image/png": extension = ".png"; break;
            case "image/jpeg": extension = ".jpg"; break;
            case "application/pdf": extension = ".pdf"; break;
            default: return name;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".bin")) return name.substring(0, name.length() - 4) + extension;
        if (name.lastIndexOf('.') <= 0) return name + extension;
        return name;
    }
}
