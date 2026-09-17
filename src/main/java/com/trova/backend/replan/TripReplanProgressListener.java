package com.trova.backend.replan;

public interface TripReplanProgressListener {
    void onProgress(int completed, int total);
}
