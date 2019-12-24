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

import com.icapps.swaggervalidator.parser.*
import io.swagger.models.properties.ArrayProperty
import io.swagger.models.properties.Property
import io.swagger.models.properties.RefProperty
import io.swagger.models.properties.StringProperty
import java.io.IOException
import java.io.Reader

class SwaggerV2Parser : SwaggerParser {

    override fun parse(reader: Reader): SwaggerFile {
        val swagger = io.swagger.parser.SwaggerParser().parse(reader.readText())

        val definitions = swagger.definitions.mapNotNull { (name, model) ->
            val properties = model.properties?.mapNotNull { (propertyName, property) ->

                try {
                    SwaggerProperty(
                        name = propertyName,
                        type = buildPropertyType(property),
                        required = property.required
                    )
                } catch (e: Throwable) {
                    println("Failed to parse swagger definition '$name' -> '$propertyName': ${e.message}")
                    null
                }
            }
            if (properties == null || properties.isEmpty()) {
                println("Skipping '$name', no properties defined")
                return@mapNotNull null
            }
            SwaggerDefinition(name = name, properties = properties)
        }

        return SwaggerFile(definitions)
    }

    private fun buildPropertyType(property: Property): SwaggerPropertyType {
        val referredType = (property as? RefProperty)?.simpleRef
        val arrayType = (property as? ArrayProperty)?.items?.let(::buildPropertyType)
        val format = property.format
        val enums = (property as? StringProperty)?.enum

        val type = when (property.type) {
            "string" -> SwaggerPropertyTypes.STRING
            "number" -> SwaggerPropertyTypes.NUMBER
            "integer" -> SwaggerPropertyTypes.INTEGER
            "boolean" -> SwaggerPropertyTypes.BOOLEAN
            "array" -> SwaggerPropertyTypes.ARRAY
            "ref" -> SwaggerPropertyTypes.REF
            else -> throw IOException("Unknown property type: ${property.type}")
        }

        return SwaggerPropertyType(
            type = type,
            referredType = referredType,
            format = format,
            innerType = arrayType,
            enumValues = enums
        )
    }

}