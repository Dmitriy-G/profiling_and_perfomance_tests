package com.example.model;

public record WorkResult(int iterations, long payloadBytes, long checksum, long elapsedNanos) {
}
