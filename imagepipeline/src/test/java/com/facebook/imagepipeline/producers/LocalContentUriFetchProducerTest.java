/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.producers;

import android.content.ContentResolver;
import android.net.Uri;

import com.facebook.imagepipeline.common.Priority;
import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imagepipeline.memory.PooledByteBuffer;
import com.facebook.imagepipeline.memory.PooledByteBufferFactory;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.testing.FakeClock;
import com.facebook.imagepipeline.testing.TestExecutorService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.InputStream;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.notNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

/**
 * Basic tests for LocalContentUriFetchProducer
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class LocalContentUriFetchProducerTest {
    private static final String PRODUCER_NAME = LocalContentUriFetchProducer.PRODUCER_NAME;
    private final String mRequestId = "mRequestId";
    @Mock
    public PooledByteBufferFactory mPooledByteBufferFactory;
    @Mock
    public ContentResolver mContentResolver;
    @Mock
    public Consumer<EncodedImage> mConsumer;
    @Mock
    public ImageRequest mImageRequest;
    @Mock
    public ProducerListener mProducerListener;
    @Mock
    public Exception mException;
    private TestExecutorService mExecutor;
    private SettableProducerContext mProducerContext;
    private Uri mContentUri;
    private LocalContentUriFetchProducer mLocalContentUriFetchProducer;
    private EncodedImage mCapturedEncodedImage;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mExecutor = new TestExecutorService(new FakeClock());
        mLocalContentUriFetchProducer = new LocalContentUriFetchProducer(
                mExecutor,
                mPooledByteBufferFactory,
                mContentResolver,
                false);
        mContentUri = Uri.fromFile(mock(File.class));

        mProducerContext = new SettableProducerContext(
                mImageRequest,
                mRequestId,
                mProducerListener,
                mock(Object.class),
                ImageRequest.RequestLevel.FULL_FETCH,
                false,
                true,
                Priority.MEDIUM);
        when(mImageRequest.getSourceUri()).thenReturn(mContentUri);
        doAnswer(
                new Answer() {
                    @Override
                    public Object answer(InvocationOnMock invocation) throws Throwable {
                        mCapturedEncodedImage =
                                EncodedImage.cloneOrNull((EncodedImage) invocation.getArguments()[0]);
                        return null;
                    }
                })
                .when(mConsumer)
                .onNewResult(notNull(EncodedImage.class), anyBoolean());
    }

    @Test
    public void testLocalContentUriFetchCancelled() {
        mLocalContentUriFetchProducer.produceResults(mConsumer, mProducerContext);
        mProducerContext.cancel();
        verify(mProducerListener).onProducerStart(mRequestId, PRODUCER_NAME);
        verify(mProducerListener).onProducerFinishWithCancellation(mRequestId, PRODUCER_NAME, null);
        verify(mConsumer).onCancellation();
        mExecutor.runUntilIdle();
        verifyZeroInteractions(mPooledByteBufferFactory);
    }

    @Test
    public void testFetchLocalContentUri() throws Exception {
        PooledByteBuffer pooledByteBuffer = mock(PooledByteBuffer.class);
        when(mPooledByteBufferFactory.newByteBuffer(any(InputStream.class)))
                .thenReturn(pooledByteBuffer);

        when(mContentResolver.openInputStream(mContentUri)).thenReturn(mock(InputStream.class));
        mLocalContentUriFetchProducer.produceResults(mConsumer, mProducerContext);

        mExecutor.runUntilIdle();
    }

    @Test(expected = RuntimeException.class)
    public void testFetchLocalContentUriFailsByThrowing() throws Exception {
        when(mPooledByteBufferFactory.newByteBuffer(any(InputStream.class))).thenThrow(mException);
        verify(mConsumer).onFailure(mException);
        verify(mProducerListener).onProducerStart(mRequestId, PRODUCER_NAME);
        verify(mProducerListener).onProducerFinishWithFailure(
                mRequestId, PRODUCER_NAME, mException, null);
    }
}
