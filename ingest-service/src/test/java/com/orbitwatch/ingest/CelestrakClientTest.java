package com.orbitwatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CelestrakClientTest {

    /**
     * An unreachable host produced "failed: null" in the 2026-09-10 container run because the
     * underlying IOException carries no message. The log has to name what broke.
     */
    @Test
    void transportFailuresAreNamedNotLoggedAsNull() {
        assertThatThrownBy(() -> new CelestrakClient().fetch("https://celestrak.unreachable.invalid/gp.php"))
                .isInstanceOf(CelestrakClient.FetchException.class)
                .hasMessageContaining("Exception")           // the exception type, whatever the network does
                .hasMessageNotContaining("failed: null");
    }

    /**
     * A dropped connection — what CelesTrak looked like from the operator's network on 2026-09-10 —
     * must not cost a whole ingest cycle: the next attempt succeeds.
     */
    @Test
    void transportFailuresAreRetried() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try (Socket s = server.accept()) {
                        if (connections.incrementAndGet() < 3) continue;   // hang up mid-handshake
                        s.getInputStream().read(new byte[1024]);
                        OutputStream out = s.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n[]").getBytes());
                        out.flush();
                    } catch (Exception e) {
                        return;
                    }
                }
            });
            t.setDaemon(true);
            t.start();

            String url = "http://127.0.0.1:" + server.getLocalPort() + "/gp";
            try (var in = new CelestrakClient().fetch(url)) {
                assertThat(new String(in.readAllBytes())).isEqualTo("[]");
            }
            assertThat(connections.get()).isEqualTo(3);
        }
    }

    /** A status error will not fix itself: fail on the first response instead of hammering the provider. */
    @Test
    void httpStatusErrorsAreNotRetried() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gp", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(503, -1);
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/gp";
            assertThatThrownBy(() -> new CelestrakClient().fetch(url))
                    .isInstanceOf(CelestrakClient.FetchException.class)
                    .hasMessageContaining("HTTP 503");
            assertThat(hits.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void successfulFetchReturnsTheBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gp", ex -> {
            byte[] body = "[{\"NORAD_CAT_ID\":1}]".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try (var in = new CelestrakClient().fetch("http://127.0.0.1:" + server.getAddress().getPort() + "/gp")) {
            assertThat(new String(in.readAllBytes())).contains("NORAD_CAT_ID");
        } finally {
            server.stop(0);
        }
    }
}
