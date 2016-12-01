/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.producers;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import android.net.Uri;

import com.facebook.common.internal.VisibleForTesting;
import com.facebook.imagepipeline.image.EncodedImage;

/**
 * Network fetcher that uses the simplest Android stack.
 * <p>
 * <p> Apps requiring more sophisticated networking should implement their own
 * {@link NetworkFetcher}.
 */
public class HttpUrlConnectionNetworkFetcher extends BaseNetworkFetcher<FetchState> {

    /**
     * 三个线程用于网络下载
     */
    private static final int NUM_NETWORK_THREADS = 3;

    /**
     * 最多5次重定向
     */
    private static final int MAX_REDIRECTS = 5;

    /**
     * 临时的重定向
     */
    public static final int HTTP_TEMPORARY_REDIRECT = 307;

    /**
     * 永久的重定向
     */
    public static final int HTTP_PERMANENT_REDIRECT = 308;

    private final ExecutorService mExecutorService;

    public HttpUrlConnectionNetworkFetcher() {
        /**
         * 创建带有3个核心线程的线程池
         */
        this(Executors.newFixedThreadPool(NUM_NETWORK_THREADS));
    }

    @VisibleForTesting
    HttpUrlConnectionNetworkFetcher(ExecutorService executorService) {
        mExecutorService = executorService;
    }

    @Override
    public FetchState createFetchState(Consumer<EncodedImage> consumer, ProducerContext context) {
        return new FetchState(consumer, context);
    }

    @Override
    public void fetch(final FetchState fetchState, final Callback callback) {
        /**
         * 向ExecutorService提交一个任务
         */
        final Future<?> future = mExecutorService.submit(
                new Runnable() {
                    @Override
                    public void run() {
                        fetchSync(fetchState, callback);
                    }
                });

        /**
         * 注册取消任务的回调
         */
        fetchState.getContext().addCallbacks(
                new BaseProducerContextCallbacks() {
                    @Override
                    public void onCancellationRequested() {
                        if (future.cancel(false)) {
                            callback.onCancellation();
                        }
                    }
                });
    }

    @VisibleForTesting
    void fetchSync(FetchState fetchState, Callback callback) {
        HttpURLConnection connection = null;

        try {
            connection = downloadFrom(fetchState.getUri(), MAX_REDIRECTS);

            if (connection != null) {
                //回调输入流，给NetworkProducer
                callback.onResponse(connection.getInputStream(), -1);
            }
        } catch (IOException e) {
            //回调失败
            callback.onFailure(e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

    }

    /**
     * 从指定的url下载数据，最多允许5次跳转(location)
     *
     * @param uri
     * @param maxRedirects
     * @return
     * @throws IOException
     */
    private HttpURLConnection downloadFrom(Uri uri, int maxRedirects) throws IOException {
        HttpURLConnection connection = openConnectionTo(uri);
        int responseCode = connection.getResponseCode();

        if (isHttpSuccess(responseCode)) {//请求成功
            return connection;

        }
        else if (isHttpRedirect(responseCode)) {//url跳转
            String nextUriString = connection.getHeaderField("Location");//获取跳转url
            connection.disconnect();//重定向后，需要断掉之前的链接。

            Uri nextUri = (nextUriString == null) ? null : Uri.parse(nextUriString);
            String originalScheme = uri.getScheme();

            /**
             * 只有小于重定向的次数才会下载
             */
            if (maxRedirects > 0 && nextUri != null && !nextUri.getScheme().equals(originalScheme)) {
                return downloadFrom(nextUri, maxRedirects - 1);
            }
            else {
                String message = maxRedirects == 0
                        ? error("URL %s follows too many redirects", uri.toString())
                        : error("URL %s returned %d without a valid redirect", uri.toString(), responseCode);
                throw new IOException(message);
            }

        }
        else {
            /**
             * 无效的链接，直接断掉
             */
            connection.disconnect();
            throw new IOException(String
                    .format("Image URL %s returned HTTP code %d", uri.toString(), responseCode));
        }
    }

    @VisibleForTesting
    static HttpURLConnection openConnectionTo(Uri uri) throws IOException {
        URL url = new URL(uri.toString());
        return (HttpURLConnection) url.openConnection();
    }

    /**
     * 是否请求成功
     *
     * @param responseCode
     * @return
     */
    private static boolean isHttpSuccess(int responseCode) {
        return (responseCode >= HttpURLConnection.HTTP_OK &&
                responseCode < HttpURLConnection.HTTP_MULT_CHOICE);
    }

    /**
     * 是否是重定向
     *
     * @param responseCode
     * @return
     */
    private static boolean isHttpRedirect(int responseCode) {
        switch (responseCode) {
            case HttpURLConnection.HTTP_MULT_CHOICE:
            case HttpURLConnection.HTTP_MOVED_PERM:
            case HttpURLConnection.HTTP_MOVED_TEMP:
            case HttpURLConnection.HTTP_SEE_OTHER:
            case HTTP_TEMPORARY_REDIRECT:
            case HTTP_PERMANENT_REDIRECT:
                return true;
            default:
                return false;
        }
    }

    private static String error(String format, Object... args) {
        return String.format(Locale.getDefault(), format, args);
    }

}
