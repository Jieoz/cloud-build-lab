package com.jieoz.ctsshare

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * Adapts before-hooks onto libxposed's interceptor chain.
 * Setting [Call.result] swallows the original call (matching the old XC_MethodHook.setResult),
 * so the hooked method returns our value and never runs its body. Mutating [Call.args] and NOT
 * setting a result lets the original run with our modified arguments.
 */
internal object HookBridge {

    fun interface Before {
        fun before(call: Call)
    }

    class Call(private val chain: XposedInterface.Chain) {
        val args: Array<Any?> = chain.args.toTypedArray()
        val chainThis: Any? = chain.thisObject

        private var replaced = false
        private var replacement: Any? = null

        var result: Any?
            get() = replacement
            set(value) {
                replacement = value
                replaced = true
            }

        internal val swallowed: Boolean get() = replaced
        internal fun proceed(): Any? = chain.proceed(args)
    }

    fun hook(origin: Executable, before: Before) {
        CtsShareModule.framework.hook(origin).intercept { chain ->
            val call = Call(chain)
            before.before(call)
            if (call.swallowed) call.result else call.proceed()
        }
    }

    fun interface After {
        fun after(thisObject: Any?, args: List<Any?>, result: Any?)
    }

    /** Runs the original first, then observes. Observer exceptions never reach the host. */
    fun after(origin: Executable, after: After) {
        CtsShareModule.framework.hook(origin).intercept { chain ->
            val result = chain.proceed()
            runCatching { after.after(chain.thisObject, chain.args, result) }
            result
        }
    }
}
