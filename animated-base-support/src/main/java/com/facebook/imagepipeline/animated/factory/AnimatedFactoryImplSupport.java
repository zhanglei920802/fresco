/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */
package com.facebook.imagepipeline.animated.factory;

import android.content.res.Resources;

import com.facebook.common.internal.DoNotStrip;
import com.facebook.imagepipeline.animated.impl.AnimatedDrawableBackendProvider;
import com.facebook.imagepipeline.animated.impl.AnimatedDrawableCachingBackendImplProvider;
import com.facebook.imagepipeline.animated.util.AnimatedDrawableUtil;
import com.facebook.imagepipeline.bitmaps.PlatformBitmapFactory;
import com.facebook.imagepipeline.core.ExecutorSupplier;

import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.concurrent.NotThreadSafe;

@NotThreadSafe
@DoNotStrip
public class AnimatedFactoryImplSupport extends AnimatedFactoryImpl {

    @DoNotStrip
    public AnimatedFactoryImplSupport(
            PlatformBitmapFactory platformBitmapFactory,
            ExecutorSupplier executorSupplier) {
        super(platformBitmapFactory,
                executorSupplier);
    }

    @Override
    protected AnimatedDrawableFactory createAnimatedDrawableFactory(
            AnimatedDrawableBackendProvider animatedDrawableBackendProvider,
            AnimatedDrawableCachingBackendImplProvider animatedDrawableCachingBackendImplProvider,
            AnimatedDrawableUtil animatedDrawableUtil,
            ScheduledExecutorService scheduledExecutorService,
            Resources resources) {
        return new AnimatedDrawableFactoryImplSupport(
                animatedDrawableBackendProvider,
                animatedDrawableCachingBackendImplProvider,
                animatedDrawableUtil,
                scheduledExecutorService,
                resources);
    }

}
