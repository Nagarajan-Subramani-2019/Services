package com.oxygenraj.userinfo.api;

import java.util.List;

public record UserPage(List<UserResponse> content, int page, int size, long totalElements) {}
