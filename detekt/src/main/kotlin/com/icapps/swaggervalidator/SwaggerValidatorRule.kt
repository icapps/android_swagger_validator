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
import com.icapps.swaggervalidator.parser.SwaggerProperty
import com.icapps.swaggervalidator.parser.SwaggerPropertyTypes
import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTypeElement
import org.jetbrains.kotlin.psi.KtUserType

class SwaggerValidatorRule() : Rule() {

    override val issue: Issue = Issue(
        javaClass.simpleName,
        Severity.Defect,
        "This rule verifies that known swagger models match the latest spec.",
        Debt.TWENTY_MINS
    )

    private val swaggerFieldNotFound = Issue(
        "SwaggerFieldNotDeclared",
        Severity.Defect,
        "This rules verifies that all fields in the swagger definition have been mapped",
        Debt.TWENTY_MINS
    )
    private val noSuchSwaggerField = Issue(
        "SwaggerFieldNotFound",
        Severity.Defect,
        "This rules verifies that all fields in the model definition also exist in the swagger definition",
        Debt.TWENTY_MINS
    )
    private val fieldCanBeNonNull = Issue(
        "FieldCanBeNonOptional",
        Severity.Maintainability,
        "This rules checks for required swagger fields which are marked as optional in the model",
        Debt.FIVE_MINS
    )
    private val fieldIsOptional = Issue(
        "FieldShouldBeOptional",
        Severity.Defect,
        "This field is marked optional in swagger but not in the model",
        Debt.FIVE_MINS
    )
    private val typeMismatch = Issue(
        "FieldTypesMismatch",
        Severity.Defect,
        "Defined type in model does not match the one defined in swagger",
        Debt.TEN_MINS
    )
    private val stringCouldBeEnum = Issue(
        "StringCouldBeEnum",
        Severity.Maintainability,
        "String could be converted to enum",
        Debt.TEN_MINS
    )

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)

        if (klass.annotationEntries.isNotEmpty()) {
            val swaggerEnumModel = klass.annotationEntries.find { it.shortName?.asString() == "SwaggerEnumModel" }

            if (swaggerEnumModel != null) {
                handleEnum(klass)
                return
            }

            val swaggerModel = klass.annotationEntries.find { it.shortName?.asString() == "SwaggerModel" } ?: return

            val swaggerModelName = extractSwaggerModelName(swaggerModel) ?: return

            PostValidationHelper.markDeclarationValidated(swaggerModelName)

            val constructor = klass.primaryConstructor
            if (constructor == null) {
                report(
                    CodeSmell(
                        issue,
                        Entity.from(klass),
                        "No primary constructor defined for model class!"
                    )
                )
                return
            }

            val modelConstructorParameters = getModelConstructorParameters(constructor)

            val swaggerDefinition = SwaggerDownloader.instance(config = ruleSetConfig).getFuture().get().definitions.find { it.name == swaggerModelName }
            if (swaggerDefinition == null) {
                report(
                    CodeSmell(
                        issue,
                        Entity.from(swaggerModel.navigationElement),
                        "'${swaggerModelName}' model class not found in swagger"
                    )
                )
                return
            }
            //TODO what about defined fields that do not exist in swagger!
            swaggerDefinition.properties.forEach { swaggerProperty ->
                val modelParameter = modelConstructorParameters.find { parameter -> parameter.name == swaggerProperty.name }
                if (modelParameter == null) {
                    report(
                        CodeSmell(
                            swaggerFieldNotFound,
                            Entity.from(klass),
                            "'${swaggerProperty.name}' property defined in swagger not found in model!"
                        )
                    )
                    return@forEach
                }
                validateFormat(modelParameter, swaggerProperty)
            }
            modelConstructorParameters.forEach { modelParameter ->
                if (swaggerDefinition.properties.none { swaggerProperty -> swaggerProperty.name == modelParameter.name }) {
                    report(
                        CodeSmell(
                            swaggerFieldNotFound,
                            Entity.from(modelParameter.element),
                            "'${modelParameter.name}' property defined in model but not in swagger!"
                        )
                    )
                }
            }
        }
    }

    private fun validateFormat(modelParameter: ConstructorParameter, swaggerProperty: SwaggerProperty): Boolean {
        var ok = true
        if (swaggerProperty.required != !modelParameter.type.isNullable) {
            ok = false
            if (swaggerProperty.required) {
                report(
                    CodeSmell(
                        fieldCanBeNonNull,
                        Entity.from(modelParameter.element),
                        "'${swaggerProperty.name}' property is marked non-null in swagger but was nullable in model"
                    )
                )
            } else {
                report(
                    CodeSmell(
                        fieldIsOptional,
                        Entity.from(modelParameter.element),
                        "'${swaggerProperty.name}' property is marked nullable in swagger but was non-null in model"
                    )
                )
            }
        }
        if (!modelParameter.type.isGeneric) {
            validateNonGenericFormat(modelParameter, swaggerProperty)
        } else {
            validateGenericFormat(modelParameter, swaggerProperty)
        }

        return ok
    }

    private fun handleEnum(klass: KtClass) {
        val entries = PsiTreeUtil.findChildrenOfType(klass, KtEnumEntry::class.java)
        val resolvedEntries = entries.map {
            val enumValueName = it.name!!
            val annotation = it.annotationEntries.find { annotation -> annotation.shortName?.asString() == "Json" }
            val annotationOverride = annotation?.let { foundAnnotation ->
                extractStringFieldFromAnnotation(foundAnnotation, "name") {
                    throw IllegalStateException("Got json annotation but not name field! (${Entity.from(foundAnnotation.navigationElement).compact()})")
                }
            }

            annotationOverride ?: enumValueName
        }

        PostValidationHelper.markEnumValidated(klass.name!!, resolvedEntries)
    }

    private fun validateNonGenericFormat(modelParameter: ConstructorParameter, swaggerProperty: SwaggerProperty) {
        when (swaggerProperty.type.type) {
            SwaggerPropertyTypes.STRING -> if (swaggerProperty.type.enumValues != null) {
                if (modelParameter.type.name == "String") {//This is allowed, but could be enum
                    report(
                        CodeSmell(
                            stringCouldBeEnum,
                            Entity.from(modelParameter.element),
                            "Raw string could be converted to an enum"
                        )
                    )
                } else {
                    PostValidationHelper.markRequiredEnum(modelParameter.type.name, this, modelParameter.element, swaggerProperty.type.enumValues)
                }
            } else {
                when (swaggerProperty.type.format) {
                    "date-time", "date" -> reportTypeMismatch(modelParameter.element, listOf("String", "Date", "Calendar"), modelParameter.type.name)
                    "byte" -> reportTypeMismatch(modelParameter.element, listOf("String", "ByteArray"), modelParameter.type.name)
                    else -> reportTypeMismatch(modelParameter.element, "String", modelParameter.type.name)
                }
            }
            SwaggerPropertyTypes.NUMBER -> when (swaggerProperty.type.format) {
                "integer", "int32" -> reportTypeMismatch(modelParameter.element, listOf("Int", "Long"), modelParameter.type.name)
                "int64" -> reportTypeMismatch(modelParameter.element, "Long", modelParameter.type.name)
                "double", "float" -> reportTypeMismatch(modelParameter.element, listOf("Float", "Double"), modelParameter.type.name)
                else -> reportTypeMismatch(modelParameter.element, "Number", modelParameter.type.name)
            }
            SwaggerPropertyTypes.INTEGER -> when (swaggerProperty.type.format) {
                "int64" -> reportTypeMismatch(modelParameter.element, "Long", modelParameter.type.name)
                "integer", "int32", null -> reportTypeMismatch(modelParameter.element, listOf("Int", "Long"), modelParameter.type.name)
                else -> reportTypeMismatch(modelParameter.element, "<error, unknown swagger format for integer>", modelParameter.type.name)
            }
            SwaggerPropertyTypes.BOOLEAN -> reportTypeMismatch(modelParameter.element, "Boolean", modelParameter.type.name)
            SwaggerPropertyTypes.ARRAY -> report(
                CodeSmell(
                    typeMismatch,
                    Entity.from(modelParameter.element),
                    "Model type mismatch, expected a collection type, but got: '$modelParameter.type.name'"
                )
            )
            SwaggerPropertyTypes.REF -> {
                PostValidationHelper.markRequiredDeclarationValidation(swaggerProperty.type.referredType!!, this, modelParameter.element)
            }
        }
    }

    private fun validateGenericFormat(modelParameter: ConstructorParameter, swaggerProperty: SwaggerProperty) {

    }

    private fun reportTypeMismatch(element: PsiElement, expected: String, got: String) {
        if (expected != got) {
            report(
                CodeSmell(
                    typeMismatch,
                    Entity.from(element),
                    "Model type mismatch, expected: '$expected' but got: '$got'"
                )
            )
        }
    }

    private fun reportTypeMismatch(element: PsiElement, expected: Collection<String>, got: String) {
        if (got !in expected) {
            report(
                CodeSmell(
                    typeMismatch,
                    Entity.from(element),
                    "Model type mismatch, expected one of: '$expected' bot got: '$got'"
                )
            )
        }
    }

    private fun getModelConstructorParameters(constructor: KtPrimaryConstructor): List<ConstructorParameter> {
        return constructor.valueParameters.map { parameter ->
            val typeElement = parameter.typeReference?.typeElement!!

            val type = Type.create(typeElement)

            val jsonAnnotation = parameter.annotationEntries.find { annotation -> annotation.shortName?.asString() == "Json" }
            val overrideName = jsonAnnotation?.let { overrideAnnotation ->
                extractStringFieldFromAnnotation(overrideAnnotation, "name") {
                    throw IllegalStateException("Got json annotation but not name field! (${Entity.from(overrideAnnotation.navigationElement).compact()})")
                }
            }
            ConstructorParameter(overrideName ?: parameter.name!!, type, typeElement.navigationElement)
        }
    }

    private fun extractSwaggerModelName(annotation: KtAnnotationEntry): String? {
        return extractStringFieldFromAnnotation(annotation, "name") {
            report(
                CodeSmell(
                    issue,
                    Entity.from(annotation.navigationElement),
                    "Swagger model reference name must be a constant string"
                )
            )
        }
    }

    data class ConstructorParameter(
        val name: String,
        val type: Type,
        val element: PsiElement
    )

}

data class Type(val name: String, val isNullable: Boolean, val isGeneric: Boolean, val genericParameters: List<Type>) {

    companion object {
        fun create(typeElement: KtTypeElement): Type {
            val isNullable = typeElement is KtNullableType

            var innerElement = typeElement
            while (innerElement !is KtUserType) {
                innerElement = when (innerElement) {
                    is KtNullableType -> innerElement.innerType
                        ?: throw IllegalStateException("Type of nullable type not found?!")
                    else -> throw IllegalStateException("Specific type not supported")
                }
            }

            var name = innerElement.referencedName!!
            val genericTypes = innerElement.typeArgumentsAsTypes
            val isGeneric = genericTypes.isNotEmpty()

            //Special case for List<String> which can be parceled
            if (name == "ParcelableStringList") {
                return Type("List", isNullable, true, listOf(Type("String", isNullable = false, isGeneric = false, genericParameters = emptyList())))
            }

            return Type(name, isNullable, isGeneric, genericTypes.map { create(it.typeElement!!) })
        }

    }
}

private fun extractStringFieldFromAnnotation(annotation: KtAnnotationEntry, annotationFieldName: String, messageIfNotString: () -> Unit): String? {
    val name = annotation.valueArguments.find { it.getArgumentName()?.asName?.asString() == annotationFieldName }

    val expression = name?.getArgumentExpression() as? KtStringTemplateExpression
    if (expression == null || expression.hasInterpolation() || expression.entries.size != 1) {
        messageIfNotString()
        return null
    }
    val stringField = (expression.entries[0] as? KtLiteralStringTemplateEntry)?.text
    if (stringField == null) {
        messageIfNotString()
    }
    return stringField
}