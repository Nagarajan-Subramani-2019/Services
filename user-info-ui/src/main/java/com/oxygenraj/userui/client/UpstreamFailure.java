package com.oxygenraj.userui.client;

/** Contains only fixed, safe messages; never retain downstream response bodies or credentials. */
public final class UpstreamFailure extends RuntimeException {
    private final int status;
    private final String code;

    public UpstreamFailure(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public static UpstreamFailure unavailable() {
        return new UpstreamFailure(503, "SERVICE_UNAVAILABLE", "User information service is unavailable. Please try again later.");
    }

    public static UpstreamFailure invalidResponse() {
        return new UpstreamFailure(502, "INVALID_UPSTREAM_RESPONSE", "User information service returned an unexpected response.");
    }
}
