/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.producers;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.mockito.Mockito.verify;

/**
 * Checks basic properties of NullProducer, that is that it always returns null.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class NullProducerTest {
    @Mock
    public Consumer mConsumer;
    @Mock
    public ProducerContext mProducerContext;
    @Mock
    public ProducerListener mProducerListener;
    private NullProducer mNullProducer;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mNullProducer = new NullProducer();
    }

    @Test
    public void testNullProducerReturnsNull() {
        mNullProducer.produceResults(mConsumer, mProducerContext);
        verify(mConsumer).onNewResult(null, true);
    }
}
