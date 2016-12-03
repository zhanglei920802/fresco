/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.datasource;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

@RunWith(RobolectricTestRunner.class)
public class DataSourcesTest {
    private Exception mException;

    @Before
    public void setUp() {
        mException = mock(Exception.class);
    }

    @Test
    public void testImmediateFailedDataSource() {
        DataSource<?> dataSource = DataSources.immediateFailedDataSource(mException);
        assertTrue(dataSource.isFinished());
        assertTrue(dataSource.hasFailed());
        assertEquals(mException, dataSource.getFailureCause());
        assertFalse(dataSource.hasResult());
        assertFalse(dataSource.isClosed());
    }
}
