/**
 * Copyright 2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.rest;

/**
 * RestResponseHandlerFactory is a factory to generate all the supporting cast required to instantiate a
 * {@link RestResponseHandler}.
 * <p/>
 * Usually called with the canonical class name and as such might have to support appropriate (multiple) constructors.
 */
public interface RestResponseHandlerFactory {

  /**
   * Returns an instance of the {@link RestResponseHandler} that the factory generates.
   * @return an instance of {@link RestResponseHandler} generated by this factory.
   * @throws InstantiationException if the {@link RestResponseHandler} instance cannot be created.
   */
  public RestResponseHandler getRestResponseHandler()
      throws InstantiationException;
}
