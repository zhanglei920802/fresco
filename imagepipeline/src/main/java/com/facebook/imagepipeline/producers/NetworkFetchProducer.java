/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.producers;

import android.os.SystemClock;

import com.facebook.common.internal.VisibleForTesting;
import com.facebook.common.references.CloseableReference;
import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imagepipeline.memory.ByteArrayPool;
import com.facebook.imagepipeline.memory.PooledByteBuffer;
import com.facebook.imagepipeline.memory.PooledByteBufferFactory;
import com.facebook.imagepipeline.memory.PooledByteBufferOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A producer to actually fetch images from the network.
 * <p>
 * <p> Downloaded bytes may be passed to the consumer as they are downloaded, but not more often
 * than {@link #TIME_BETWEEN_PARTIAL_RESULTS_MS}.
 * <p>
 * <p>Clients should provide an instance of {@link NetworkFetcher} to make use of their networking
 * stack. Use {@link HttpUrlConnectionNetworkFetcher} as a model.
 */
public class NetworkFetchProducer implements Producer<EncodedImage> {

    public static final String PRODUCER_NAME = "NetworkFetchProducer";
    public static final String INTERMEDIATE_RESULT_PRODUCER_EVENT = "intermediate_result";
    /**
     * Time between two consecutive partial results are propagated upstream
     * <p>
     * TODO 5399646: make this configurable
     */
    @VisibleForTesting
    static final long TIME_BETWEEN_PARTIAL_RESULTS_MS = 100;
    private static final int READ_SIZE = 16 * 1024;
    private final PooledByteBufferFactory mPooledByteBufferFactory;
    private final ByteArrayPool mByteArrayPool;
    private final NetworkFetcher mNetworkFetcher;

    public NetworkFetchProducer(
            PooledByteBufferFactory pooledByteBufferFactory,
            ByteArrayPool byteArrayPool,
            NetworkFetcher networkFetcher) {
        mPooledByteBufferFactory = pooledByteBufferFactory;
        mByteArrayPool = byteArrayPool;
        mNetworkFetcher = networkFetcher;
    }

    private static float calculateProgress(int downloaded, int total) {
        if (total > 0) {
            return (float) downloaded / total;
        }
        else {
            // If we don't know the total number of bytes, we approximate the progress by an exponential
            // that approaches 1. Here are some values of the progress, given the number of bytes:
            // 0.5 kB ~  1%
            // 2.5 kB ~  5%
            //   5 kB ~ 10%
            //  14 kB ~ 25%
            //  34 kB ~ 50%
            //  68 kB ~ 75%
            // 113 kB ~ 90%
            // 147 kB ~ 95%
            // 225 kB ~ 99%
            return 1 - (float) Math.exp(-downloaded / 5e4);
        }
    }

    @Override
    public void produceResults(Consumer<EncodedImage> consumer, ProducerContext context) {
        context.getListener()
               .onProducerStart(context.getId(), PRODUCER_NAME);
        /**
         * 创建一个fetchState
         */
        final FetchState fetchState = mNetworkFetcher.createFetchState(consumer, context);

        /**
         * 使用NetworkFetcher下载图片,默认使用是http来下载
         * TODO 但是在哪里构造的呢？因为有Volley,OkHttp,Http三种类型，应该是有一定的策略的
         */
        mNetworkFetcher.fetch(
                fetchState, new NetworkFetcher.Callback() {
                    @Override
                    public void onResponse(InputStream response, int responseLength) throws IOException {
                        NetworkFetchProducer.this.onResponse(fetchState, response, responseLength);
                    }

                    @Override
                    public void onFailure(Throwable throwable) {
                        NetworkFetchProducer.this.onFailure(fetchState, throwable);
                    }

                    @Override
                    public void onCancellation() {
                        NetworkFetchProducer.this.onCancellation(fetchState);
                    }
                });
    }

    private void onResponse(
            FetchState fetchState,//fetch状态
            InputStream responseData,//输入流
            int responseContentLength)
            throws IOException {
        final PooledByteBufferOutputStream pooledOutputStream;
        if (responseContentLength > 0) {
            pooledOutputStream = mPooledByteBufferFactory.newOutputStream(responseContentLength);
        }
        else {
            pooledOutputStream = mPooledByteBufferFactory.newOutputStream();
        }
        final byte[] ioArray = mByteArrayPool.get(READ_SIZE);
        try {
            int length;
            while ((length = responseData.read(ioArray)) >= 0) {
                if (length > 0) {
                    pooledOutputStream.write(ioArray, 0, length);
                    maybeHandleIntermediateResult(pooledOutputStream, fetchState);
                    float progress = calculateProgress(pooledOutputStream.size(), responseContentLength);
                    //更新图片加载的进度
                    fetchState.getConsumer().onProgressUpdate(progress);
                }
            }
            mNetworkFetcher.onFetchCompletion(fetchState, pooledOutputStream.size());//标记fetchTime
            //通知消费者已经有结果了
            handleFinalResult(pooledOutputStream, fetchState);
        } finally {
            mByteArrayPool.release(ioArray);
            pooledOutputStream.close();
        }
    }

    private void maybeHandleIntermediateResult(
            PooledByteBufferOutputStream pooledOutputStream,
            FetchState fetchState) {
        final long nowMs = SystemClock.uptimeMillis();
        if (shouldPropagateIntermediateResults(fetchState) &&
                nowMs - fetchState.getLastIntermediateResultTimeMs() >= TIME_BETWEEN_PARTIAL_RESULTS_MS) {
            fetchState.setLastIntermediateResultTimeMs(nowMs);
            fetchState.getListener()
                      .onProducerEvent(fetchState.getId(), PRODUCER_NAME, INTERMEDIATE_RESULT_PRODUCER_EVENT);
            notifyConsumer(pooledOutputStream, false, fetchState.getConsumer());
        }
    }

    private void handleFinalResult(
            PooledByteBufferOutputStream pooledOutputStream,
            FetchState fetchState) {
        Map<String, String> extraMap = getExtraMap(fetchState, pooledOutputStream.size());
        fetchState.getListener()
                  .onProducerFinishWithSuccess(fetchState.getId(), PRODUCER_NAME, extraMap);
        notifyConsumer(pooledOutputStream, true, fetchState.getConsumer());
    }

    private void notifyConsumer(
            PooledByteBufferOutputStream pooledOutputStream,
            boolean isFinal,
            Consumer<EncodedImage> consumer) {
        CloseableReference<PooledByteBuffer> result =
                CloseableReference.of(pooledOutputStream.toByteBuffer());
        EncodedImage encodedImage = null;
        try {
            //解析图片
            encodedImage = new EncodedImage(result);
            encodedImage.parseMetaData();

            //通知新的结果
            consumer.onNewResult(encodedImage, isFinal);
        } finally {
            EncodedImage.closeSafely(encodedImage);
            CloseableReference.closeSafely(result);
        }
    }

    private void onFailure(FetchState fetchState, Throwable e) {
        fetchState.getListener()
                  .onProducerFinishWithFailure(fetchState.getId(), PRODUCER_NAME, e, null);
        fetchState.getConsumer().onFailure(e);
    }

    private void onCancellation(FetchState fetchState) {
        fetchState.getListener()
                  .onProducerFinishWithCancellation(fetchState.getId(), PRODUCER_NAME, null);
        fetchState.getConsumer().onCancellation();
    }

    private boolean shouldPropagateIntermediateResults(FetchState fetchState) {
        if (!fetchState.getContext().getImageRequest().getProgressiveRenderingEnabled()) {
            return false;
        }
        return mNetworkFetcher.shouldPropagate(fetchState);
    }

    @Nullable
    private Map<String, String> getExtraMap(FetchState fetchState, int byteSize) {
        if (!fetchState.getListener().requiresExtraMap(fetchState.getId())) {
            return null;
        }
        return mNetworkFetcher.getExtraMap(fetchState, byteSize);
    }
}
