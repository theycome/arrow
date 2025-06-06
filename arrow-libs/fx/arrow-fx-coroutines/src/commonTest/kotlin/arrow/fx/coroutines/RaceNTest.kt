package arrow.fx.coroutines

import arrow.core.Either
import arrow.core.identity
import arrow.core.merge
import io.kotest.assertions.retry
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

fun <A> Either<Throwable, A>.rethrow(): A =
  fold({ throw it }, ::identity)

class RaceNTest {
  @Test fun race2JoinFirst() = runTestUsingDefaultDispatcher {
    checkAll(10, Arb.int()) { i ->
      raceN({ i }, { awaitCancellation() }) shouldBe Either.Left(i)
    }
  }

  @Test fun race2JoinSecond() = runTestUsingDefaultDispatcher {
    checkAll(10, Arb.int()) { i ->
      raceN({ awaitCancellation() }, { i }) shouldBe Either.Right(i)
    }
  }

  @Test fun race2CancelsAll() = runTestUsingDefaultDispatcher {
    checkAll(10, Arb.int(), Arb.int()) { a, b ->
      val s = Channel<Unit>()
      val pa = CompletableDeferred<Pair<Int, ExitCase>>()
      val pb = CompletableDeferred<Pair<Int, ExitCase>>()

      val loserA: suspend CoroutineScope.() -> Int = { guaranteeCase({ s.receive(); awaitCancellation() }) { ex -> pa.complete(Pair(a, ex)) } }
      val loserB: suspend CoroutineScope.() -> Int = { guaranteeCase({ s.receive(); awaitCancellation() }) { ex -> pb.complete(Pair(b, ex)) } }

      val f = async { raceN(loserA, loserB) }

      // Suspend until all racers started
      s.send(Unit)
      s.send(Unit)
      f.cancel()

      pa.await().let { (res, exit) ->
        res shouldBe a
        exit.shouldBeInstanceOf<ExitCase.Cancelled>()
      }
      pb.await().let { (res, exit) ->
        res shouldBe b
        exit.shouldBeInstanceOf<ExitCase.Cancelled>()
      }
    }
  }

  @Test fun race2CancelsWithFirstSuccess() = runTestUsingDefaultDispatcher {
    checkAll(
      Arb.either(Arb.throwable(), Arb.int()),
      Arb.boolean(),
      Arb.int()
    ) { eith, leftWinner, a ->
      val s = Channel<Unit>()
      val pa = CompletableDeferred<Pair<Int, ExitCase>>()

      val winner: suspend CoroutineScope.() -> Int = { s.send(Unit); eith.rethrow() }
      val loserA: suspend CoroutineScope.() -> Int = { guaranteeCase({ s.receive(); awaitCancellation() }) { ex -> pa.complete(Pair(a, ex)) } }

      val res = Either.catch {
        if (leftWinner) raceN(winner, loserA)
        else raceN(loserA, winner)
      }.map { it.merge() }

      pa.await().let { (res, exit) ->
        res shouldBe a
        exit.shouldBeInstanceOf<ExitCase.Cancelled>()
      }
      res shouldBe either(eith)
    }
  }

  @Test fun race3JoinFirst() = runTestUsingDefaultDispatcher {
    checkAll(10, Arb.int()) { i ->
      raceN({ i }, { awaitCancellation() }, { awaitCancellation() }) shouldBe Race3.First(i)
    }
  }

  @Test fun race3JoinSecond() = runTestUsingDefaultDispatcher {
    checkAll(10, Arb.int()) { i ->
      raceN({ awaitCancellation() }, { i }, { awaitCancellation() }) shouldBe Race3.Second(i)
    }
  }

  @Test fun race3JoinThird() = runTestUsingDefaultDispatcher {
    checkAll(10, Arb.int()) { i ->
      raceN({ awaitCancellation() }, { awaitCancellation() }, { i }) shouldBe Race3.Third(i)
    }
  }

  @Test fun race3CancelsAll() = runTestUsingDefaultDispatcher {
    retry(10, 1.seconds) {
      checkAll(10, Arb.int(), Arb.int(), Arb.int()) { a, b, c ->
        val s = Channel<Unit>()
        val pa = CompletableDeferred<Pair<Int, ExitCase>>()
        val pb = CompletableDeferred<Pair<Int, ExitCase>>()
        val pc = CompletableDeferred<Pair<Int, ExitCase>>()

        val loserA: suspend CoroutineScope.() -> Int =
          { guaranteeCase({ s.receive(); awaitCancellation() }) { ex -> pa.complete(Pair(a, ex)) } }
        val loserB: suspend CoroutineScope.() -> Int =
          { guaranteeCase({ s.receive(); awaitCancellation() }) { ex -> pb.complete(Pair(b, ex)) } }
        val loserC: suspend CoroutineScope.() -> Int =
          { guaranteeCase({ s.receive(); awaitCancellation() }) { ex -> pc.complete(Pair(c, ex)) } }

        val f = async { raceN(loserA, loserB, loserC) }

        s.send(Unit) // Suspend until all racers started
        s.send(Unit)
        s.send(Unit)
        f.cancel()

        pa.await().let { (res, exit) ->
          res shouldBe a
          exit.shouldBeInstanceOf<ExitCase.Cancelled>()
        }
        pb.await().let { (res, exit) ->
          res shouldBe b
          exit.shouldBeInstanceOf<ExitCase.Cancelled>()
        }
        pc.await().let { (res, exit) ->
          res shouldBe c
          exit.shouldBeInstanceOf<ExitCase.Cancelled>()
        }
      }
    }
  }

  @Test fun race3CancelsWithFirstSuccess() = runTestUsingDefaultDispatcher {
    checkAll(
      Arb.either(Arb.throwable(), Arb.int()),
      Arb.element(listOf(1, 2, 3)),
      Arb.int(),
      Arb.int()
    ) { eith, leftWinner, a, b ->
      val s = Channel<Unit>()
      val pa = CompletableDeferred<Pair<Int, ExitCase>>()
      val pb = CompletableDeferred<Pair<Int, ExitCase>>()

      val winner: suspend CoroutineScope.() -> Int = { s.send(Unit); s.send(Unit); eith.rethrow() }
      val loserA: suspend CoroutineScope.() -> Int = { guaranteeCase({ s.receive(); awaitCancellation() }) { ex -> pa.complete(Pair(a, ex)) } }
      val loserB: suspend CoroutineScope.() -> Int = { guaranteeCase({ s.receive(); awaitCancellation() }) { ex -> pb.complete(Pair(b, ex)) } }

      val res = Either.catch {
        when (leftWinner) {
          1 -> raceN(winner, loserA, loserB)
          2 -> raceN(loserA, winner, loserB)
          else -> raceN(loserA, loserB, winner)
        }
      }.map { it.fold(::identity, ::identity, ::identity) }

      pa.await().let { (res, exit) ->
        res shouldBe a
        exit.shouldBeInstanceOf<ExitCase.Cancelled>()
      }
      pb.await().let { (res, exit) ->
        res shouldBe b
        exit.shouldBeInstanceOf<ExitCase.Cancelled>()
      }
      res should either(eith)
    }
  }
}
