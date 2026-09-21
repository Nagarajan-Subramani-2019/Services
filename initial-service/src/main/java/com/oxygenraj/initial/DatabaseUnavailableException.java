package com.oxygenraj.initial;

public class DatabaseUnavailableException extends RuntimeException {

    public DatabaseUnavailableException() {
        super("Database is unavailable.");
    }

    public DatabaseUnavailableException(Throwable cause) {
        super("Database is unavailable.", cause);
    }
}
