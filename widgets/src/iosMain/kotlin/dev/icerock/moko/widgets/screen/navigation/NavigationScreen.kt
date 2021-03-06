/*
 * Copyright 2019 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package dev.icerock.moko.widgets.screen.navigation

import dev.icerock.moko.parcelize.Parcelable
import dev.icerock.moko.widgets.screen.Args
import dev.icerock.moko.widgets.screen.Screen
import dev.icerock.moko.widgets.screen.TypedScreenDesc
import dev.icerock.moko.widgets.screen.getAssociatedScreen
import dev.icerock.moko.widgets.style.applyNavigationBarStyle
import dev.icerock.moko.widgets.utils.toUIBarButtonItem
import kotlinx.coroutines.Runnable
import platform.UIKit.UIBarButtonItem
import platform.UIKit.UINavigationController
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UIKit.navigationItem
import platform.darwin.NSObject
import kotlin.native.ref.WeakReference

actual abstract class NavigationScreen<S> actual constructor(
    private val initialScreen: TypedScreenDesc<Args.Empty, S>,
    router: Router
) : Screen<Args.Empty>() where S : Screen<Args.Empty>, S : NavigationItem {

    init {
        router.navigationScreen = this
    }

    private var navigationController: UINavigationController? = null
    private val controllerDelegate = ControllerDelegate(this)

    override fun createViewController(): UIViewController {
        val controller = UINavigationController().also {
            navigationController = it
            it.delegate = controllerDelegate
        }
        val rootScreen = initialScreen.instantiate()
        val rootViewController = rootScreen.viewController
        controller.setViewControllers(listOf(rootViewController))

        return controller
    }

    private fun updateNavigation(
        navigationItem: NavigationItem,
        viewController: UIViewController
    ) {
        when (val navBar = navigationItem.navigationBar) {
            is NavigationBar.None -> navigationController?.navigationBarHidden = true
            is NavigationBar.Normal -> {
                navigationController?.navigationBarHidden = false
                viewController.navigationItem.title = navBar.title.localized()
                navigationController?.navigationBar?.applyNavigationBarStyle(navBar.styles)

                if (navBar.backButton != null) {
                    viewController.navigationItem.leftBarButtonItem = navBar.backButton.toUIBarButtonItem()
                }

                val rightButtons: List<UIBarButtonItem>? = navBar.actions?.map {
                    it.toUIBarButtonItem()
                }?.reversed()
                viewController.navigationItem.rightBarButtonItems = rightButtons
            }
        }
    }

    actual class Router {
        var navigationScreen: NavigationScreen<*>? = null

        internal actual fun <T, Arg : Args, S> createPushRouteInternal(
            destination: TypedScreenDesc<Arg, S>,
            inputMapper: (T) -> Arg
        ): Route<T> where S : Screen<Arg>, S : NavigationItem {
            return object : Route<T> {
                override fun route(arg: T) {
                    val newScreen = destination.instantiate()
                    newScreen.arg = inputMapper(arg)
                    val screenViewController: UIViewController = newScreen.viewController
                    navigationScreen!!.run {
                        navigationController?.pushViewController(
                            screenViewController,
                            animated = true
                        )
                    }
                }
            }
        }

        internal actual fun <IT, Arg : Args, OT, R : Parcelable, S> createPushResultRouteInternal(
            destination: TypedScreenDesc<Arg, S>,
            inputMapper: (IT) -> Arg,
            outputMapper: (R) -> OT
        ): RouteWithResult<IT, OT> where S : Screen<Arg>, S : Resultable<R>, S : NavigationItem {
            return object : RouteWithResult<IT, OT> {
                override fun route(source: Screen<*>, arg: IT, handler: RouteHandler<OT>) {
                    val navigationScreen = navigationScreen!!

                    val newScreen = destination.instantiate()
                    newScreen.arg = inputMapper(arg)
                    val screenViewController: UIViewController = newScreen.viewController

                    navigationScreen.controllerDelegate.resultCallbacks[screenViewController] =
                        Runnable {
                            val result = newScreen.screenResult
                            val mappedResult = result?.let(outputMapper)
                            handler.handleResult(mappedResult)
                        }

                    navigationScreen.run {
                        navigationController?.pushViewController(
                            screenViewController,
                            animated = true
                        )
                    }
                }

                override val resultMapper: (Parcelable) -> OT = { outputMapper(it as R) }
            }
        }

        internal actual fun <T, Arg : Args, S> createReplaceRouteInternal(
            destination: TypedScreenDesc<Arg, S>,
            inputMapper: (T) -> Arg
        ): Route<T> where S : Screen<Arg>, S : NavigationItem {
            return object : Route<T> {
                override fun route(arg: T) {
                    val newScreen = destination.instantiate()
                    newScreen.arg = inputMapper(arg)
                    val screenViewController: UIViewController = newScreen.viewController
                    navigationScreen!!.run {
                        navigationController?.setViewControllers(
                            listOf(screenViewController),
                            animated = true
                        )
                    }
                }
            }
        }

        actual fun createPopRoute(): Route<Unit> {
            return object : Route<Unit> {
                override fun route(arg: Unit) {
                    navigationScreen!!.navigationController!!.popViewControllerAnimated(true)
                }
            }
        }
    }

    private class ControllerDelegate(navigationScreen: NavigationScreen<*>) : NSObject(),
        UINavigationControllerDelegateProtocol {
        private val navigationScreen = WeakReference(navigationScreen)
        val resultCallbacks = mutableMapOf<UIViewController, Runnable>()

        override fun navigationController(
            navigationController: UINavigationController,
            willShowViewController: UIViewController,
            animated: Boolean
        ) {
            val stack = navigationController.viewControllers
            resultCallbacks.filterKeys { stack.contains(it).not() }
                .forEach { (vc, runnable) ->
                    runnable.run()
                    resultCallbacks.remove(vc)
                }
            val controller = willShowViewController
            val screen = controller.getAssociatedScreen() ?: return

            if (screen is NavigationItem) {
                navigationScreen.get()?.updateNavigation(screen, controller)
            }
        }
    }
}
