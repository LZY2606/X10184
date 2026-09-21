package com.regforge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private String demoYaml() throws Exception {
        try (var in = new ClassPathResource("demo.yaml").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void homePageShowsRegisterForgeTitle() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("寄存器铸模")));
    }

    @Test
    void importsRevisionSimulatesAndGeneratesTraceableCode() throws Exception {
        MvcResult imported = mvc.perform(post("/api/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"yaml\":" + com.fasterxml.jackson.databind.ObjectMapper
                                .class.getMethod("writeValueAsString", Object.class)
                                .invoke(new com.fasterxml.jackson.databind.ObjectMapper(), demoYaml()) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andReturn();
        String body = imported.getResponse().getContentAsString();
        long id = new ObjectMapper().readTree(body).get("id").asLong();
        String hash = new ObjectMapper().readTree(body).get("hash").asText();

        mvc.perform(get("/api/versions/" + id + "/conflicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").isArray());

        String steps = """
                {"steps":[
                  {"type":"WRITE","register":"LOCKCTL","value":"0x0"},
                  {"type":"WRITE","register":"STATUS","value":"0x0"},
                  {"type":"WRITE","register":"CTRL","value":"0x1"},
                  {"type":"PEEK","register":"STATUS"},
                  {"type":"READ","register":"STATUS"},
                  {"type":"READ","register":"STATUS"}
                ]}""";
        mvc.perform(post("/api/versions/" + id + "/simulate")
                        .contentType(MediaType.APPLICATION_JSON).content(steps))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[3].readValue").value("0x00000001"))
                .andExpect(jsonPath("$.steps[3].stateAfter.STATUS").value("0x00000001"))
                .andExpect(jsonPath("$.steps[4].stateAfter.STATUS").value("0x00000000"));

        mvc.perform(get("/api/versions/" + id + "/codegen").param("lang", "c"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(hash)));
    }
}
