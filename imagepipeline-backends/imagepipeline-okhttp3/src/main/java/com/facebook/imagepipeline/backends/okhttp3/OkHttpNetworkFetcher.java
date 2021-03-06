/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.backends.okhttp3;

import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;

import com.facebook.common.logging.FLog;
import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imagepipeline.producers.BaseNetworkFetcher;
import com.facebook.imagepipeline.producers.BaseProducerContextCallbacks;
import com.facebook.imagepipeline.producers.Consumer;
import com.facebook.imagepipeline.producers.FetchState;
import com.facebook.imagepipeline.producers.ProducerContext;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Network fetcher that uses OkHttp 3 as a backend.
 */
public class OkHttpNetworkFetcher extends
        BaseNetworkFetcher<OkHttpNetworkFetcher.OkHttpNetworkFetchState> {

    private static final String TAG = "OkHttpNetworkFetchProducer";
    private static final String QUEUE_TIME = "queue_time";
    private static final String FETCH_TIME = "fetch_time";
    private static final String TOTAL_TIME = "total_time";
    private static final String IMAGE_SIZE = "image_size";
    private final Call.Factory mCallFactory;
    private Executor mCancellationExecutor;

    /**
     * @param okHttpClient client to use
     */
    public OkHttpNetworkFetcher(OkHttpClient okHttpClient) {
        this(okHttpClient, okHttpClient.dispatcher().executorService());
    }

    /**
     * @param callFactory          custom {@link Call.Factory} for fetching image from the network
     * @param cancellationExecutor executor on which fetching cancellation is performed if
     *                             cancellation is requested from the UI Thread
     */
    public OkHttpNetworkFetcher(Call.Factory callFactory, Executor cancellationExecutor) {
        mCallFactory = callFactory;
        mCancellationExecutor = cancellationExecutor;
    }

    @Override
    public OkHttpNetworkFetchState createFetchState(
            Consumer<EncodedImage> consumer,
            ProducerContext context) {
        return new OkHttpNetworkFetchState(consumer, context);
    }

    @Override
    public void fetch(final OkHttpNetworkFetchState fetchState, final Callback callback) {
        fetchState.submitTime = SystemClock.elapsedRealtime();
        final Uri uri = fetchState.getUri();
        final Request request = new Request.Builder()
                .cacheControl(new CacheControl.Builder().noStore().build())//no-cache
                .url(uri.toString())//url
                .get()//GET Request
                .build();
        fetchWithRequest(fetchState, callback, request);
    }

    @Override
    public void onFetchCompletion(OkHttpNetworkFetchState fetchState, int byteSize) {
        fetchState.fetchCompleteTime = SystemClock.elapsedRealtime();
    }

    @Override
    public Map<String, String> getExtraMap(OkHttpNetworkFetchState fetchState, int byteSize) {
        Map<String, String> extraMap = new HashMap<>(4);
        extraMap.put(QUEUE_TIME, Long.toString(fetchState.responseTime - fetchState.submitTime));
        extraMap.put(FETCH_TIME, Long.toString(fetchState.fetchCompleteTime - fetchState.responseTime));
        extraMap.put(TOTAL_TIME, Long.toString(fetchState.fetchCompleteTime - fetchState.submitTime));
        extraMap.put(IMAGE_SIZE, Integer.toString(byteSize));
        return extraMap;
    }

    protected void fetchWithRequest(
            final OkHttpNetworkFetchState fetchState,
            final Callback callback,
            final Request request) {
        final Call call = mCallFactory.newCall(request);//创建请求

        //cancel
        fetchState.getContext().addCallbacks(
                new BaseProducerContextCallbacks() {
                    @Override
                    public void onCancellationRequested() {
                        if (Looper.myLooper() != Looper.getMainLooper()) {
                            call.cancel();
                        }
                        else {
                            mCancellationExecutor.execute(new Runnable() {
                                @Override
                                public void run() {
                                    call.cancel();
                                }
                            });
                        }
                    }
                });

        //将请求入队列
        call.enqueue(
                new okhttp3.Callback() {
                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        fetchState.responseTime = SystemClock.elapsedRealtime();//标记响应时间
                        final ResponseBody body = response.body();
                        try {
                            if (!response.isSuccessful()) {//没有成功
                                //处理异常
                                handleException(call, new IOException("Unexpected HTTP code " + response), callback);
                                return;
                            }

                            long contentLength = body.contentLength();
                            if (contentLength < 0) {
                                contentLength = 0;
                            }

                            //回调字节流
                            callback.onResponse(body.byteStream(), (int) contentLength);
                        } catch (Exception e) {
                            //处理异常
                            handleException(call, e, callback);
                        } finally {
                            try {
                                body.close();
                            } catch (Exception e) {
                                FLog.w(TAG, "Exception when closing response body", e);
                            }
                        }
                    }

                    @Override
                    public void onFailure(Call call, IOException e) {
                        //处理异常
                        handleException(call, e, callback);
                    }
                });
    }

    /**
     * Handles exceptions.
     * <p>
     * <p> OkHttp notifies callers of cancellations via an IOException. If IOException is caught
     * after request cancellation, then the exception is interpreted as successful cancellation
     * and onCancellation is called. Otherwise onFailure is called.
     */
    private void handleException(final Call call, final Exception e, final Callback callback) {
        if (call.isCanceled()) {
            callback.onCancellation();
        }
        else {
            callback.onFailure(e);
        }
    }

    public static class OkHttpNetworkFetchState extends FetchState {
        public long submitTime;
        public long responseTime;
        public long fetchCompleteTime;

        public OkHttpNetworkFetchState(
                Consumer<EncodedImage> consumer,
                ProducerContext producerContext) {
            super(consumer, producerContext);
        }
    }
}
