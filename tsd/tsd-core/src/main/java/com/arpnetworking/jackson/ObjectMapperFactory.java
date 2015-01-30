/**
 * Copyright 2014 Groupon.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.arpnetworking.jackson;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.joda.JodaModule;
import com.google.common.base.Optional;

/**
 * Create a "standard" <code>ObjectMapper</code> instance.
 *
 * @author Ville Koskela (vkoskela at groupon dot com)
 */
public final class ObjectMapperFactory {

    /**
     * Create a new <code>ObjectMapper</code> configured with standard
     * settings. New instances can be safely customized by clients.
     *
     * @return New mutable <code>ObjectMapper</code> instance.
     */
    public static ObjectMapper createInstance() {
        return createModifiableObjectMapper("TsdCoreStandard");
    }

    /**
     * Get <code>ObjectMapper</code> instance configured with standard
     * settings. These instances are considered shared and are immutable.
     *
     * @return Shared immutable <code>ObjectMapper</code> instance.
     */
    public static ObjectMapper getInstance() {
        return UNMODIFIABLE_OBJECT_MAPPER;
    }

    private static ObjectMapper createModifiableObjectMapper(final String name) {
        final ObjectMapper objectMapper = new ObjectMapper();
        final SimpleModule module = new SimpleModule(name);
        module.addSerializer(Optional.class, OptionalSerializer.newInstance());
        objectMapper.registerModule(module);
        objectMapper.registerModule(new JodaModule());
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.configure(DeserializationFeature.WRAP_EXCEPTIONS, false);
        objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        return objectMapper;
    }

    private ObjectMapperFactory() {}

    private static final ObjectMapper UNMODIFIABLE_OBJECT_MAPPER = ImmutableObjectMapper.of(
            createModifiableObjectMapper("SharedTsdCoreStandard"));
}
