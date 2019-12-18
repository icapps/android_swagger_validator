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

package com.icapps.swaggervalidator

import com.icapps.swaggervalidator.model.PostValidationHelper
import com.icapps.swaggervalidator.parser.SwaggerDownloader
import com.icapps.swaggervalidator.parser.SwaggerFile
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Detektion
import io.gitlab.arturbosch.detekt.api.FileProcessListener
import io.gitlab.arturbosch.detekt.api.ProjectMetric
import org.jetbrains.kotlin.psi.KtFile
import java.util.concurrent.Future
import kotlin.system.exitProcess

class SwaggerRuleProcessor : FileProcessListener {

    private var future: Future<SwaggerFile>? = null

    override val priority: Int
        get() = 100000

    private var swaggerDownloader: SwaggerDownloader? = null

    override fun onStart(files: List<KtFile>) {
        future = swaggerDownloader?.getFuture()
        PostValidationHelper.reset()

        super.onStart(files)
    }

    override fun init(config: Config) {
        super.init(config)

        val swaggerConfig = config.subConfig("SwaggerValidator")
        swaggerDownloader = SwaggerDownloader.instance(swaggerConfig)
    }

    override fun onFinish(files: List<KtFile>, result: Detektion) {
        super.onFinish(files, result)

        future?.get() ?: throw IllegalStateException("Download not started")
        swaggerDownloader?.shutdown()

        val missingReport = PostValidationHelper.notifyMissing()
        if (missingReport.isNotEmpty()) {
            println("Additional issues found while processing swagger. Sorry but we will have to kill the process")
        }
        missingReport.forEach {
            println("\t${it.compact()}")
        }

        result.add(ProjectMetric("number of swagger models validated", PostValidationHelper.completedDeclarationsCount))
        result.add(ProjectMetric("number of swagger enums validated", PostValidationHelper.completedEnumsCount))

        PostValidationHelper.reset()
        if (missingReport.isNotEmpty())
            exitProcess(-1)
    }
}