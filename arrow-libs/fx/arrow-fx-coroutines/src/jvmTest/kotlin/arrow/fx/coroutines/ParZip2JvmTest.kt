package arrow.fx.coroutines

import arrow.core.Either
import io.kotest.matchers.should
import io.kotest.matchers.string.shouldStartWith
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class ParZip2JvmTest {
  @Test
  fun parZip2ReturnsToOriginalContext(): Unit =
      runBlocking(context = Dispatchers.Default) {
        val zipCtxName = "parZip2"
        resourceScope {
          val zipCtx = executor { Executors.newFixedThreadPool(2, NamedThreadFactory(zipCtxName)) }
          withContext(single()) {
            Thread.currentThread().name shouldStartWith "single"

            val (s1, s2) =
                parZip(zipCtx, { Thread.currentThread().name }, { Thread.currentThread().name }) {
                    a,
                    b ->
                  Pair(a, b)
                }

            s1 shouldStartWith zipCtxName
            s2 shouldStartWith zipCtxName
            Thread.currentThread().name shouldStartWith "single"
          }
        }
      }


  @Test
  fun parZip2ReturnsToOriginalContextOnFailureRight(): Unit =
    parZip2ReturnsToOriginalContextOnFailure(true)

  @Test
  fun parZip2ReturnsToOriginalContextOnFailureLeft(): Unit =
    parZip2ReturnsToOriginalContextOnFailure(false)

  private fun parZip2ReturnsToOriginalContextOnFailure(choose: Boolean): Unit =
      runBlocking(context = Dispatchers.Default) {
        val zipCtxName = "parZip2"
        resourceScope {
          val zipCtx = executor { Executors.newFixedThreadPool(2, NamedThreadFactory(zipCtxName)) }
          val e = RuntimeException("Boom")
          withContext(single()) {
            Thread.currentThread().name shouldStartWith "single"

            Either.catch {
              if (choose) parZip(zipCtx, { throw e }, { awaitCancellation() }) { _, _ -> Unit }
              else parZip(zipCtx, { awaitCancellation() }, { throw e }) { _, _ -> Unit }
            } should leftException(e)

            Thread.currentThread().name shouldStartWith "single"
          }
        }
      }

  @Test
  fun parZip2FinishesOnSingleThread(): Unit =
      runBlocking(context = Dispatchers.Default) {
        val res = resourceScope {
          val ctx = singleThreadContext("single")
          parZip(ctx, { Thread.currentThread().name }, { Thread.currentThread().name }) { a, b ->
            listOf(a, b)
          }
        }
        res.forEach { it shouldStartWith "single" }
      }
}

suspend fun parallelCtx(
    nThreads: Int,
    mapCtxName: String,
    use: suspend (CoroutineContext, CoroutineContext) -> Unit,
): Unit = resourceScope {
  use(singleThreadContext("single"), fixedThreadPoolContext(nThreads, mapCtxName))
}
