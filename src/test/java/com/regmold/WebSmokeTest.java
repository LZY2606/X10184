package com.regmold;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WebSmokeTest {

    @TempDir static Path tempDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("test.db"));
        r.add("regmold.gen.dir", () -> tempDir.resolve("gen").toString());
    }

    @Autowired MockMvc mvc;

    @Test
    void indexShowsTitle() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("寄存器铸模")));
    }

    @Test
    void fullFlow() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/static/sample.yaml"));
        String body = "{\"name\":\"demo\",\"yaml\":" + jsonQuote(yaml) + ",\"message\":\"v1\"}";
        MvcResult res = mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        String json = res.getResponse().getContentAsString();
        long id = Long.parseLong(json.replaceAll(".*\"projectId\":(\\d+).*", "$1"));

        mvc.perform(get("/projects/" + id)).andExpect(status().isOk());
        mvc.perform(get("/projects/" + id + "/revisions/1")).andExpect(status().isOk());
        mvc.perform(get("/projects/" + id + "/diff")).andExpect(status().isOk());

        mvc.perform(get("/api/projects/" + id + "/revisions/1/model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("demo-chip"));
        mvc.perform(get("/api/projects/" + id + "/revisions/1/conflicts"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));

        MvcResult sim = mvc.perform(post("/api/projects/" + id + "/revisions/1/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"script\":\"write LOCK 0x1\\nwrite CTRL 0x1\\nread CTRL\"}"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(sim.getResponse().getContentAsString().contains("CTRL"));

        MvcResult gen = mvc.perform(post("/api/projects/" + id + "/revisions/1/generate"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(gen.getResponse().getContentAsString().contains("fingerprint"));

        mvc.perform(get("/api/projects/" + id + "/revisions/1/artifact").param("path", "MANIFEST.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("revision=1")));

        // second revision with a changed address -> diff must list the affected API
        String yaml2 = yaml.replace("address: 0x04", "address: 0x44");
        mvc.perform(post("/api/projects/" + id + "/revisions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"yaml\":" + jsonQuote(yaml2) + ",\"message\":\"move CTRL\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/projects/" + id + "/diff").param("a", "1").param("b", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("REGMOLD_CTRL_ADDR")));
    }

    private static String jsonQuote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
