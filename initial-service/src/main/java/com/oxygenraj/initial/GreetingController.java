package com.oxygenraj.initial;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class GreetingController {

    private final OracleSchemaService oracleSchemaService;

    public GreetingController(OracleSchemaService oracleSchemaService) {
        this.oracleSchemaService = oracleSchemaService;
    }

    @GetMapping("/hi")
    public GreetingResponse hi() {
        return greeting("Hi");
    }

    @GetMapping("/hello")
    public GreetingResponse hello() {
        return greeting("Hello");
    }

    private GreetingResponse greeting(String message) {
        return new GreetingResponse(
                message, "initial-service", oracleSchemaService.requireInitialSchema());
    }
}
