package com.example.demo;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceTests {

    private ChatModel model;
    private ChatService service;

    @BeforeEach
    void setUp() {
        this.model = mock(ChatModel.class);
        when(this.model.getOptions()).thenReturn(ChatOptions.builder().build());
        this.service = new ChatService(ChatClient.builder(this.model));
    }

    @Test
    void returnsOnlyTheFinalAnswerWithoutThinkingMetadata() {
        when(this.model.call(any(Prompt.class)))
                .thenReturn(response("println 'Hello world'", "Internal planning text"));

        assertEquals("println 'Hello world'", this.service.reply("Hello world in Groovy"));
    }

    @Test
    void skipsThinkingOnlyChunksAndPreservesAnswerWhitespace() {
        when(this.model.stream(any(Prompt.class)))
                .thenReturn(Flux.just(
                        response("", "Internal planning text"),
                        response("println ", ""),
                        response("'Hello world'", "")));

        assertEquals(List.of("println ", "'Hello world'"),
                this.service.streamReply("Hello world in Groovy")
                        .collectList().block(Duration.ofSeconds(5)));
    }

    private ChatResponse response(String answer, String thinking) {
        AssistantMessage output = AssistantMessage.builder()
                .content(answer)
                .properties(Map.of("thinking", thinking))
                .build();
        return new ChatResponse(List.of(new Generation(output,
                ChatGenerationMetadata.builder().metadata("thinking", thinking).build())));
    }
}
