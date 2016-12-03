/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.animated.util;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Build;

import com.facebook.common.util.ByteConstants;

import java.util.Arrays;

/**
 * Utility methods for AnimatedDrawable.
 */
public class AnimatedDrawableUtil {

    // See comment in fixFrameDurations below.
    private static final int MIN_FRAME_DURATION_MS = 11;
    private static final int FRAME_DURATION_MS_FOR_MIN = 100;

    /**
     * Checks whether the specified frame number is outside the range inclusive of both start and end.
     * If start <= end, start is within, end is within, and everything in between is within.
     * If start > end, start is within, end is within, everything less than start is within and
     * everything greater than end is within. This behavior is useful for handling the wrapping case.
     *
     * @param startFrame  the start frame
     * @param endFrame    the end frame
     * @param frameNumber the frame number
     * @return whether the frame is outside the range of [start, end]
     */
    public static boolean isOutsideRange(int startFrame, int endFrame, int frameNumber) {
        if (startFrame == -1 || endFrame == -1) {
            // This means nothing should pass.
            return true;
        }
        boolean outsideRange;
        if (startFrame <= endFrame) {
            outsideRange = frameNumber < startFrame || frameNumber > endFrame;
        }
        else {
            // Wrapping
            outsideRange = frameNumber < startFrame && frameNumber > endFrame;
        }
        return outsideRange;
    }

    public void appendMemoryString(StringBuilder sb, int kiloBytes) {
        if (kiloBytes < ByteConstants.KB) {
            sb.append(kiloBytes);
            sb.append("KB");
        }
        else {
            int mbUsed = kiloBytes / ByteConstants.KB;
            int mbUsedDecimal = (kiloBytes % ByteConstants.KB) / 100;
            sb.append(mbUsed);
            sb.append(".");
            sb.append(mbUsedDecimal);
            sb.append("MB");
        }
    }

    /**
     * Adjusts the frame duration array to respect logic for minimum frame duration time.
     *
     * @param frameDurationMs the frame duration array
     */
    public void fixFrameDurations(int[] frameDurationMs) {
        // We follow Chrome's behavior which comes from Firefox.
        // Comment from Chrome's ImageSource.cpp follows:
        // We follow Firefox's behavior and use a duration of 100 ms for any frames that specify
        // a duration of <= 10 ms. See <rdar://problem/7689300> and <http://webkit.org/b/36082>
        // for more information.
        for (int i = 0; i < frameDurationMs.length; i++) {
            if (frameDurationMs[i] < MIN_FRAME_DURATION_MS) {
                frameDurationMs[i] = FRAME_DURATION_MS_FOR_MIN;
            }
        }
    }

    /**
     * Gets the total duration of an image by summing up the duration of the frames.
     *
     * @param frameDurationMs the frame duration array
     * @return the total duration in milliseconds
     */
    public int getTotalDurationFromFrameDurations(int[] frameDurationMs) {
        int totalMs = 0;
        for (int i = 0; i < frameDurationMs.length; i++) {
            totalMs += frameDurationMs[i];
        }
        return totalMs;
    }

    /**
     * Given an array of frame durations, generate an array of timestamps corresponding to when each
     * frame beings.
     *
     * @param frameDurationsMs an array of frame durations
     * @return an array of timestamps
     */
    public int[] getFrameTimeStampsFromDurations(int[] frameDurationsMs) {
        int[] frameTimestampsMs = new int[frameDurationsMs.length];
        int accumulatedDurationMs = 0;
        for (int i = 0; i < frameDurationsMs.length; i++) {
            frameTimestampsMs[i] = accumulatedDurationMs;
            accumulatedDurationMs += frameDurationsMs[i];
        }
        return frameTimestampsMs;
    }

    /**
     * Gets the frame index for specified timestamp.
     *
     * @param frameTimestampsMs an array of timestamps generated by {@link #getFrameForTimestampMs)}
     * @param timestampMs       the timestamp
     * @return the frame index for the timestamp or the last frame number if the timestamp is outside
     * the duration of the entire animation
     */
    public int getFrameForTimestampMs(int frameTimestampsMs[], int timestampMs) {
        int index = Arrays.binarySearch(frameTimestampsMs, timestampMs);
        if (index < 0) {
            return -index - 1 - 1;
        }
        else {
            return index;
        }
    }

    @SuppressLint("NewApi")
    public int getSizeOfBitmap(Bitmap bitmap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            return bitmap.getAllocationByteCount();
        }
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1) {
            return bitmap.getByteCount();
        }
        else {
            // Estimate for earlier platforms.
            return bitmap.getWidth() * bitmap.getHeight() * 4;
        }
    }
}
