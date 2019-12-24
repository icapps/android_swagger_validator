/*
 * Copyright (C) 2019 icapps
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.icapps.swaggervalidator.parser.v2

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SwaggerV2ParserTest {

    @Test
    fun testParse() {
        val swaggerFile = OkHttpClient.Builder()
            .build()
            .newCall(Request.Builder()
                .get()
                .url("https://raw.githubusercontent.com/OAI/OpenAPI-Specification/master/examples/v2.0/json/petstore-expanded.json")
                .build())
            .execute().body?.use { body ->
            SwaggerV2Parser().parse(body.charStream());
        }
        println(swaggerFile)
    }

}