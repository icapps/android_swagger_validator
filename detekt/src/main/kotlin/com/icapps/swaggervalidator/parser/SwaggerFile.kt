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

//Parsed version
data class SwaggerFile(val definitions: List<SwaggerDefinition>)

data class SwaggerDefinition(val name: String, val properties: List<SwaggerProperty>)

data class SwaggerProperty(val name: String, val type: SwaggerPropertyType, val required: Boolean)

data class SwaggerPropertyType(
    val type: SwaggerPropertyTypes,
    val referredType: String?,
    val format: String?,
    val innerType: SwaggerPropertyType?,
    val enumValues: List<String>?
)

enum class SwaggerPropertyTypes {
    STRING,
    NUMBER,
    INTEGER,
    BOOLEAN,
    ARRAY,
    REF
}