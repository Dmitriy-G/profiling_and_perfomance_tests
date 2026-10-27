package com.example.service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

import com.example.model.WorkResult;
import org.springframework.stereotype.Service;

@Service
public class WorkService {
    private final AtomicReferenceArray<byte[][]> survivors = new AtomicReferenceArray<>(128);
    private final AtomicLong sequence = new AtomicLong();

    public WorkResult simulate(int iterations) {
        long start = System.nanoTime();
        long payloadBytes = 0;
        long checksum = 0;
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int iteration = 0; iteration < iterations; iteration++) {
            byte[][] graph = new byte[16][];
            for (int index = 0; index < graph.length; index++) {
                byte[] payload = new byte[random.nextInt(256, 4097)];
                random.nextBytes(payload);
                checksum += payload[0] + payload[payload.length - 1];
                payloadBytes += payload.length;
                graph[index] = payload;
            }

            int slot = Math.floorMod(sequence.getAndIncrement(), survivors.length());
            survivors.set(slot, graph);
        }

        return new WorkResult(iterations, payloadBytes, checksum, System.nanoTime() - start);
    }
}
