/*
 * Copyright 2009-2011 Roger Kapsi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ardverk.dht.config;

import java.util.concurrent.TimeUnit;

public class DefaultConfig extends AbstractConfig {
    
    private volatile long operationTimeoutInMillis;
    
    public DefaultConfig() {
    }
    
    public DefaultConfig(long timeout, TimeUnit unit) {
        setOperationTimeout(timeout, unit);
    }
    
    @Override
    public void setOperationTimeout(long timeout, TimeUnit unit) {
        this.operationTimeoutInMillis = unit.toMillis(timeout);
    }
    
    @Override
    public long getOperationTimeout(TimeUnit unit) {
        return unit.convert(operationTimeoutInMillis, TimeUnit.MILLISECONDS);
    }
}