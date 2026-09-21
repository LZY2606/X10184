package com.regforge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "regforge.db=:memory:")
class WebIntegrationTest {

    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();

    private String url(String p) { return "http://127.0.0.1:" + port + p; }

    private String post(String path, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    private String get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path))).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static final String YAML = """
            name: it_dev
            version: "3"
            registers:
              - name: CTRL
                address: 0x0
                fields:
                  - {name: EN, bits: 0, access: rw}
            """;

    private String q(String s) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(s);
    }

    @Test
    void indexHasTitle() throws Exception {
        assertTrue(get("/").contains("寄存器铸模"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void importGenerateSimulate() throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> imp = om.readValue(post("/api/import", "{\"yaml\":" + q(YAML) + "}"), Map.class);
        assertEquals(true, imp.get("created"));
        int rev = ((Number) imp.get("revision")).intValue();

        List<Map<String, String>> files = om.readValue(get("/api/generate/" + rev), List.class);
        assertEquals(4, files.size());
        assertTrue(files.stream().anyMatch(f -> f.get("path").equals("c/regs.h")));
        assertTrue(files.stream().anyMatch(f -> f.get("path").equals("c/regs.c")));
        assertTrue(files.stream().anyMatch(f -> f.get("path").equals("rust/regs.rs")));
        assertTrue(files.stream().anyMatch(f -> f.get("path").equals("docs/registers.md")));

        Map<String, Object> started = om.readValue(post("/api/sim/" + rev + "/start", ""), Map.class);
        String session = (String) started.get("session");
        Map<String, Object> step = om.readValue(post("/api/sim/" + session + "/step",
                "{\"type\":\"write\",\"target\":\"CTRL\",\"value\":\"0x1\"}"), Map.class);
        Map<String, Object> state = (Map<String, Object>) step.get("state");
        assertEquals("1", String.valueOf(state.get("CTRL")));

        Map<String, Object> val = om.readValue(post("/api/validate", "{\"yaml\":" + q(YAML) + "}"), Map.class);
        assertNotNull(val.get("layout"));
    }
}
