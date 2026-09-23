package balti.xposed.pixelifygooglephotos

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * Adapts before-hooks onto libxposed's interceptor chain.
 * Setting [Call.result] swallows the original call, matching the old XC_MethodHook.setResult.
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
        PixelifyModule.framework.hook(origin).intercept { chain ->
            val call = Call(chain)
            before.before(call)
            if (call.swallowed) return@intercept call.result
            call.proceed()
        }
    }

    fun findClass(name: String, classLoader: ClassLoader): Class<*> =
        classLoader.loadClass(name)

    fun setStaticObject(clazz: Class<*>, fieldName: String, value: Any?) {
        val field = clazz.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(null, value)
    }
}
