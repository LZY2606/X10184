package com.regmold;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:./data/test-regmold.db",
        "server.port=0"
})
@AutoConfigureMockMvc
class ApiIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Test
    void indexShowsChineseTitle() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("寄存器铸模")));
    }

    @Test
    void analyzeAndPersistRevision() throws Exception {
        String yaml = TestSupport.fixture("sample.yaml");
        mvc.perform(post("/api/analyze").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(Map.of("yaml", yaml))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        mvc.perform(post("/api/revisions").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(Map.of("yaml", yaml))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1));
        mvc.perform(get("/api/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("gpio"));
    }

    @Test
    void simulateReportsReadClearConsumption() throws Exception {
        String yaml = TestSupport.fixture("sample.yaml");
        Map<String, Object> body = Map.of(
                "yaml", yaml,
                "operations", List.of(
                        Map.of("type", "read", "register", "STATUS"),
                        Map.of("type", "read", "register", "STATUS")));
        mvc.perform(post("/api/simulate").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].value").value(3))
                .andExpect(jsonPath("$.steps[1].value").value(2));
    }

    @Test
    void generateReturnsHashedArtifacts() throws Exception {
        String yaml = TestSupport.fixture("sample.yaml");
        mvc.perform(post("/api/generate").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(Map.of("yaml", yaml))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files['register_model.h']").exists())
                .andExpect(jsonPath("$.files['register_model.rs']").exists())
                .andExpect(jsonPath("$.files['register_model.md']").exists());
    }

    @Test
    void invalidModelRejectedForGeneration() throws Exception {
        String bad = """
                name: bad
                data_width: 32
                registers:
                  - name: A
                    offset: 0
                    fields:
                      - {name: X, bits: "[1:0]", access: RW}
                      - {name: Y, bits: "[2:1]", access: RW}
                """;
        mvc.perform(post("/api/generate").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(Map.of("yaml", bad))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void diffEndpointReportsAbiChange() throws Exception {
        String a = TestSupport.fixture("sample.yaml");
        String b = a.replace("offset: 0x0C", "offset: 0x14");
        mvc.perform(post("/api/diff").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(Map.of("yamlA", a, "yamlB", b))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].kind").exists());
    }
}
