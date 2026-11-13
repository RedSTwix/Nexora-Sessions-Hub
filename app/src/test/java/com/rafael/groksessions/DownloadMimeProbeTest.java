package com.rafael.groksessions;

import org.junit.Test;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public final class DownloadMimeProbeTest {
    @Test public void probesOnlyAPrefixWithTheTriggeringProfilesHeaders() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            FutureTask<String> request = new FutureTask<>(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    StringBuilder headers = new StringBuilder();
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) headers.append(line).append('\n');
                    socket.getOutputStream().write(("HTTP/1.1 206 Partial Content\r\nContent-Type: application/octet-stream\r\n"
                            + "Content-Length: 1000000\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(new byte[]{0,0,0,24,'f','t','y','p','i','s','o','m'});
                    socket.getOutputStream().write(new byte[52]);
                    socket.getOutputStream().flush();
                    return headers.toString();
                }
            });
            new Thread(request, "mime-probe-fixture").start();
            assertEquals("video/mp4", DownloadMimeProbe.inspect("http://localhost:" + server.getLocalPort() + "/opaque",
                    "Profile-Agent", "profile=first", "application/octet-stream"));
            String headers = request.get(5, TimeUnit.SECONDS);
            assertTrue(headers.contains("Range: bytes=0-63"));
            assertTrue(headers.contains("User-Agent: Profile-Agent"));
            assertTrue(headers.contains("Cookie: profile=first"));
        }
    }

    @Test public void doesNotFollowARedirectWithTheProfilesCookies() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            FutureTask<Void> request = new FutureTask<>(() -> {
                try (Socket socket = server.accept()) {
                    socket.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/never-request\r\n"
                            + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
                return null;
            });
            new Thread(request, "mime-redirect-fixture").start();
            assertEquals("application/octet-stream", DownloadMimeProbe.inspect("http://localhost:" + server.getLocalPort() + "/opaque",
                    "Profile-Agent", "profile=first", "application/octet-stream"));
            request.get(5, TimeUnit.SECONDS);
        }
    }
}
