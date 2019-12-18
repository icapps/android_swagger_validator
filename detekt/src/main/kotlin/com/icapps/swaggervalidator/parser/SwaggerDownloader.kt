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

package com.icapps.swaggervalidator.parser

import com.icapps.swaggervalidator.parser.v2.SwaggerV2Parser
import io.gitlab.arturbosch.detekt.api.Config
import net.harawata.appdirs.AppDirsFactory
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

class SwaggerDownloader private constructor(private val swaggerParser: SwaggerParser) {

    companion object {
        private const val CACHE_DIR_MAX = 10485760L //10mb

        private var instance: SwaggerDownloader? = null

        fun instance(config: Config): SwaggerDownloader {
            synchronized(this) {
                instance?.let { return it }

                val swaggerUrl = config.valueOrNull<String>("swaggerUrl")
                    ?: throw IllegalArgumentException("Missing swaggerUrl configuration")
                val swaggerVersion = config.valueOrDefault("swaggerVersion", 2)

                val newInstance = SwaggerDownloader(
                    when (swaggerVersion) {
                        2 -> SwaggerV2Parser()
                        else -> throw IllegalStateException("Unsupported swagger version: $swaggerVersion")
                    }
                )
                newInstance.startDownload(swaggerUrl)
                instance = newInstance
                return newInstance
            }
        }

    }

    private val cacheDir = AppDirsFactory.getInstance().getUserCacheDir("detekt-swagger-validator", "1.0", "icapps")

    private val client = OkHttpClient.Builder()
        .cache(Cache(File(cacheDir), CACHE_DIR_MAX))
        .build()

    private val lock = Any()
    private var runningFuture: Future<SwaggerFile>? = null

    fun getFuture(): Future<SwaggerFile> {
        return synchronized(lock) {
            runningFuture!!
        }
    }

    private fun startDownload(url: String): Future<SwaggerFile> {
        return synchronized(lock) {
            runningFuture ?: createDownloadFuture(url)
        }
    }

    private fun createDownloadFuture(url: String): Future<SwaggerFile> {
        val future = CompletableFuture<SwaggerFile>()

        val request = Request.Builder()
            .get()
            .url(url)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                future.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { responseResource ->
                    val body = responseResource.body
                    if (responseResource.isSuccessful && body != null) {
                        try {
                            future.complete(swaggerParser.parse(body.charStream()))
                        } catch (e: Throwable) {
                            future.completeExceptionally(e)
                        }
                    } else {
                        future.completeExceptionally(IOException("Failed to get swagger. Server returned ${responseResource.code} (or no body)"))
                    }
                }
            }
        })

        runningFuture = future
        return future
    }

    fun shutdown() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdownNow()
    }


}