package com.frammy.unitylauncher.chunkactivity;

public class PlayerChunkSession {
    public final String currentChunk;
    public final long enterTime;

    public PlayerChunkSession(String currentChunk, long enterTime) {
        this.currentChunk = currentChunk;
        this.enterTime = enterTime;
    }
}
