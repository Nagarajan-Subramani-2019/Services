package com.oxygenraj.transactionui;

public class ApiFailure extends RuntimeException {
    private final int status;
    private final String code;
    public ApiFailure(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    public int status() { return status; }
    public String code() { return code; }
    public static ApiFailure unavailable() { return new ApiFailure(503,"BACKEND_UNAVAILABLE","A required API is unavailable. Try again shortly."); }
    public static ApiFailure invalid() { return new ApiFailure(502,"INVALID_BACKEND_RESPONSE","A required API returned an invalid response."); }
}
