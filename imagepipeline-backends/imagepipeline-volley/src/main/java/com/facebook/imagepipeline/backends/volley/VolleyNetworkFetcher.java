/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.backends.volley;

import android.os.SystemClock;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.RequestQueue.RequestFilter;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imagepipeline.producers.BaseNetworkFetcher;
import com.facebook.imagepipeline.producers.BaseProducerContextCallbacks;
import com.facebook.imagepipeline.producers.Consumer;
import com.facebook.imagepipeline.producers.FetchState;
import com.facebook.imagepipeline.producers.ProducerContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Network fetcher that uses Volley as its backend. The request being made will bypass Volley's
 * cache.
 * <p>
 * Volley does not allow access to a {@link InputStream}. Therefore, responses will be passed
 * along as complete byte arrays, which will not allow for progressive JPEG streaming.
 */
public class VolleyNetworkFetcher extends
        BaseNetworkFetcher<VolleyNetworkFetcher.VolleyNetworkFetchState> {

    public static class VolleyNetworkFetchState extends FetchState {
        long submitTime;
        long responseTime;
        long fetchCompleteTime;

        public VolleyNetworkFetchState(
                Consumer<EncodedImage> consumer,
                ProducerContext producerContext) {
            super(consumer, producerContext);
        }
    }

    private static final String QUEUE_TIME = "queue_time";
    private static final String FETCH_TIME = "fetch_time";
    private static final String TOTAL_TIME = "total_time";
    private static final String IMAGE_SIZE = "image_size";

    /**
     * 请求队列
     */
    private final RequestQueue mRequestQueue;

    /**
     * @param requestQueue The Volley {@link RequestQueue} to use
     */
    public VolleyNetworkFetcher(RequestQueue requestQueue) {
        mRequestQueue = requestQueue;
    }

    @Override
    public VolleyNetworkFetchState createFetchState(
            Consumer<EncodedImage> consumer,
            ProducerContext context) {
        return new VolleyNetworkFetchState(consumer, context);
    }

    @Override
    public void fetch(final VolleyNetworkFetchState fetchState, final Callback callback) {
        fetchState.submitTime = SystemClock.elapsedRealtime();//标记任务提交时间

        final RawRequest request = new RawRequest(
                fetchState.getUri().toString(),
                new Response.Listener<byte[]>() {
                    @Override
                    public void onResponse(byte[] bytes) {
                        fetchState.responseTime = SystemClock.uptimeMillis();//标记任务响应时间

                        try {
                            InputStream is = new ByteArrayInputStream(bytes);
                            //回调输入流
                            callback.onResponse(is, bytes.length);
                        } catch (IOException e) {
                            //失败回调
                            callback.onFailure(e);
                        }
                    }
                },
                new Response.ErrorListener() {
                    @Override
                    public void onErrorResponse(VolleyError volleyError) {
                        //失败回调
                        callback.onFailure(volleyError);
                    }
                });

        //取消的监听
        fetchState.getContext().addCallbacks(
                new BaseProducerContextCallbacks() {
                    @Override
                    public void onCancellationRequested() {
                        mRequestQueue.cancelAll(new RequestFilter() {
                            @Override
                            public boolean apply(Request<?> candidate) {
                                /**
                                 * 如果序列号相同，则取消
                                 */
                                return candidate != null && request.getSequence() == candidate.getSequence();
                            }
                        });
                    }
                });

        mRequestQueue.add(request);
    }

    @Override
    public void onFetchCompletion(VolleyNetworkFetchState fetchState, int byteSize) {
        fetchState.fetchCompleteTime = SystemClock.elapsedRealtime();
    }

    @Override
    public Map<String, String> getExtraMap(VolleyNetworkFetchState fetchState, int byteSize) {
        Map<String, String> extraMap = new HashMap<>(4);
        extraMap.put(QUEUE_TIME, Long.toString(fetchState.responseTime - fetchState.submitTime));
        extraMap.put(FETCH_TIME, Long.toString(fetchState.fetchCompleteTime - fetchState.responseTime));
        extraMap.put(TOTAL_TIME, Long.toString(fetchState.fetchCompleteTime - fetchState.submitTime));
        extraMap.put(IMAGE_SIZE, Integer.toString(byteSize));
        return extraMap;
    }

}
