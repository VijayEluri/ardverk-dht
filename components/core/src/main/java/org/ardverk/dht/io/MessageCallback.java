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

package org.ardverk.dht.io;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.ardverk.dht.message.ResponseMessage;


/**
 * The {@link MessageCallback} is called by the {@link MessageDispatcher}
 * for {@link ResponseMessage}s.
 */
public interface MessageCallback {

  /**
   * Called for a {@link ResponseMessage}.
   */
  public boolean handleResponse(RequestEntity entity, ResponseMessage response, 
      long time, TimeUnit unit) throws IOException;
  
  /**
   * Called if a timeout occurred.
   */
  public void handleTimeout(RequestEntity entity, 
      long time, TimeUnit unit) throws IOException;
  
  /**
   * 
   */
  public void handleIllegalResponse(RequestEntity entity, 
      ResponseMessage response, long time, TimeUnit unit) throws IOException;
  
  /**
   * Called if an {@link Exception} occurred.
   */
  public void handleException(RequestEntity entity, Throwable exception);
}