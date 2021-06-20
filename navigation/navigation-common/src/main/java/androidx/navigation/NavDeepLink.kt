/*
 * Copyright (C) 2017 The Android Open Source Project
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
package androidx.navigation

import android.net.Uri
import android.os.Bundle
import androidx.annotation.RestrictTo
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * NavDeepLink encapsulates the parsing and matching of a navigation deep link.
 *
 * This should be added to a [NavDestination] using
 * [NavDestination.addDeepLink].
 */
public class NavDeepLink internal constructor(
    /**
     * The uri pattern from the NavDeepLink.
     *
     * @see NavDeepLinkRequest.uri
     */
    public val uriPattern: String?,
    /**
     * The action from the NavDeepLink.
     *
     * @see NavDeepLinkRequest.action
     */
    public val action: String?,
    /**
     * The mimeType from the NavDeepLink.
     *
     * @see NavDeepLinkRequest.mimeType
     */
    public val mimeType: String?
) {
    private val arguments = mutableListOf<String>()
    private val paramArgMap = mutableMapOf<String, ParamQuery>()
    private var pattern: Pattern? = null
    private var isParameterizedQuery = false

    private var mimeTypePattern: Pattern? = null

    public var isExactDeepLink: Boolean = false
        /** @suppress */
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        get
        internal set

    /** @suppress */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public constructor(uri: String) : this(uri, null, null)

    private fun buildPathRegex(
        uri: String,
        uriRegex: StringBuilder,
        fillInPattern: Pattern
    ): Boolean {
        val matcher = fillInPattern.matcher(uri)
        var appendPos = 0
        // Track whether this is an exact deep link
        var exactDeepLink = !uri.contains(".*")
        while (matcher.find()) {
            val argName = matcher.group(1) as String
            arguments.add(argName)
            // Use Pattern.quote() to treat the input string as a literal
            uriRegex.append(Pattern.quote(uri.substring(appendPos, matcher.start())))
            uriRegex.append("([^/]+?)")
            appendPos = matcher.end()
            exactDeepLink = false
        }
        if (appendPos < uri.length) {
            // Use Pattern.quote() to treat the input string as a literal
            uriRegex.append(Pattern.quote(uri.substring(appendPos)))
        }
        // Match either the end of string if all params are optional or match the
        // question mark and 0 or more characters after it
        // We do not use '.*' here because the finalregex would replace it with a quoted
        // version below.
        uriRegex.append("($|(\\?(.)*))")
        return exactDeepLink
    }

    internal fun matches(uri: Uri): Boolean {
        return matches(NavDeepLinkRequest(uri, null, null))
    }

    internal fun matches(deepLinkRequest: NavDeepLinkRequest): Boolean {
        if (!matchUri(deepLinkRequest.uri)) {
            return false
        }
        return if (!matchAction(deepLinkRequest.action)) {
            false
        } else matchMimeType(deepLinkRequest.mimeType)
    }

    private fun matchUri(uri: Uri?): Boolean {
        // If the null status of both are not the same return false.
        return if (uri == null == (pattern != null)) {
            false
        } else uri == null || pattern!!.matcher(uri.toString()).matches()
        // If both are null return true, otherwise see if they match
    }

    private fun matchAction(action: String?): Boolean {
        // If the null status of both are not the same return false.
        return if (action == null == (this.action != null)) {
            false
        } else action == null || this.action == action
        // If both are null return true, otherwise see if they match
    }

    private fun matchMimeType(mimeType: String?): Boolean {
        // If the null status of both are not the same return false.
        return if (mimeType == null == (this.mimeType != null)) {
            false
        } else mimeType == null || mimeTypePattern!!.matcher(mimeType).matches()

        // If both are null return true, otherwise see if they match
    }

    /** @suppress */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public fun getMimeTypeMatchRating(mimeType: String): Int {
        return if (this.mimeType == null || !mimeTypePattern!!.matcher(mimeType).matches()) {
            -1
        } else MimeType(this.mimeType)
            .compareTo(MimeType(mimeType))
    }

    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS", "NullableCollection")
    /** Pattern.compile has no nullability for the regex parameter
     * @suppress
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public fun getMatchingArguments(
        deepLink: Uri,
        arguments: Map<String, NavArgument?>
    ): Bundle? {
        val matcher = pattern!!.matcher(deepLink.toString())
        if (!matcher.matches()) {
            return null
        }
        val bundle = Bundle()
        val size = this.arguments.size
        for (index in 0 until size) {
            val argumentName = this.arguments[index]
            val value = Uri.decode(matcher.group(index + 1))
            val argument = arguments[argumentName]
            if (parseArgument(bundle, argumentName, value, argument)) {
                return null
            }
        }
        if (isParameterizedQuery) {
            for (paramName in paramArgMap.keys) {
                var argMatcher: Matcher? = null
                val storedParam = paramArgMap[paramName]
                val inputParams = deepLink.getQueryParameter(paramName)
                if (inputParams != null) {
                    // Match the input arguments with the saved regex
                    argMatcher = Pattern.compile(storedParam!!.paramRegex).matcher(inputParams)
                    if (!argMatcher.matches()) {
                        return null
                    }
                }
                // Params could have multiple arguments, we need to handle them all
                for (index in 0 until storedParam!!.size()) {
                    var value: String? = null
                    if (argMatcher != null) {
                        value = Uri.decode(argMatcher.group(index + 1))
                    }
                    val argName = storedParam.getArgumentName(index)
                    val argument = arguments[argName]
                    if (value != null && value.replace("[{}]".toRegex(), "") != argName &&
                        parseArgument(bundle, argName, value, argument)
                    ) {
                        return null
                    }
                }
            }
        }

        // Check that all required arguments are present in bundle
        for ((argName, argument) in arguments.entries) {
            val argumentIsRequired = argument != null && !argument.isDefaultValuePresent
            if (argumentIsRequired && !bundle.containsKey(argName)) return null
        }

        return bundle
    }

    private fun parseArgument(
        bundle: Bundle,
        name: String,
        value: String,
        argument: NavArgument?
    ): Boolean {
        if (argument != null) {
            val type = argument.type
            try {
                type.parseAndPut(bundle, name, value)
            } catch (e: IllegalArgumentException) {
                // Failed to parse means this isn't a valid deep link
                // for the given URI - i.e., the URI contains a non-integer
                // value for an integer argument
                return true
            }
        } else {
            bundle.putString(name, value)
        }
        return false
    }

    /**
     * Used to maintain query parameters and the mArguments they match with.
     */
    private class ParamQuery {
        var paramRegex: String? = null
        private val arguments = mutableListOf<String>()

        fun addArgumentName(name: String) {
            arguments.add(name)
        }

        fun getArgumentName(index: Int): String {
            return arguments[index]
        }

        fun size(): Int {
            return arguments.size
        }
    }

    private class MimeType(mimeType: String) : Comparable<MimeType> {
        var type: String
        var subType: String
        override fun compareTo(other: MimeType): Int {
            var result = 0
            // matching just subtypes is 1
            // matching just types is 2
            // matching both is 3
            if (type == other.type) {
                result += 2
            }
            if (subType == other.subType) {
                result++
            }
            return result
        }

        init {
            val typeAndSubType =
                mimeType.split("/".toRegex()).dropLastWhile { it.isEmpty() }
            type = typeAndSubType[0]
            subType = typeAndSubType[1]
        }
    }

    /**
     * A builder for constructing [NavDeepLink] instances.
     */
    public class Builder {

        /** @suppress */
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public constructor()

        private var uriPattern: String? = null
        private var action: String? = null
        private var mimeType: String? = null

        /**
         * Set the uri pattern for the [NavDeepLink].
         *
         * @param uriPattern The uri pattern to add to the NavDeepLink
         *
         * @return This builder.
         */
        public fun setUriPattern(uriPattern: String): Builder {
            this.uriPattern = uriPattern
            return this
        }

        /**
         * Set the action for the [NavDeepLink].
         *
         * @throws IllegalArgumentException if the action is empty.
         *
         * @param action the intent action for the NavDeepLink
         *
         * @return This builder.
         */
        public fun setAction(action: String): Builder {
            // if the action given at runtime is empty we should throw
            require(action.isNotEmpty()) { "The NavDeepLink cannot have an empty action." }
            this.action = action
            return this
        }

        /**
         * Set the mimeType for the [NavDeepLink].
         *
         * @param mimeType the mimeType for the NavDeepLink
         *
         * @return This builder.
         */
        public fun setMimeType(mimeType: String): Builder {
            this.mimeType = mimeType
            return this
        }

        /**
         * Build the [NavDeepLink] specified by this builder.
         *
         * @return the newly constructed NavDeepLink.
         */
        public fun build(): NavDeepLink {
            return NavDeepLink(uriPattern, action, mimeType)
        }

        internal companion object {
            /**
             * Creates a [NavDeepLink.Builder] with a set uri pattern.
             *
             * @param uriPattern The uri pattern to add to the NavDeepLink
             * @return a [Builder] instance
             */
            @JvmStatic
            fun fromUriPattern(uriPattern: String): Builder {
                val builder = Builder()
                builder.setUriPattern(uriPattern)
                return builder
            }

            /**
             * Creates a [NavDeepLink.Builder] with a set action.
             *
             * @throws IllegalArgumentException if the action is empty.
             *
             * @param action the intent action for the NavDeepLink
             * @return a [Builder] instance
             */
            @JvmStatic
            fun fromAction(action: String): Builder {
                // if the action given at runtime is empty we should throw
                require(action.isNotEmpty()) { "The NavDeepLink cannot have an empty action." }
                val builder = Builder()
                builder.setAction(action)
                return builder
            }

            /**
             * Creates a [NavDeepLink.Builder] with a set mimeType.
             *
             * @param mimeType the mimeType for the NavDeepLink
             * @return a [Builder] instance
             */
            @JvmStatic
            fun fromMimeType(mimeType: String): Builder {
                val builder = Builder()
                builder.setMimeType(mimeType)
                return builder
            }
        }
    }

    private companion object {
        private val SCHEME_PATTERN = Pattern.compile("^[a-zA-Z]+[+\\w\\-.]*:")
    }

    init {
        if (uriPattern != null) {
            val parameterizedUri = Uri.parse(uriPattern)
            isParameterizedQuery = parameterizedUri.query != null
            val uriRegex = StringBuilder("^")
            if (!SCHEME_PATTERN.matcher(uriPattern).find()) {
                uriRegex.append("http[s]?://")
            }
            @Suppress("RegExpRedundantEscape")
            val fillInPattern = Pattern.compile("\\{(.+?)\\}")
            if (isParameterizedQuery) {
                var matcher = Pattern.compile("(\\?)").matcher(uriPattern)
                if (matcher.find()) {
                    buildPathRegex(
                        uriPattern.substring(0, matcher.start()),
                        uriRegex,
                        fillInPattern
                    )
                }
                isExactDeepLink = false
                for (paramName in parameterizedUri.queryParameterNames) {
                    val argRegex = StringBuilder()
                    val queryParam = parameterizedUri.getQueryParameter(paramName) as String
                    matcher = fillInPattern.matcher(queryParam)
                    var appendPos = 0
                    val param = ParamQuery()
                    // Build the regex for each query param
                    while (matcher.find()) {
                        param.addArgumentName(matcher.group(1) as String)
                        argRegex.append(
                            Pattern.quote(
                                queryParam.substring(
                                    appendPos,
                                    matcher.start()
                                )
                            )
                        )
                        argRegex.append("(.+?)?")
                        appendPos = matcher.end()
                    }
                    if (appendPos < queryParam.length) {
                        argRegex.append(Pattern.quote(queryParam.substring(appendPos)))
                    }
                    // Save the regex with wildcards unquoted, and add the param to the map with its
                    // name as the key
                    param.paramRegex = argRegex.toString().replace(".*", "\\E.*\\Q")
                    paramArgMap[paramName] = param
                }
            } else {
                isExactDeepLink = buildPathRegex(uriPattern, uriRegex, fillInPattern)
            }
            // Since we've used Pattern.quote() above, we need to
            // specifically escape any .* instances to ensure
            // they are still treated as wildcards in our final regex
            val finalRegex = uriRegex.toString().replace(".*", "\\E.*\\Q")
            pattern = Pattern.compile(finalRegex, Pattern.CASE_INSENSITIVE)
        }
        if (mimeType != null) {
            val mimeTypePattern = Pattern.compile("^[\\s\\S]+/[\\s\\S]+$")
            val mimeTypeMatcher = mimeTypePattern.matcher(mimeType)
            require(mimeTypeMatcher.matches()) {
                "The given mimeType $mimeType does not match to required \"type/subtype\" format"
            }

            // get the type and subtype of the mimeType
            val splitMimeType = MimeType(
                mimeType
            )

            // the matching pattern can have the exact name or it can be wildcard literal (*)
            val mimeTypeRegex = "^(${splitMimeType.type}|[*]+)/(${splitMimeType.subType}|[*]+)$"

            // if the deep link type or subtype is wildcard, allow anything
            val finalRegex = mimeTypeRegex.replace("*|[*]", "[\\s\\S]")
            this.mimeTypePattern = Pattern.compile(finalRegex)
        }
    }
}
