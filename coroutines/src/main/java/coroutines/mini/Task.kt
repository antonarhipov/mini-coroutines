package coroutines.mini

import java.util.concurrent.CountDownLatch
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.createCoroutineUnintercepted
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

typealias OnCompletion<T> = (Result<T>) -> Unit

fun <T> Task<T>.invokeOnCompletion(block: OnCompletion<T>) {
    (this as? TaskImpl)?.invokeOnCompletion(block)
}

suspend fun <T> Iterable<Task<T>>.awaitAll(): List<T> {
    return map { it.await() }
}

internal fun <T> Task<T>.blockingGet(): T {
    var taskResult: Result<T>? = null
    val latch = CountDownLatch(1)
    val continuation = object : Continuation<T> {
        override val context: CoroutineContext = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            taskResult = result
            latch.countDown()
        }
    }
    suspend { await() }.startCoroutine(continuation)
    latch.await()
    val result = taskResult!!
    return if (result.isSuccess) {
        result.getOrThrow()
    } else {
        throw result.exceptionOrNull()!!
    }
}

interface Task<T> {
    suspend fun await(): T

    fun cancel()
}

internal class TaskImpl<T>(
    private val block: suspend () -> T,
    context: CoroutineContext,
) : Task<T>, Runnable, Continuation<T> {
    private var isCanceled = false

    @Volatile
    private var result: Result<T>? = null

    internal val isCompleted: Boolean
        get() = isCanceled || result != null

    @Volatile
    var onReschedule: ((delayMillis: Long) -> Unit)? = null

    private var coroutine: Continuation<Unit>? = null

    private val onCompletionCallbacks = mutableListOf<OnCompletion<T>>()

    private val dispatcher = context[CoroutineDispatcher]
        ?: error("The current context does not contain a CoroutineDispatcher")

    override val context: CoroutineContext = context + TaskDelay(this)

    override fun resumeWith(result: Result<T>) {
        synchronized(onCompletionCallbacks) {
            this.result = result
            onCompletionCallbacks.forEach { it.invoke(result) }
            onCompletionCallbacks.clear()
        }
    }

    @Deprecated("Do not call run() manually")
    override fun run() {
        if (isCanceled) return
        val continuation = coroutine ?: block
            .createCoroutineUnintercepted(completion = this)
            .also { this.coroutine = it }
        continuation.resume(Unit)
    }

    override suspend fun await(): T {
        if (isCanceled) throw TaskCancellationException(this)
        val result = this.result
        return if (result == null) {
            // Suspend until the task is completed
            suspendCoroutine { cont ->
                invokeOnCompletion { cont.resumeWith(it) }
            }
        } else {
            // Task is finished
            if (result.isSuccess) {
                result.getOrThrow()
            } else {
                throw result.exceptionOrNull()!!
            }
        }
    }

    override fun cancel() {
        if (isCompleted) return
        isCanceled = true
        resumeWith(Result.failure(TaskCancellationException(this)))
    }

    fun start() {
        dispatcher.dispatch(this)
    }

    fun invokeOnCompletion(block: OnCompletion<T>) {
        if (result != null) {
            block(result!!)
            return
        }
        synchronized(onCompletionCallbacks) {
            if (result != null) {
                block(result!!)
            } else {
                onCompletionCallbacks.add(block)
            }
        }
    }
}

class TaskCancellationException(
    task: Task<*>,
) : Exception("$task canceled")
