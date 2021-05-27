/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.room.compiler.processing

import kotlin.reflect.KClass

/**
 * This wraps annotations that may be declared in sources, and thus not representable with a
 * compiled type. This is an equivalent to the Java AnnotationMirror API.
 *
 * In comparison, [XAnnotationBox] is used in situations where the annotation class is already
 * compiled and can be referenced. This can be converted with [asAnnotationBox] if the annotation
 * class is already compiled.
 */
interface XAnnotation {
    /**
     * The simple name of the annotation class.
     */
    val simpleName: String

    /**
     * The [XType] representing the annotation class.
     *
     * Accessing this requires resolving the type, and is thus more expensive that just accessing
     * [simpleName].
     */
    val type: XType

    /**
     * Returns the value of the given [methodName], throwing an exception if the method is not
     * found or if the given type [T] does not match the actual type.
     *
     * Note that non primitive types are wrapped by interfaces in order to allow them to be
     * represented by the process:
     * - "Class" types are represented with [XType]
     * - Annotations are represented with [XAnnotation]
     * - Enums are represented with [XEnumEntry]
     *
     * For convenience, wrapper functions are provided for these types, eg [getAsType]
     */
    fun <T> get(methodName: String): T {
        val argument = valueArguments.firstOrNull { it.name == methodName }
            ?: error("No property named $methodName was found in annotation $simpleName")

        @Suppress("UNCHECKED_CAST")
        return argument.value as T
    }

    /**
     * Returns the value of the given [methodName] as a type reference.
     */
    fun getAsType(methodName: String): XType? {
        return get(methodName) as XType?
    }

    /**
     * Returns the value of the given [methodName] as a list of type references.
     */
    fun getAsTypeList(methodName: String): List<XType> {
        return get(methodName) as List<XType>
    }

    /**
     * Returns the value of the given [methodName] as another [XAnnotation].
     */
    fun getAsAnnotation(methodName: String): XAnnotation {
        return get(methodName) as XAnnotation
    }

    /**
     * Returns the value of the given [methodName] as a list of [XAnnotation].
     */
    fun getAsAnnotationList(methodName: String): List<XAnnotation> {
        return get(methodName) as List<XAnnotation>
    }

    /**
     * Returns the value of the given [methodName] as a [XEnumEntry].
     */
    fun getAsEnum(methodName: String): XEnumEntry {
        return get(methodName) as XEnumEntry
    }

    /**
     * Returns the value of the given [methodName] as a list of [XEnumEntry].
     */
    fun getAsEnumList(methodName: String): List<XEnumEntry> {
        return get(methodName) as List<XEnumEntry>
    }

    /**
     * Information about all properties declared in the annotation class.
     */
    val valueArguments: List<XValueArgument>
}

/**
 * Get a representation of this [XAnnotation] as a [XAnnotationBox].
 *
 * Only possible if the annotation class is available (ie it is in the classpath and not in
 * the compiled sources).
 */
inline fun <reified T : Annotation> XAnnotation.asAnnotationBox(): XAnnotationBox<T> {
    return (this as InternalXAnnotation).asAnnotationBox(T::class.java)
}
