package com.flowpilot.common;

import com.flowpilot.common.util.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract tests of the identifier representation in the HTTP responses.
 *
 * <p>The identifiers are 64 bit snowflake ids. A browser number holds 53 bits exactly, so an id
 * that is sent as a number is rounded and the detail pages then address a record that does not
 * exist. Every identifier that leaves the API therefore has to be a string.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdentifierSerializationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("the identifiers of the HTTP responses are strings")
    void identifiersAreStrings() throws Exception {
        String username = "idtest_" + System.nanoTime();
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\","
                                + "\"password\":\"IdentifierTest123\"}"))
                .andExpect(status().isOk());

        String login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\","
                                + "\"password\":\"IdentifierTest123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isString())
                .andExpect(jsonPath("$.data.user.id").isString())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(login).path("data").path("token").asText();

        // The user has no workflow of their own, so one is created for the test.
        mockMvc.perform(post("/api/v1/workflows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"identifier test\",\"definition\":{\"nodes\":["
                                + "{\"id\":\"only\",\"type\":\"CODE\",\"dependencies\":[],"
                                + "\"config\":{\"operation\":\"identity\"}}]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isString());

        String workflows = mockMvc.perform(get("/api/v1/workflows")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").isString())
                .andExpect(jsonPath("$.data.records[0].ownerId").isString())
                .andReturn().getResponse().getContentAsString();
        assertThat(workflows).contains("\"id\":\"");

        String workflowId = objectMapper.readTree(workflows).path("data").path("records")
                .get(0).path("id").asText();

        String versions = mockMvc.perform(get("/api/v1/workflows/" + workflowId + "/versions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").isString())
                .andExpect(jsonPath("$.data[0].workflowId").isString())
                .andReturn().getResponse().getContentAsString();
        assertThat(versions).contains("\"id\":\"");

        String versionId = objectMapper.readTree(versions).path("data").get(0).path("id").asText();

        String executions = mockMvc.perform(get("/api/v1/executions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(executions).doesNotContain("\"executionId\": ");

        String approvals = mockMvc.perform(get("/api/v1/approvals")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(approvals).doesNotContain("\"approvalId\": ");
    }

    @Test
    @DisplayName("a snowflake id keeps its exact value as a string")
    void identifierKeepsItsExactValue() {
        long id = 378902953627938816L;
        String text = Ids.of(id);

        assertThat(text).isEqualTo("378902953627938816");
        // A browser number would round this value.
        assertThat(String.valueOf((double) id)).isNotEqualTo(text);
    }
}
