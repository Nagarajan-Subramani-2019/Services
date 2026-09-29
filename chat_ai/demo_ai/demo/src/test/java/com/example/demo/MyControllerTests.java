package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MyController.class)
class MyControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @Test
    void returnsTheLocalModelReply() throws Exception {
        given(this.chatService.reply("Say hello")).willReturn("Hello from Ollama");

        this.mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Say hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Hello from Ollama"));
    }

    @Test
    void streamsTheLocalModelReply() throws Exception {
        given(this.chatService.streamReply("Say hello"))
                .willReturn(Flux.just("Hello", " from Ollama"));

        MvcResult result = this.mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"Say hello\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        this.mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("event:token")))
                .andExpect(content().string(containsString("data:Hello")))
                .andExpect(content().string(containsString("data: from Ollama")))
                .andExpect(content().string(containsString("event:done")));
    }

    @Test
    void rejectsBlankMessages() throws Exception {
        this.mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportsWhenOllamaIsUnavailable() throws Exception {
        given(this.chatService.reply("Say hello"))
                .willThrow(new ResourceAccessException("Connection refused"));

        this.mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Say hello\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Ollama is unavailable. Start Ollama and pull the configured model."));
    }

    @Test
    void reportsWhenTheModelReturnsNoFinalAnswer() throws Exception {
        given(this.chatService.reply("Say hello"))
                .willThrow(new EmptyModelResponseException());

        this.mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Say hello\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("The local model did not return a final answer. Please try again."));
    }
}
