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

package com.icapps.swaggervalidator.model

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Finding
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.com.intellij.psi.PsiElement

object PostValidationHelper {

    private val lock = Any()

    private val completedDeclarations = mutableSetOf<String>()
    private val requiredDeclarations = mutableListOf<RequiredDeclarationHolder>()
    private val knownEnums = mutableMapOf<String, List<String>>()
    private val requiredEnums = mutableMapOf<String, MutableList<RequiredEnumDeclarationHolder>>()
    private val validatedEnums = mutableListOf<String>()

    private val missingTypeValidation = Issue(
        "MissingTypeValidation",
        Severity.Maintainability,
        "Referenced swagger type not processed by rule. Did you forget to add annotation?",
        Debt.TEN_MINS
    )
    private val missingEnumValue = Issue(
        "MissingEnumValue",
        Severity.Defect,
        "Swagger references more enum values than defined in the model",
        Debt.TEN_MINS
    )
    private val extraEnumValue = Issue(
        "ExtraEnumValue",
        Severity.Defect,
        "More enum values are defined than are reported in swagger model",
        Debt.TEN_MINS
    )
    private val missingEnumValidation = Issue(
        "MissingEnumValidation",
        Severity.Maintainability,
        "Referenced swagger enum not processed by rule. Did you forget to add annotation?",
        Debt.TEN_MINS
    )

    val completedDeclarationsCount: Int
        get() = synchronized(lock) { completedDeclarations.size }
    val completedEnumsCount: Int
        get() = synchronized(lock) { validatedEnums.size }

    fun markDeclarationValidated(swaggerName: String) {
        synchronized(lock) {
            completedDeclarations += swaggerName
            requiredDeclarations.removeIf { it.swaggerName == swaggerName }
        }
    }

    fun markRequiredDeclarationValidation(swaggerName: String, rule: Rule, originatingElement: PsiElement) {
        synchronized(lock) {
            if (swaggerName in completedDeclarations)
                return
            requiredDeclarations += RequiredDeclarationHolder(swaggerName, rule, originatingElement)
        }
    }

    fun reset() {
        synchronized(lock) {
            requiredDeclarations.clear()
            completedDeclarations.clear()
            knownEnums.clear()
            requiredEnums.clear()
            validatedEnums.clear()
        }
    }

    //TODO notify of enums annotated with SwaggerEnumModel but NOT validated in swagger
    fun notifyMissing(): List<Finding> {
        return synchronized(lock) {
            requiredDeclarations.map { holder ->
                CodeSmell(
                    missingTypeValidation,
                    Entity.Companion.from(holder.originatingElement),
                    "'${holder.swaggerName}' referenced in swagger is not processed by the rule. Did you forget to add annotation?"
                )
            } + requiredEnums.flatMap { (name, holders) ->
                holders.map { holder ->
                    CodeSmell(
                        missingTypeValidation,
                        Entity.Companion.from(holder.originatingElement),
                        "'${name}' enum referenced in model is not processed by the rule. Did you forget to add annotation?"
                    )
                }
            }
        }
    }

    fun markRequiredEnum(name: String, rule: Rule, element: PsiElement, enumValues: List<String>) {
        synchronized(lock) {
            val known = knownEnums[name]
            if (known == null) {
                requiredEnums.getOrPut(name, { mutableListOf() }).add(RequiredEnumDeclarationHolder(enumValues, rule, element))
            } else {
                validateEnum(name, known, enumValues, rule, element)
                validatedEnums += name
                Unit
            }
        }
    }

    fun markEnumValidated(name: String, enumValues: List<String>) {
        synchronized(lock) {
            knownEnums[name] = enumValues
            requiredEnums.remove(name)?.forEach { holder ->
                validateEnum(name, enumValues, holder.enumValues, holder.rule, holder.originatingElement)
                validatedEnums += name
            }
        }
    }

    //TODO what if this enum is being reused... in multiple responses...
    private fun validateEnum(enumName: String, knownValues: List<String>, requiredValues: List<String>, rule: Rule, element: PsiElement) {
        val missingItems = requiredValues.filter { enumValue -> enumValue !in knownValues }
        val extraItems = knownValues.filter { knownValue -> knownValue !in requiredValues }
        if (missingItems.isNotEmpty()) {
            rule.report(
                CodeSmell(
                    missingEnumValue,
                    Entity.Companion.from(element),
                    "'${enumName}' enum class is missing some values defined in swagger: (${missingItems.joinToString(", ")})"
                )
            )
        }
        if (extraItems.isNotEmpty()) {
            rule.report(
                CodeSmell(
                    extraEnumValue,
                    Entity.Companion.from(element),
                    "'${enumName}' enum class is defining more items than swagger: (${extraItems.joinToString(", ")})"
                )
            )
        }
    }

}

private data class RequiredDeclarationHolder(val swaggerName: String, val rule: Rule, val originatingElement: PsiElement)
private data class RequiredEnumDeclarationHolder(val enumValues: List<String>, val rule: Rule, val originatingElement: PsiElement)