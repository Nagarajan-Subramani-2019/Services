package com.example.demo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class MyController {

    private final ChatService chatService;

    public MyController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return new ChatResponse(this.chatService.reply(request.message()));
    }

    @PostMapping(path = "/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ChatRequest request) {
        Flux<ServerSentEvent<String>> tokenEvents = this.chatService.streamReply(request.message())
                .map(token -> ServerSentEvent.<String>builder(token).event("token").build());

        return Flux.concat(
                        Flux.just(ServerSentEvent.<String>builder("Connected to Ollama. Generating response…")
                                .event("status")
                                .build()),
                        tokenEvents,
                        Flux.just(ServerSentEvent.<String>builder().event("done").build()))
                .onErrorResume(exception -> Flux.just(ServerSentEvent.<String>builder(streamErrorMessage(exception))
                        .event("error")
                        .build()));
    }

    private String streamErrorMessage(Throwable exception) {
        if (exception instanceof ResourceAccessException || exception instanceof TransientAiException) {
            return "Ollama is unavailable. Start Ollama and pull the configured model.";
        }
        if (exception instanceof NonTransientAiException) {
            return "Ollama rejected the request or the configured model is unavailable.";
        }
        if (exception instanceof EmptyModelResponseException) {
            return "The local model did not return a final answer. Please try again.";
        }
        return "The local model could not complete the response. Please try again.";
    }

    public record ChatRequest(
            @NotBlank(message = "message must not be blank")
            @Size(max = 4_000, message = "message must be at most 4000 characters")
            String message) {
    }

    public record ChatResponse(String answer) {
    }
}
