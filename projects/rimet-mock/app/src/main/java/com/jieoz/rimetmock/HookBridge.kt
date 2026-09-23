package com.jieoz.rimetmock

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * Adapts before-hooks onto libxposed's interceptor chain.
 * Setting [Call.result] swallows the original call (matching the old XC_MethodHook.setResult),
 * so the hooked method returns our value and never runs its body.
 */
internal object HookBridge {

    fun interface Before {
        fun before(call: Call)
    }

    class Call(private val chain: XposedInterface.Chain) {
        val args: Array<Any?> = chain.args.toTypedArray()

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
        RimetMockModule.framework.hook(origin).intercept { chain ->
            val call = Call(chain)
            before.before(call)
            if (call.swallowed) return@intercept call.result
            call.proceed()
        }
    }
}
