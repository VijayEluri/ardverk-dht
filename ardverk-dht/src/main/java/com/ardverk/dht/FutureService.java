/*
 * Copyright 2009-2010 Roger Kapsi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ardverk.dht;

import java.util.concurrent.TimeUnit;

import com.ardverk.dht.concurrent.ArdverkFuture;
import com.ardverk.dht.concurrent.ArdverkProcess;
import com.ardverk.dht.config.Config;

/**
 * The {@link FutureService} is providing an interface 
 * for executing {@link ArdverkProcess}es.
 */
interface FutureService {

    /**
     * Submits the given {@link ArdverkProcess} for execution.
     */
    public <V> ArdverkFuture<V> submit(
            ArdverkProcess<V> process, Config config);
    
    /**
     * Submits the given {@link ArdverkProcess} for execution.
     */
    public <V> ArdverkFuture<V> submit(ExecutorKey executorKey, 
            ArdverkProcess<V> process, long timeout, TimeUnit unit);
}