package arrow.fx.coroutines

import arrow.core.Either
import arrow.core.Tuple6
import io.kotest.matchers.should
import io.kotest.matchers.string.shouldStartWith
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.test.Test

class ParZip6JvmTest {
  val threadName: suspend CoroutineScope.() -> String =
    { Thread.currentThread().name }

  @Test fun parZip6ReturnsToOriginalContext(): Unit = runBlocking(Dispatchers.Default) {
    val zipCtxName = "parZip6"
    resourceScope {
      val zipCtx = executor { Executors.newFixedThreadPool(6, NamedThreadFactory(zipCtxName)) }

      withContext(single()) {
        threadName() shouldStartWith "single"

        val (s1, s2, s3, s4, s5, s6) = parZip(
          zipCtx, threadName, threadName, threadName, threadName, threadName, threadName
        ) { a, b, c, d, e, f ->
          Tuple6(a, b, c, d, e, f)
        }

        s1 shouldStartWith zipCtxName
        s2 shouldStartWith zipCtxName
        s3 shouldStartWith zipCtxName
        s4 shouldStartWith zipCtxName
        s5 shouldStartWith zipCtxName
        s6 shouldStartWith zipCtxName
        threadName() shouldStartWith "single"
      }
    }
  }

  @Test fun parZip6ReturnsToOriginalContextOnFailure(): Unit = runBlocking(Dispatchers.Default) {
    val zipCtxName = "parZip6"
    resourceScope {
    val zipCtx = executor { Executors.newFixedThreadPool(6, NamedThreadFactory(zipCtxName)) }

    checkAll(10, Arb.int(1..6), Arb.throwable()) { choose, e ->
        withContext(single()) {
          threadName() shouldStartWith "single"

          Either.catch {
            when (choose) {
              1 -> parZip(
                zipCtx,
                { throw e },
                { awaitCancellation() },
                { awaitCancellation() },
                { awaitCancellation() },
                { awaitCancellation() },
                { awaitCancellation() }
              ) { _, _, _, _, _, _ -> Unit }

              2 -> parZip(
                zipCtx,
                { awaitCancellation() },
                { throw e },
                { awaitCancellation() },
                { awaitCancellation() },
                { awaitCancellation() },
                { awaitCancellation() }
              ) { _, _, _, _, _, _ -> Unit }

              3 -> parZip(
                zipCtx,
                { awaitCancellation() },
                { awaitCancellation() },
                { throw e },
                { awaitCancellation() },
                { awaitCancellation() },
                { awaitCancellation() }
              ) { _, _, _, _, _, _ -> Unit }

              4 -> parZip(
                zipCtx,
                { awaitCancellation() },
                { awaitCancellation() },
                { awaitCancellation() },
                { throw e },
                { awaitCancellation() },
                { awaitCancellation() }
              ) { _, _, _, _, _, _ -> Unit }

              5 -> parZip(
                zipCtx,
                { awaitCancellation() },
                { awaitCancellation() },
                { awaitCancellation() },
                { awaitCancellation() },
                { throw e },
                { awaitCancellation() }
              ) { _, _, _, _, _, _ -> Unit }

              else -> parZip(
                zipCtx,
                { awaitCancellation() },
                { awaitCancellation() },
                { awaitCancellation() },
                { awaitCancellation() },
                { awaitCancellation() },
                { throw e }
              ) { _, _, _, _, _, _ -> Unit }
            }
          } should leftException(e)
          threadName() shouldStartWith "single"
        }
      }
    }
  }

  @Test fun parZip6FinishesOnSingleThread(): Unit = runBlocking(Dispatchers.Default) {
    checkAll(10, Arb.string()) {
      val res = resourceScope {
        val ctx = singleThreadContext("single")
        parZip(ctx, threadName, threadName, threadName, threadName, threadName, threadName) { a, b, c, d, e, f ->
          listOf(a, b, c, d, e, f)
        }
      }
      res.forEach { it shouldStartWith "single" }
    }
  }
}
