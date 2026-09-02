package android.util

/** Routes the renderer's logging to stdout. See android/graphics/Primitives.kt. */
object Log {
    @JvmStatic fun i(tag: String, msg: String): Int { println("I/$tag: $msg"); return 0 }
    @JvmStatic fun w(tag: String, msg: String): Int { println("W/$tag: $msg"); return 0 }
    @JvmStatic fun e(tag: String, msg: String): Int { println("E/$tag: $msg"); return 0 }
    @JvmStatic fun d(tag: String, msg: String): Int { println("D/$tag: $msg"); return 0 }
}
