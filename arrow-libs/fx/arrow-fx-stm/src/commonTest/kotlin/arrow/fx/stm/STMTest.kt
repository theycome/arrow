package arrow.fx.stm

import arrow.fx.coroutines.parMap
import arrow.fx.coroutines.parZip
import arrow.fx.stm.internal.BlockedIndefinitely
import io.kotest.assertions.AssertionErrorBuilder
import io.kotest.common.reflection.bestName
import io.kotest.matchers.assertionCounter
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

@ExperimentalTime
class STMTest {

  @Test fun noEffects() = runTest {
    atomically { 10 } shouldBeExactly 10
  }

  @Test fun readingFromVars() = runTest {
    checkAll(Arb.int()) { i: Int ->
      val tv = TVar.new(i)
      atomically {
        tv.read()
      } shouldBeExactly i
      tv.unsafeRead() shouldBeExactly i
    }
  }

  @Test fun readingAndWriting() = runTest {
    checkAll(Arb.int(), Arb.int()) { i: Int, j: Int ->
      val tv = TVar.new(i)
      atomically { tv.write(j) }
      tv.unsafeRead() shouldBeExactly j
    }
  }

  @Test fun readAfterAWriteShouldHaveTheUpdatedValue() = runTest {
    checkAll(Arb.int(), Arb.int()) { i: Int, j: Int ->
      val tv = TVar.new(i)
      atomically { tv.write(j); tv.read() } shouldBeExactly j
      tv.unsafeRead() shouldBeExactly j
    }
  }

  @Test fun readingMultipleVariables() = runTest {
    checkAll(Arb.int(), Arb.int(), Arb.int()) { i: Int, j: Int, k: Int ->
      val v1 = TVar.new(i)
      val v2 = TVar.new(j)
      val v3 = TVar.new(k)
      atomically { v1.read() + v2.read() + v3.read() } shouldBeExactly i + j + k
      v1.unsafeRead() shouldBeExactly i
      v2.unsafeRead() shouldBeExactly j
      v3.unsafeRead() shouldBeExactly k
    }
  }

  @Test fun readingAndWritingMultipleVariables() = runTest {
    checkAll(Arb.int(), Arb.int(), Arb.int()) { i: Int, j: Int, k: Int ->
      val v1 = TVar.new(i)
      val v2 = TVar.new(j)
      val v3 = TVar.new(k)
      val sum = TVar.new(0)
      atomically {
        val s = v1.read() + v2.read() + v3.read()
        sum.write(s)
      }
      v1.unsafeRead() shouldBeExactly i
      v2.unsafeRead() shouldBeExactly j
      v3.unsafeRead() shouldBeExactly k
      sum.unsafeRead() shouldBeExactly i + j + k
    }
  }

  @Test fun retryWithoutPriorReadsThrowsAnException() = runTest {
    shouldThrow<BlockedIndefinitely> { atomically { retry() } }
  }

  @Test fun retryShouldSuspendForeverIfNoReadVariableChanges() = runTest {
    withTimeoutOrNull(500.milliseconds) {
      val tv = TVar.new(0)
      atomically {
        if (tv.read() == 0) retry()
        else 200
      }
    } shouldBe null
  }

  @Test fun aSuspendedTransactionWillResumeIfAVariableChanges() = runTest {
    val tv = TVar.new(0)
    val f = async {
      delay(500.milliseconds)
      atomically { tv.modify { it + 1 } }
    }
    atomically {
      when (val i = tv.read()) {
        0 -> retry()
        else -> i
      }
    } shouldBeExactly 1
    f.join()
  }

  @Test fun aSuspendedTransactionWillResumeIfAnyVariableChanges() = runTest {
    val v1 = TVar.new(0)
    val v2 = TVar.new(0)
    val v3 = TVar.new(0)
    val f = async {
      delay(500.milliseconds)
      atomically { v1.modify { it + 1 } }
      delay(500.milliseconds)
      atomically { v2.modify { it + 1 } }
      delay(500.milliseconds)
      atomically { v3.modify { it + 1 } }
    }
    atomically {
      val i = v1.read() + v2.read() + v3.read()
      check(i >= 3)
      i
    } shouldBeExactly 3
    f.join()
  }

  @Test fun retryOrElseRetryOrElseT1T1() = runTest {
    atomically {
      stm { retry() } orElse { 10 }
    } shouldBeExactly 10
  }

  @Test fun retryOrElseT1OrElseRetryT1() = runTest {
    atomically {
      stm { 10 } orElse { retry() }
    } shouldBeExactly 10
  }

  @Test fun retryOrElseAssociativity() = runTest {
    checkAll(Arb.boolean(), Arb.boolean(), Arb.boolean()) { b1: Boolean, b2: Boolean, b3: Boolean ->
      if ((b1 || b2 || b3).not()) {
        shouldThrow<BlockedIndefinitely> {
          atomically {
            stm { stm { check(b1) } orElse { check(b2) } } orElse { check(b3) }
          }
        } shouldBe shouldThrow {
          atomically {
            stm { check(b1) } orElse { stm { check(b2) } orElse { check(b3) } }
          }
        }
      } else {
        atomically {
          stm { stm { check(b1) } orElse { check(b2) } } orElse { check(b3) }
        } shouldBe atomically {
          stm { check(b1) } orElse { stm { check(b2) } orElse { check(b3) } }
        }
      }
    }
  }

  @Test fun suspendedTransactionsAreResumedForVariablesAccessedInOrElse() = runTest {
    val tv = TVar.new(0)
    val f = async {
      delay(10.microseconds)
      atomically { tv.modify { it + 1 } }
    }
    atomically {
      stm {
        when (val i = tv.read()) {
          0 -> retry()
          else -> i
        }
      } orElse { retry() }
    } shouldBeExactly 1
    f.join()
  }

  @Test fun onASingleVariableConcurrentTransactionsShouldBeLinear() = runTest {
    val tv = TVar.new(0)
    val res = TQueue.new<Int>()

    (0..100).map {
      async {
        atomically {
          val r = tv.read().also { tv.write(it + 1) }
          res.write(r)
        }
      }
    }.joinAll()

    atomically { res.flush() } shouldBe (0..100).toList()
  }

  @Test fun atomicallyRethrowsExceptions() = runTest {
    shouldThrow<IllegalArgumentException> { atomically { throw IllegalArgumentException("Test") } }
  }

  @Test fun throwingAnExceptionsShouldVoidAllStateChanges() = runTest {
    val tv = TVar.new(10)
    shouldThrow<IllegalArgumentException> {
      atomically { tv.write(30); throw IllegalArgumentException("test") }
    }
    tv.unsafeRead() shouldBeExactly 10
  }

  @Test fun catchShouldWorkAsExcepted() = runTest {
    val tv = TVar.new(10)
    val ex = IllegalArgumentException("test")
    atomically {
      catch({
        tv.write(30)
        throw ex
      }) { e ->
        e shouldBe ex
      }
    }
    tv.unsafeRead() shouldBeExactly 10
  }

  @Test fun concurrentExample1() = runTest {
    val acc1 = TVar.new(100)
    val acc2 = TVar.new(200)
    parZip(
      {
        // transfer acc1 to acc2
        val amount = 50
        atomically {
          val acc1Balance = acc1.read()
          check(acc1Balance - amount >= 0)
          acc1.write(acc1Balance - amount)
          acc2.modify { it + 50 }
        }
      },
      {
        atomically { acc1.modify { it - 60 } }
        delay(20.milliseconds)
        atomically { acc1.modify { it + 60 } }
      },
      { _, _ -> Unit }
    )
    acc1.unsafeRead() shouldBeExactly 50
    acc2.unsafeRead() shouldBeExactly 250
  }

  // TypeError: Cannot read property 'toString' of undefined
  // at ObjectLiteral_0.test(/var/folders/x5/6r18d9w52c7czy6zh5m1spvw0000gn/T/_karma_webpack_624630/commons.js:3661)
  // at <global>.invokeMatcher(/var/folders/x5/6r18d9w52c7czy6zh5m1spvw0000gn/T/_karma_webpack_624630/commons.js:19216)
  // at <global>.should(/var/folders/x5/6r18d9w52c7czy6zh5m1spvw0000gn/T/_karma_webpack_624630/commons.js:19212)
  // at <global>.shouldBeInRange(/var/folders/x5/6r18d9w52c7czy6zh5m1spvw0000gn/T/_karma_webpack_624630/commons.js:3652)
  // at STMTransaction.f(/var/folders/x5/6r18d9w52c7czy6zh5m1spvw0000gn/T/_karma_webpack_624630/commons.js:261217)
  // at commit.doResume(/var/folders/x5/6r18d9w52c7czy6zh5m1spvw0000gn/T/_karma_webpack_624630/commons.js:270552)
  // at commit.CoroutineImpl.resumeWith(/var/folders/x5/6r18d9w52c7czy6zh5m1spvw0000gn/T/_karma_webpack_624630/commons.js:118697)
  // at CancellableContinuationImpl.DispatchedTask.run(/var/folders/x5/6r18d9w52c7czy6zh5m1spvw0000gn/T/_karma_webpack_624630/commons.js:174593)
  // at WindowMessageQueue.MessageQueue.process(/var/folders/x5/6r18d9w52c7czy6zh5m1spvw0000gn/T/_karma_webpack_624630/commons.js:177985)
  // at <global>.<unknown>(/var/folders/x5/6r18d9w52c7czy6zh5m1spvw0000gn/T/_karma_webpack_624630/commons.js:177940)
  @Test @Ignore fun concurrentExample2ConfigEnabledFalse() = runTest {
    val tq = TQueue.new<Int>()
    parZip(
      {
        // producers
        (0..4).parMap {
          for (i in (it * 20 + 1)..(it * 20 + 20)) {
            atomically { tq.write(i) }
          }
        }
      },
      {
        val collected = mutableSetOf<Int>()
        for (i in 1..100) {
          // consumer
          atomically {
            tq.read().also { it shouldBeInRange (1..100) }
          }.also { collected.add(it) }
        }
        // verify that we got 100 unique numbers
        collected.size shouldBeExactly 100
      }
    ) { _, _ -> Unit }
    // the above only finishes if the consumer reads at least 100 values, this here is just to make sure there are no leftovers
    atomically { tq.flush() } shouldBe emptyList()
  }
}

// copied from Kotest so we can inline it
inline fun <reified T : Throwable> shouldThrow(block: () -> Any?): T {
  assertionCounter.inc()
  val expectedExceptionClass = T::class
  val thrownThrowable = try {
    block()
    null  // Can't throw failure here directly, as it would be caught by the catch clause, and it's an AssertionError, which is a special case
  } catch (thrown: Throwable) {
    thrown
  }

  return when (thrownThrowable) {
    null -> throw AssertionErrorBuilder.create()
      .withMessage("Expected exception ${expectedExceptionClass.bestName()} but no exception was thrown.")
      .build()
    is T -> thrownThrowable
    is AssertionError -> throw thrownThrowable
    else -> throw AssertionErrorBuilder.create()
      .withMessage("Expected exception ${expectedExceptionClass.bestName()} but a ${thrownThrowable::class.simpleName} was thrown instead.")
      .withCause(thrownThrowable)
      .build()
  }
}
