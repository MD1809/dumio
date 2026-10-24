package com.dumio.backend.event;


public record PasswordChangedEvent(String email) {

    @Override
    public String toString() {
        return "PasswordChangedEvent[redacted]";
    }
}
