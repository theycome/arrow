package arrow.fx.coroutines

import arrow.atomic.Atomic
import arrow.atomic.update
import arrow.atomic.value
import arrow.core.Either
import arrow.core.Tuple5
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test

class ParZip5Test {
    @Test
    fun parZip5RunsInParallel() = runTestUsingDefaultDispatcher {
      checkAll(10, Arb.int(), Arb.int(), Arb.int(), Arb.int(), Arb.int()) { a, b, c, d, e ->
        val r = Atomic("")
        val modifyGate1 = CompletableDeferred<Unit>()
        val modifyGate2 = CompletableDeferred<Unit>()
        val modifyGate3 = CompletableDeferred<Unit>()
        val modifyGate4 = CompletableDeferred<Unit>()

        parZip(
          {
            modifyGate2.await()
            r.update { i -> "$i$a" }
          },
          {
            modifyGate3.await()
            r.update { i -> "$i$b" }
            modifyGate2.complete(Unit)
          },
          {
            modifyGate4.await()
            r.update { i -> "$i$c" }
            modifyGate3.complete(Unit)
          },
          {
            modifyGate1.await()
            r.update { i -> "$i$d" }
            modifyGate4.complete(Unit)
          },
          {
            r.value = "$e"
            modifyGate1.complete(Unit)
          }
        ) { _a, _b, _c, _d, _e ->
          Tuple5(_a, _b, _c, _d, _e)
        }

        r.value shouldBe "$e$d$c$b$a"
      }
    }
    
    @Test
    fun CancellingParZip5CancelsAllParticipants() = runTestUsingDefaultDispatcher {
        val s = Channel<Unit>()
        val pa = CompletableDeferred<ExitCase>()
        val pb = CompletableDeferred<ExitCase>()
        val pc = CompletableDeferred<ExitCase>()
        val pd = CompletableDeferred<ExitCase>()
        val pe = CompletableDeferred<ExitCase>()

        val loserA: suspend CoroutineScope.() -> Int = { awaitExitCase(s, pa) }
        val loserB: suspend CoroutineScope.() -> Int = { awaitExitCase(s, pb) }
        val loserC: suspend CoroutineScope.() -> Int = { awaitExitCase(s, pc) }
        val loserD: suspend CoroutineScope.() -> Int = { awaitExitCase(s, pd) }
        val loserE: suspend CoroutineScope.() -> Int = { awaitExitCase(s, pe) }

        val f = async {
          parZip(loserA, loserB, loserC, loserD, loserE) { _a, _b, _c, _d, _e ->
            Tuple5(
              _a,
              _b,
              _c,
              _d,
              _e
            )
          }
        }

        repeat(5) { s.send(Unit) } // Suspend until all racers started
        f.cancel()

        pa.await().shouldBeTypeOf<ExitCase.Cancelled>()
        pb.await().shouldBeTypeOf<ExitCase.Cancelled>()
        pc.await().shouldBeTypeOf<ExitCase.Cancelled>()
        pd.await().shouldBeTypeOf<ExitCase.Cancelled>()
        pe.await().shouldBeTypeOf<ExitCase.Cancelled>()
    }
    
    @Test
    fun parZip5CancelsLosersIfAFailureOccursInOneOfTheTasks() = runTestUsingDefaultDispatcher {
      checkAll(
        Arb.throwable(),
        Arb.element(listOf(1, 2, 3, 4, 5)),
      ) { e, winningTask ->
        val s = Channel<Unit>()
        val pa = CompletableDeferred<ExitCase>()
        val pb = CompletableDeferred<ExitCase>()
        val pc = CompletableDeferred<ExitCase>()
        val pd = CompletableDeferred<ExitCase>()

        val winner: suspend CoroutineScope.() -> Int = { repeat(4) { s.send(Unit) }; throw e }
        val loserA: suspend CoroutineScope.() -> Int = { awaitExitCase(s, pa) }
        val loserB: suspend CoroutineScope.() -> Int = { awaitExitCase(s, pb) }
        val loserC: suspend CoroutineScope.() -> Int = { awaitExitCase(s, pc) }
        val loserD: suspend CoroutineScope.() -> Int = { awaitExitCase(s, pd) }

        val r = Either.catch {
          when (winningTask) {
            1 -> parZip(winner, loserA, loserB, loserC, loserD) { _, _, _, _, _ -> }
            2 -> parZip(loserA, winner, loserB, loserC, loserD) { _, _, _, _, _ -> }
            3 -> parZip(loserA, loserB, winner, loserC, loserD) { _, _, _, _, _ -> }
            4 -> parZip(loserA, loserB, loserC, winner, loserD) { _, _, _, _, _ -> }
            else -> parZip(loserA, loserB, loserC, loserD, winner) { _, _, _, _, _ -> }
          }
        }

        pa.await().shouldBeTypeOf<ExitCase.Cancelled>()
        pb.await().shouldBeTypeOf<ExitCase.Cancelled>()
        pc.await().shouldBeTypeOf<ExitCase.Cancelled>()
        pd.await().shouldBeTypeOf<ExitCase.Cancelled>()
        r should leftException(e)
      }
    }
    
    @Test
    fun parZipCancellationExceptionOnRightCanCancelRest() = runTestUsingDefaultDispatcher {
      checkAll(10, Arb.string(), Arb.int(1..5)) { msg, cancel ->
        val s = Channel<Unit>()
        val pa = CompletableDeferred<ExitCase>()
        val pb = CompletableDeferred<ExitCase>()
        val pc = CompletableDeferred<ExitCase>()
        val pd = CompletableDeferred<ExitCase>()

        val winner: suspend CoroutineScope.() -> Int = { repeat(4) { s.send(Unit) }; throw CancellationException(msg) }
        val loserA: suspend CoroutineScope.() -> Int = { awaitExitCase(s, pa) }
        val loserB: suspend CoroutineScope.() -> Int = { awaitExitCase(s, pb) }
        val loserC: suspend CoroutineScope.() -> Int = { awaitExitCase(s, pc) }
        val loserD: suspend CoroutineScope.() -> Int = { awaitExitCase(s, pd) }

        try {
          when (cancel) {
            1 -> parZip(winner, loserA, loserB, loserC, loserD) { _, _, _, _, _ -> }
            2 -> parZip(loserA, winner, loserB, loserC, loserD) { _, _, _, _, _ -> }
            3 -> parZip(loserA, loserB, winner, loserC, loserD) { _, _, _, _, _ -> }
            4 -> parZip(loserA, loserB, loserC, winner, loserD) { _, _, _, _, _ -> }
            else -> parZip(loserA, loserB, loserC, loserD, winner) { _, _, _, _, _ -> }
          }
        } catch (e: CancellationException) {
          e.message shouldBe msg
        }
        pa.await().shouldBeTypeOf<ExitCase.Cancelled>()
        pb.await().shouldBeTypeOf<ExitCase.Cancelled>()
        pc.await().shouldBeTypeOf<ExitCase.Cancelled>()
        pd.await().shouldBeTypeOf<ExitCase.Cancelled>()
      }
    }
  }
