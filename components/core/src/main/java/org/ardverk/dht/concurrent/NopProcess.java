/*
 * Copyright 2009-2012 Roger Kapsi
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

package org.ardverk.dht.concurrent;

import org.ardverk.concurrent.AsyncProcessFuture;

/**
 * An implementation of {@link DHTProcess} that does nothing.
 */
public class NopProcess<V> implements DHTProcess<V> {

  private static final DHTProcess<Object> NOP 
    = new NopProcess<Object>();
  
  @SuppressWarnings("unchecked")
  public static <V> DHTProcess<V> create() {
    return (DHTProcess<V>)NOP;
  }
  
  private NopProcess() {}
  
  @Override
  public void start(AsyncProcessFuture<V> future) {
  }
}