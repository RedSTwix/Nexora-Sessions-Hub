package com.rafael.groksessions;

import org.json.JSONException;
import org.json.JSONObject;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

final class SiteShortcut {
    final String id, name, url, icon;

    SiteShortcut(String id, String name, String url, String icon) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("Atalho inválido.");
        if (name == null || name.trim().isEmpty() || name.trim().length() > 80)
            throw new IllegalArgumentException("Use um nome de até 80 caracteres.");
        this.id = id;
        this.name = name.trim();
        this.url = normalizeUrl(url);
        this.icon = icon != null && icon.length() <= 50000 ? icon : null;
    }

    static String normalizeUrl(String input) {
        if (input == null || input.trim().isEmpty() || input.length() > 4096)
            throw new IllegalArgumentException("Informe o endereço do site.");
        String value = input.trim();
        if (value.matches("(?i)^(javascript|data|file|content|intent|about|mailto|tel):.*"))
            throw new IllegalArgumentException("Use um endereço HTTP ou HTTPS válido.");
        if (value.startsWith("//")) value = "https:" + value;
        else if (!value.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) value = "https://" + value;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getHost().isEmpty() || uri.getRawUserInfo() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException("Use um endereço HTTP ou HTTPS válido, sem usuário ou senha na URL.");
            scheme = scheme.toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if ((port == 443 && scheme.equals("https")) || (port == 80 && scheme.equals("http"))) port = -1;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            String canonical = scheme + "://" + host + (port == -1 ? "" : ":" + port) + path;
            if (uri.getRawQuery() != null) canonical += "?" + uri.getRawQuery();
            if (uri.getRawFragment() != null) canonical += "#" + uri.getRawFragment();
            return new URI(canonical).toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Confira o endereço do site.");
        }
    }

    static String origin(String url) {
        try {
            URI uri = new URI(normalizeUrl(url));
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if ((port == 443 && scheme.equals("https")) || (port == 80 && scheme.equals("http"))) port = -1;
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (port == -1 ? "" : ":" + port);
        } catch (IllegalArgumentException | URISyntaxException ignored) { return ""; }
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("name", name).put("url", url).put("icon", icon);
    }

    static SiteShortcut fromJson(JSONObject value) {
        return new SiteShortcut(value.optString("id"), value.optString("name"), value.optString("url"), value.optString("icon", null));
    }
}
