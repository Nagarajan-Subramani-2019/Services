package com.example.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ChatService {

    private final ChatClient chatClient;

    public ChatService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        Return only the requested final answer. Do not include thinking, analysis,
                        planning, or a restatement of the question. For code requests, return only
                        the code unless an explanation is requested.
                        """)
                .build();
    }

    public String reply(String message) {
        String answer = this.chatClient.prompt()
                .user(message)
                .call()
                .content();

        if (answer == null || answer.isBlank()) {
            throw new EmptyModelResponseException();
        }

        return answer;
    }

    public Flux<String> streamReply(String message) {
        return this.chatClient.prompt()
                .user(message)
                .stream()
                .content();
    }
}
