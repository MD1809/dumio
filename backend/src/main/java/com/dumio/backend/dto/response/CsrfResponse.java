package com.dumio.backend.dto.response;

public record CsrfResponse(String token, String headerName) {

    @Override
    public String toString() {
        return "CsrfResponse[headerName=" + headerName + "]";
    }
}
