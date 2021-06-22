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

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.util.AttributeSet
import androidx.annotation.CallSuper
import androidx.annotation.IdRes
import androidx.annotation.RestrictTo
import androidx.collection.SparseArrayCompat
import androidx.core.content.res.use
import androidx.navigation.common.R
import kotlin.reflect.KClass

/**
 * NavDestination represents one node within an overall navigation graph.
 *
 * Each destination is associated with a [Navigator] which knows how to navigate to this
 * particular destination.
 *
 * Destinations declare a set of [actions][putAction] that they
 * support. These actions form a navigation API for the destination; the same actions declared
 * on different destinations that fill similar roles allow application code to navigate based
 * on semantic intent.
 *
 * Each destination has a set of [arguments][arguments] that will
 * be applied when [navigating][NavController.navigate] to that destination.
 * Any default values for those arguments can be overridden at the time of navigation.
 *
 * NavDestinations should be created via [Navigator.createDestination].
 */
public open class NavDestination(
    /**
     * The name associated with this destination's [Navigator].
     */
    public val navigatorName: String
) {
    /**
     * This optional annotation allows tooling to offer auto-complete for the
     * `android:name` attribute. This should match the class type passed to
     * [parseClassFromName] when parsing the
     * `android:name` attribute.
     */
    @kotlin.annotation.Retention(AnnotationRetention.BINARY)
    @Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
    public annotation class ClassType(val value: KClass<*>)

    /** @suppress */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public class DeepLinkMatch(
        public val destination: NavDestination,
        @get:Suppress("NullableCollection") // Needed for nullable bundle
        public val matchingArgs: Bundle?,
        private val isExactDeepLink: Boolean,
        private val hasMatchingAction: Boolean,
        private val mimeTypeMatchLevel: Int
    ) : Comparable<DeepLinkMatch> {
        override fun compareTo(other: DeepLinkMatch): Int {
            // Prefer exact deep links
            if (isExactDeepLink && !other.isExactDeepLink) {
                return 1
            } else if (!isExactDeepLink && other.isExactDeepLink) {
                return -1
            }
            if (matchingArgs != null && other.matchingArgs == null) {
                return 1
            } else if (matchingArgs == null && other.matchingArgs != null) {
                return -1
            }
            if (matchingArgs != null) {
                val sizeDifference = matchingArgs.size() - other.matchingArgs!!.size()
                if (sizeDifference > 0) {
                    return 1
                } else if (sizeDifference < 0) {
                    return -1
                }
            }
            if (hasMatchingAction && !other.hasMatchingAction) {
                return 1
            } else if (!hasMatchingAction && other.hasMatchingAction) {
                return -1
            }
            return mimeTypeMatchLevel - other.mimeTypeMatchLevel
        }
    }

    /**
     * Gets the [NavGraph] that contains this destination. This will be set when a
     * destination is added to a NavGraph via [NavGraph.addDestination].
     */
    public var parent: NavGraph? = null
        /** @suppress */
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public set
    private var idName: String? = null

    /**
     * The descriptive label of this destination.
     */
    public var label: CharSequence? = null
    private val deepLinks = mutableListOf<NavDeepLink>()
    private val actions: SparseArrayCompat<NavAction> = SparseArrayCompat()

    private var _arguments: MutableMap<String, NavArgument> = mutableMapOf()
    /**
     * The arguments supported by this destination. Returns a read-only map of argument names
     * to [NavArgument] objects that can be used to check the type, default value
     * and nullability of the argument.
     *
     * To add and remove arguments for this NavDestination
     * use [addArgument] and [removeArgument].
     * @return Read-only map of argument names to arguments.
     */
    public val arguments: Map<String, NavArgument>
        get() = _arguments.toMap()

    /**
     * NavDestinations should be created via [Navigator.createDestination].
     *
     * This constructor requires that the given Navigator has a [Navigator.Name] annotation.
     */
    public constructor(navigator: Navigator<out NavDestination>) : this(
        NavigatorProvider.getNameForNavigator(
            navigator.javaClass
        )
    )

    /**
     * Called when inflating a destination from a resource.
     *
     * @param context local context performing inflation
     * @param attrs attrs to parse during inflation
     */
    @CallSuper
    public open fun onInflate(context: Context, attrs: AttributeSet) {
        context.resources.obtainAttributes(attrs, R.styleable.Navigator).use { array ->
            route = array.getString(R.styleable.Navigator_route)

            if (array.hasValue(R.styleable.Navigator_android_id)) {
                id = array.getResourceId(R.styleable.Navigator_android_id, 0)
                idName = getDisplayName(context, id)
            }
            label = array.getText(R.styleable.Navigator_android_label)
        }
    }

    /**
     * The destination's unique ID. This should be an ID resource generated by
     * the Android resource system.
     */
    @get:IdRes
    public var id: Int = 0
        set(@IdRes id) {
            field = id
            idName = null
        }

    /**
     * The destination's unique route. Setting this will also update the [id] of the destinations
     * so custom destination ids should only be set after setting the route.
     *
     * @return this destination's route, or null if no route is set
     *
     * @throws IllegalArgumentException is the given route is empty
     */
    public var route: String? = null
        set(route) {
            if (route == null) {
                id = 0
            } else {
                require(route.isNotBlank()) { "Cannot have an empty route" }
                val internalRoute = createRoute(route)
                id = internalRoute.hashCode()
                addDeepLink(internalRoute)
            }
            deepLinks.remove(deepLinks.firstOrNull { it.uriPattern == createRoute(field) })
            field = route
        }

    /**
     * @hide
     */
    @get:RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public open val displayName: String
        get() = idName ?: id.toString()

    /**
     * Checks the given deep link [Uri], and determines whether it matches a Uri pattern added
     * to the destination by a call to [addDeepLink] . It returns `true`
     * if the deep link is a valid match, and `false` otherwise.
     *
     * This should be called prior to [NavController.navigate] to ensure the deep link
     * can be navigated to.
     *
     * @param deepLink to the destination reachable from the current NavGraph
     * @return True if the deepLink exists for the destination.
     * @see NavDestination.addDeepLink
     * @see NavController.navigate
     * @see NavDestination.hasDeepLink
     */
    public open fun hasDeepLink(deepLink: Uri): Boolean {
        return hasDeepLink(NavDeepLinkRequest(deepLink, null, null))
    }

    /**
     * Checks the given [NavDeepLinkRequest], and determines whether it matches a
     * [NavDeepLink] added to the destination by a call to
     * [addDeepLink]. It returns `true` if the request is a valid
     * match, and `false` otherwise.
     *
     * This should be called prior to [NavController.navigate] to
     * ensure the deep link can be navigated to.
     *
     * @param deepLinkRequest to the destination reachable from the current NavGraph
     * @return True if the deepLink exists for the destination.
     * @see NavDestination.addDeepLink
     * @see NavController.navigate
     */
    public open fun hasDeepLink(deepLinkRequest: NavDeepLinkRequest): Boolean {
        return matchDeepLink(deepLinkRequest) != null
    }

    /**
     * Add a deep link to this destination. Matching Uris sent to
     * [NavController.handleDeepLink] or [NavController.navigate] will
     * trigger navigating to this destination.
     *
     * In addition to a direct Uri match, the following features are supported:
     *
     * Uris without a scheme are assumed as http and https. For example,
     * `www.example.com` will match `http://www.example.com` and
     * `https://www.example.com`.
     * Placeholders in the form of `{placeholder_name}` matches 1 or more
     * characters. The String value of the placeholder will be available in the arguments
     * [Bundle] with a key of the same name. For example,
     * `http://www.example.com/users/{id}` will match
     * `http://www.example.com/users/4`.
     * The `.*` wildcard can be used to match 0 or more characters.
     *
     * These Uris can be declared in your navigation XML files by adding one or more
     * `<deepLink app:uri="uriPattern" />` elements as
     * a child to your destination.
     *
     * Deep links added in navigation XML files will automatically replace instances of
     * `${applicationId}` with the applicationId of your app.
     * Programmatically added deep links should use [Context.getPackageName] directly
     * when constructing the uriPattern.
     * @param uriPattern The uri pattern to add as a deep link
     * @see NavController.handleDeepLink
     * @see NavController.navigate
     * @see NavDestination.addDeepLink
     */
    public fun addDeepLink(uriPattern: String) {
        addDeepLink(NavDeepLink.Builder().setUriPattern(uriPattern).build())
    }

    /**
     * Add a deep link to this destination. Uris that match the given [NavDeepLink] uri
     * sent to [NavController.handleDeepLink] or
     * [NavController.navigate] will trigger navigating to this
     * destination.
     *
     * In addition to a direct Uri match, the following features are supported:
     *
     * Uris without a scheme are assumed as http and https. For example,
     * `www.example.com` will match `http://www.example.com` and
     * `https://www.example.com`.
     * Placeholders in the form of `{placeholder_name}` matches 1 or more
     * characters. The String value of the placeholder will be available in the arguments
     * [Bundle] with a key of the same name. For example,
     * `http://www.example.com/users/{id}` will match
     * `http://www.example.com/users/4`.
     * The `.*` wildcard can be used to match 0 or more characters.
     *
     * These Uris can be declared in your navigation XML files by adding one or more
     * `<deepLink app:uri="uriPattern" />` elements as
     * a child to your destination.
     *
     * Custom actions and mimetypes are also supported by [NavDeepLink] and can be declared
     * in your navigation XML files by adding
     * `<app:action="android.intent.action.SOME_ACTION" />` or
     * `<app:mimetype="type/subtype" />` as part of your deepLink declaration.
     *
     * Deep link Uris, actions, and mimetypes added in navigation XML files will automatically
     * replace instances of `${applicationId}` with the applicationId of your app.
     * Programmatically added deep links should use [Context.getPackageName] directly
     * when constructing the uriPattern.
     *
     * When matching deep links for calls to [NavController.handleDeepLink] or
     * [NavController.navigate] the order of precedence is as follows:
     * the deep link with the most matching arguments will be chosen, followed by the deep link
     * with a matching action, followed by the best matching mimeType (e.i. when matching
     * mimeType image/jpg: image/ * > *\/jpg > *\/ *).
     * @param navDeepLink The NavDeepLink to add as a deep link
     * @see NavController.handleDeepLink
     * @see NavController.navigate
     */
    public fun addDeepLink(navDeepLink: NavDeepLink) {
        val missingRequiredArguments = arguments.filterValues { !it.isDefaultValuePresent }
            .keys
            .filter { it !in navDeepLink.arguments }
        require(missingRequiredArguments.isEmpty()) {
            "Deep link ${navDeepLink.uriPattern} can't be used to open destination $this.\n" +
                "Following required arguments are missing: $missingRequiredArguments"
        }

        deepLinks.add(navDeepLink)
    }

    /**
     * Determines if this NavDestination has a deep link matching the given Uri.
     * @param navDeepLinkRequest The request to match against all deep links added in
     * [addDeepLink]
     * @return The matching [NavDestination] and the appropriate [Bundle] of arguments
     * extracted from the Uri, or null if no match was found.
     * @suppress
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public open fun matchDeepLink(navDeepLinkRequest: NavDeepLinkRequest): DeepLinkMatch? {
        if (deepLinks.isEmpty()) {
            return null
        }
        var bestMatch: DeepLinkMatch? = null
        for (deepLink in deepLinks) {
            val uri = navDeepLinkRequest.uri
            val matchingArguments =
                if (uri != null) deepLink.getMatchingArguments(uri, arguments) else null
            val requestAction = navDeepLinkRequest.action
            val matchingAction = requestAction != null && requestAction ==
                deepLink.action
            val mimeType = navDeepLinkRequest.mimeType
            val mimeTypeMatchLevel =
                if (mimeType != null) deepLink.getMimeTypeMatchRating(mimeType) else -1
            if (matchingArguments != null || matchingAction || mimeTypeMatchLevel > -1) {
                val newMatch = DeepLinkMatch(
                    this, matchingArguments,
                    deepLink.isExactDeepLink, matchingAction, mimeTypeMatchLevel
                )
                if (bestMatch == null || newMatch > bestMatch) {
                    bestMatch = newMatch
                }
            }
        }
        return bestMatch
    }
    /**
     * Build an array containing the hierarchy from the root down to this destination.
     *
     * @param previousDestination the previous destination we are starting at
     * @return An array containing all of the ids from the previous destination (or the root of
     * the graph if null) to this destination
     * @suppress
     */
    @JvmOverloads
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public fun buildDeepLinkIds(previousDestination: NavDestination? = null): IntArray {
        val hierarchy = ArrayDeque<NavDestination>()
        var current: NavDestination? = this
        do {
            val parent = current!!.parent
            if (
                previousDestination?.parent != null && previousDestination.parent!!.findNode(
                        current.id
                    ) === current
            ) {
                hierarchy.addFirst(current)
                break
            }
            if (parent == null || parent.startDestinationId != current.id) {
                hierarchy.addFirst(current)
            }
            current = parent
        } while (current != null)
        return hierarchy.toList().map { it.id }.toIntArray()
    }

    /**
     * @return Whether this NavDestination supports outgoing actions
     * @see NavDestination.putAction
     * @suppress
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public open fun supportsActions(): Boolean {
        return true
    }

    /**
     * Returns the [NavAction] for the given action ID. This will recursively check the
     * [parent][getParent] of this destination if the action destination is not found in
     * this destination.
     *
     * @param id action ID to fetch
     * @return the [NavAction] mapped to the given action id, or null if one has not been set
     */
    public fun getAction(@IdRes id: Int): NavAction? {
        val destination = if (actions.isEmpty) null else actions[id]
        // Search the parent for the given action if it is not found in this destination
        return destination ?: parent?.run { getAction(id) }
    }

    /**
     * Creates a [NavAction] for the given [destId] and associates it with the [actionId].
     *
     * @param actionId action ID to bind
     * @param destId destination ID for the given action
     */
    public fun putAction(@IdRes actionId: Int, @IdRes destId: Int) {
        putAction(actionId, NavAction(destId))
    }

    /**
     * Sets the [NavAction] destination for an action ID.
     *
     * @param actionId action ID to bind
     * @param action action to associate with this action ID
     * @throws UnsupportedOperationException this destination is considered a terminal destination
     * and does not support actions
     */
    public fun putAction(@IdRes actionId: Int, action: NavAction) {
        if (!supportsActions()) {
            throw UnsupportedOperationException(
                "Cannot add action $actionId to $this as it does not support actions, " +
                    "indicating that it is a terminal destination in your navigation graph and " +
                    "will never trigger actions."
            )
        }
        require(actionId != 0) { "Cannot have an action with actionId 0" }
        actions.put(actionId, action)
    }

    /**
     * Unsets the [NavAction] for an action ID.
     *
     * @param actionId action ID to remove
     */
    public fun removeAction(@IdRes actionId: Int) {
        actions.remove(actionId)
    }

    /**
     * Sets an argument type for an argument name
     *
     * @param argumentName argument object to associate with destination
     * @param argument argument object to associate with destination
     */
    public fun addArgument(argumentName: String, argument: NavArgument) {
        _arguments[argumentName] = argument
    }

    /**
     * Unsets the argument type for an argument name.
     *
     * @param argumentName argument to remove
     */
    public fun removeArgument(argumentName: String) {
        _arguments.remove(argumentName)
    }

    /**
     * Combines the default arguments for this destination with the arguments provided
     * to construct the final set of arguments that should be used to navigate
     * to this destination.
     * @suppress
     */
    @Suppress("NullableCollection") // Needed for nullable bundle
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public fun addInDefaultArgs(args: Bundle?): Bundle? {
        if (args == null && _arguments.isNullOrEmpty()) {
            return null
        }
        val defaultArgs = Bundle()
        for ((key, value) in _arguments) {
            value.putDefaultValue(key, defaultArgs)
        }
        if (args != null) {
            defaultArgs.putAll(args)
            for ((key, value) in _arguments) {
                require(value.verify(key, defaultArgs)) {
                    "Wrong argument type for '$key' in argument bundle. ${value.type.name} " +
                        "expected."
                }
            }
        }
        return defaultArgs
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append(javaClass.simpleName)
        sb.append("(")
        if (idName == null) {
            sb.append("0x")
            sb.append(Integer.toHexString(id))
        } else {
            sb.append(idName)
        }
        sb.append(")")
        if (!route.isNullOrBlank()) {
            sb.append(" route=")
            sb.append(route)
        }
        if (label != null) {
            sb.append(" label=")
            sb.append(label)
        }
        return sb.toString()
    }

    public companion object {
        private val classes = mutableMapOf<String, Class<*>>()

        /**
         * Parse the class associated with this destination from a raw name, generally extracted
         * from the `android:name` attribute added to the destination's XML. This should
         * be the class providing the visual representation of the destination that the
         * user sees after navigating to this destination.
         *
         * This method does name -> Class caching and should be strongly preferred over doing your
         * own parsing if your [Navigator] supports the `android:name` attribute to
         * give consistent behavior across all Navigators.
         *
         * @param context Context providing the package name for use with relative class names and the
         * ClassLoader
         * @param name Absolute or relative class name. Null names will be ignored.
         * @param expectedClassType The expected class type
         * @return The parsed class
         * @throws IllegalArgumentException if the class is not found in the provided Context's
         * ClassLoader or if the class is not of the expected type
         */
        @Suppress("UNCHECKED_CAST")
        @JvmStatic
        protected fun <C> parseClassFromName(
            context: Context,
            name: String,
            expectedClassType: Class<out C?>
        ): Class<out C?> {
            var innerName = name
            if (innerName[0] == '.') {
                innerName = context.packageName + innerName
            }
            var clazz = classes[innerName]
            if (clazz == null) {
                try {
                    clazz = Class.forName(innerName, true, context.classLoader)
                    classes[name] = clazz
                } catch (e: ClassNotFoundException) {
                    throw IllegalArgumentException(e)
                }
            }
            require(expectedClassType.isAssignableFrom(clazz!!)) {
                "$innerName must be a subclass of $expectedClassType"
            }
            return clazz as Class<out C?>
        }

        /**
         * Used internally for NavDestinationTest
         * @suppress
         */
        @JvmStatic
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public fun <C> parseClassFromNameInternal(
            context: Context,
            name: String,
            expectedClassType: Class<out C?>
        ): Class<out C?> {
            return parseClassFromName(context, name, expectedClassType)
        }

        /**
         * Retrieve a suitable display name for a given id.
         * @param context Context used to resolve a resource's name
         * @param id The id to get a display name for
         * @return The resource's name if it is a valid id or just the id itself if it is not
         * a valid resource
         * @hide
         */
        @JvmStatic
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public fun getDisplayName(context: Context, id: Int): String {
            // aapt-generated IDs have the high byte nonzero,
            // so anything below that cannot be a valid resource id
            return if (id <= 0x00FFFFFF) {
                id.toString()
            } else try {
                context.resources.getResourceName(id)
            } catch (e: Resources.NotFoundException) {
                id.toString()
            }
        }

        /**
         * @hide
         */
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        public fun createRoute(route: String?): String =
            if (route != null) "android-app://androidx.navigation/$route" else ""

        /**
         * Provides a sequence of the NavDestination's hierarchy. The hierarchy starts with this
         * destination itself and is then followed by this destination's [NavDestination.parent], then that
         * graph's parent, and up the hierarchy until you've reached the root navigation graph.
         */
        @JvmStatic
        public val NavDestination.hierarchy: Sequence<NavDestination>
            get() = generateSequence(this) { it.parent }
    }
}
