package com.example.demo;

public class EmptyModelResponseException extends RuntimeException {

    public EmptyModelResponseException() {
        super("The local model did not return a final answer.");
    }
}
