package arrow.core

import arrow.core.test.nonEmptyList
import arrow.platform.stackSafeIteration
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainOnlyOnce
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.negativeInt
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.set
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.kotest.property.exhaustive.exhaustive
import kotlinx.coroutines.test.runTest
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test

class NonEmptyListTest {

  @Test
  fun nonEmptyList() = runTest {
    checkAll(
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
    ) { nel1, nel2, nel3 ->
      (nel1 + nel2) + nel3 shouldBe nel1 + (nel2 + nel3)
    }
  }

  @Test
  fun iterableToNonEmptyListOrNullShouldRoundTrip() = runTest {
    checkAll(Arb.nonEmptyList(Arb.int())) { nonEmptyList ->
      nonEmptyList.all.toNonEmptyListOrNull().shouldNotBeNull() shouldBe nonEmptyList
    }
  }

  @Test
  fun iterableToNonEmptyListOrNoneShouldRoundTrip() = runTest {
    checkAll(Arb.nonEmptyList(Arb.int())) { nonEmptyList ->
      nonEmptyList.all.toNonEmptyListOrNone() shouldBe nonEmptyList.some()
    }
  }

  @Test
  fun iterableToNonEmptyListOrNullShouldReturnNullForAnEmptyIterable() = runTest {
    listOf<String>().toNonEmptyListOrNull().shouldBeNull()
  }

  @Test
  fun iterableToNonEmptyListOrNullShouldWorkCorrectlyWhenTheIterableStartsWithOrContainsNull() = runTest {
    checkAll(Arb.list(Arb.int())) { list ->
      checkAll(iterations = 5, Arb.int(min = 0, max = list.size)) { ix ->
        val mutableList: MutableList<Int?> = list.toMutableList()
        mutableList.add(ix, null)
        mutableList.toNonEmptyListOrNull().shouldNotBeNull() shouldBe mutableList
      }
    }
  }

  @Test
  fun canAlignListsWithDifferentLengths() = runTest {
    checkAll(Arb.nonEmptyList(Arb.boolean()), Arb.nonEmptyList(Arb.boolean())) { a, b ->
      val result = a.align(b)

      result.size shouldBe max(a.size, b.size)
      result.take(min(a.size, b.size)).forEach {
        it.isBoth() shouldBe true
      }
      result.drop(min(a.size, b.size)).forEach {
        if (a.size < b.size) {
          it.isRight() shouldBe true
        } else {
          it.isLeft() shouldBe true
        }
      }
    }
  }

  @Test
  fun mapOrAccumulateIsStackSafeAndRunsInOriginalOrder() = runTest {
    val acc = mutableListOf<Int>()
    val res = (0..stackSafeIteration())
      .toNonEmptyListOrNull()
      .shouldNotBeNull()
      .mapOrAccumulate(String::plus) {
        acc.add(it)
        it
      }
    res shouldBe Either.Right(acc)
    res shouldBe Either.Right((0..stackSafeIteration()).toList())
  }

  @Test
  fun mapOrAccumulateAccumulatesErrors() = runTest {
    checkAll(Arb.nonEmptyList(Arb.int(), range = 0..20)) { nel ->
      val res = nel.mapOrAccumulate { i ->
        if (i % 2 == 0) i else raise(i)
      }

      val expected = nel.filterNot { it % 2 == 0 }
        .toNonEmptyListOrNull()?.left() ?: nel.filter { it % 2 == 0 }.right()

      res shouldBe expected
    }
  }

  @Test
  fun mapOrAccumulateAccumulatesErrorsWithCombineFunction() = runTest {
    checkAll(Arb.nonEmptyList(Arb.negativeInt(), range = 0..20)) { nel ->
      val res = nel.mapOrAccumulate(String::plus) { i ->
        if (i > 0) i else raise("Negative")
      }

      res shouldBe nel.map { "Negative" }.joinToString("").left()
    }
  }

  @Test
  fun padZip() = runTest {
    checkAll(Arb.nonEmptyList(Arb.int()), Arb.nonEmptyList(Arb.int())) { a, b ->
      val result = a.padZip(b)
      val left = a + List(max(0, b.size - a.size)) { null }
      val right = b + List(max(0, a.size - b.size)) { null }

      result shouldBe left.zip(right)
    }
  }

  @Test
  fun padZipWithTransformation() = runTest {
    checkAll(Arb.nonEmptyList(Arb.int()), Arb.nonEmptyList(Arb.int())) { a, b ->
      val result = a.padZip(b, { it * 2 }, { it * 3 }, { x, y -> x + y })

      val minSize = min(a.size, b.size)
      result.size shouldBe max(a.size, b.size)
      result.take(minSize) shouldBe a.take(minSize).zip(b.take(minSize)) { x, y -> x + y }

      if (a.size > b.size) {
        result.drop(minSize) shouldBe a.drop(minSize).map { it * 2 }
      } else {
        result.drop(minSize) shouldBe b.drop(minSize).map { it * 3 }
      }
    }
  }

  @Test
  fun unzipIsTheInverseOfZip() = runTest {
    checkAll(Arb.nonEmptyList(Arb.int())) { nel ->
      val zipped = nel.zip(nel)
      val left = zipped.map { it.first }
      val right = zipped.map { it.second }

      left shouldBe nel
      right shouldBe nel
    }
  }

  @Test
  fun unzipWithSplitFunction() = runTest {
    checkAll(Arb.nonEmptyList(Arb.pair(Arb.int(), Arb.int()))) { nel ->
      val unzipped = nel.unzip(::identity)

      unzipped.first shouldBe nel.map { it.first }
      unzipped.second shouldBe nel.map { it.second }
    }
  }

  @Test
  fun zip2() = runTest {
    checkAll(Arb.nonEmptyList(Arb.int()), Arb.nonEmptyList(Arb.int())) { a, b ->
      val result = a.zip(b)
      val expected = a.all.zip(b.all).toNonEmptyListOrNull()
      result shouldBe expected
    }
  }

  @Test
  fun zip3() = runTest {
    checkAll(
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
    ) { a, b, c ->
      val result = a.zip(b, c, ::Triple)
      val expected = a.all.zip(b.all, c.all, ::Triple).toNonEmptyListOrNull()
      result shouldBe expected
    }
  }

  @Test
  fun zip4() = runTest {
    checkAll(
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
    ) { a, b, c, d ->
      val result = a.zip(b, c, d, ::Tuple4)
      val expected = a.all.zip(b.all, c.all, d.all, ::Tuple4).toNonEmptyListOrNull()
      result shouldBe expected
    }
  }

  @Test
  fun zip5() = runTest {
    checkAll(
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
    ) { a, b, c, d, e ->
      val result = a.zip(b, c, d, e, ::Tuple5)
      val expected = a.all.zip(b.all, c.all, d.all, e.all, ::Tuple5).toNonEmptyListOrNull()
      result shouldBe expected
    }
  }

  @Test
  fun zip6() = runTest {
    checkAll(
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
    ) { a, b, c, d, e, f ->
      val result = a.zip(b, c, d, e, f, ::Tuple6)
      val expected =
        a.all.zip(b.all, c.all, d.all, e.all, f.all, ::Tuple6).toNonEmptyListOrNull()
      result shouldBe expected
    }
  }

  @Test
  fun zip7() = runTest {
    checkAll(
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
    ) { a, b, c, d, e, f, g ->
      val result = a.zip(b, c, d, e, f, g, ::Tuple7)
      val expected =
        a.all.zip(b.all, c.all, d.all, e.all, f.all, g.all, ::Tuple7).toNonEmptyListOrNull()
      result shouldBe expected
    }
  }

  @Test
  fun zip8() = runTest {
    checkAll(
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
    ) { a, b, c, d, e, f, g, h ->
      val result = a.zip(b, c, d, e, f, g, h, ::Tuple8)
      val expected = a.all.zip(b.all, c.all, d.all, e.all, f.all, g.all, h.all, ::Tuple8)
        .toNonEmptyListOrNull()
      result shouldBe expected
    }
  }

  @Test
  fun zip9() = runTest {
    checkAll(
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
    ) { a, b, c, d, e, f, g, h, i ->
      val result = a.zip(b, c, d, e, f, g, h, i, ::Tuple9)
      val expected = a.all.zip(b.all, c.all, d.all, e.all, f.all, g.all, h.all, i.all, ::Tuple9)
        .toNonEmptyListOrNull()
      result shouldBe expected
    }
  }

  @Test
  fun maxElement() = runTest {
    checkAll(
      Arb.nonEmptyList(Arb.int()),
    ) { a ->
      val result = a.max()
      val expected = a.maxOrNull()
      result shouldBe expected
    }
  }

  @Test
  fun maxByElement() = runTest {
    checkAll(
      Arb.nonEmptyList(Arb.int()),
    ) { a ->
      val result = a.maxBy(::identity)
      val expected = a.maxByOrNull(::identity)
      result shouldBe expected
    }
  }

  @Test
  fun minElement() = runTest {
    checkAll(
      Arb.nonEmptyList(Arb.int()),
    ) { a ->
      val result = a.min()
      val expected = a.minOrNull()
      result shouldBe expected
    }
  }

  @Test
  fun minByElement() = runTest {
    checkAll(
      Arb.nonEmptyList(Arb.int()),
    ) { a ->
      val result = a.minBy(::identity)
      val expected = a.minByOrNull(::identity)
      result shouldBe expected
    }
  }

  @Test
  fun nonEmptyListEqualsList() = runTest {
    checkAll(
      Arb.nonEmptyList(Arb.int()),
    ) { a ->
      // `shouldBe` doesn't use the `equals` methods on `Iterable`
      (a == a.all).shouldBeTrue()
    }
  }

  @Test
  fun lastOrNull() = runTest {
    checkAll(
      Arb.nonEmptyList(Arb.int()),
    ) { a ->
      val result = a.lastOrNull()
      val expected = a.last()
      result shouldBe expected
    }
  }

  @Test
  fun extract() = runTest {
    checkAll(
      Arb.nonEmptyList(Arb.int()),
    ) { a ->
      val result = a.extract()
      val expected = a.head
      result shouldBe expected
    }
  }

  @Test
  fun plus() = runTest {
    checkAll(
      Arb.nonEmptyList(Arb.int()),
      Arb.int(),
    ) { a, b ->
      val result = a + b
      val expected = a.all + b
      result shouldBe expected
    }
  }

  @Test
  fun coflatMapKeepsLength() = runTest {
    checkAll(
      Arb.nonEmptyList(Arb.int()),
    ) { a ->
      val result = a.coflatMap { it.all }
      val expected = a.all
      result.size shouldBe expected.size
    }
  }

  @Test
  fun foldLeftAddition() = runTest {
    checkAll(
      Arb.nonEmptyList(Arb.int()),
      Arb.int(),
    ) { list, initial ->
      val result = list.foldLeft(initial) { acc, i -> acc + i }
      val expected = initial + list.all.sum()
      result shouldBe expected
    }
  }

  @Test
  fun hashCodeConsistent() = runTest {
    checkAll(Arb.nonEmptyList(Arb.int(), range = 0..10)) { a ->
      buildSet {
        repeat(3) {
          add(a.hashCode())
        }
      }.size shouldBe 1
    }
  }

  @Test
  fun toList() = runTest {
    checkAll(Arb.list(Arb.int(), range = 0..20)) { a ->
      a.toNonEmptyListOrNull()
        ?.toList()
        ?.shouldBe(a)
    }
  }

  @Test
  fun distinct() = runTest {
    val ex = listOf(1, 2, 3, 4, 5).exhaustive()
    checkAll(Arb.list(ex, range = 0..20)) { a ->
      val expected = a.distinct()

      a.toNonEmptyListOrNull()
        ?.distinct()
        ?.shouldBe(expected)
    }
  }

  @Test
  fun distinctBy() = runTest {
    val ex = listOf(1, 2, 3, 4, 5).exhaustive()
    fun selector(i: Int) = i % 2 == 0

    checkAll(Arb.list(ex, range = 0..20)) { a ->
      val expected = a.distinctBy(::selector)

      a.toNonEmptyListOrNull()
        ?.distinctBy(::selector)
        ?.shouldBe(expected)
    }
  }

  @Test
  fun flatMap() = runTest {
    fun transform(i: Int) = listOf(i) + 1

    checkAll(Arb.list(Arb.int(), range = 0..20)) { a ->
      val expected = a.flatMap(::transform)

      a.toNonEmptyListOrNull()
        ?.flatMap {
          transform(it).let(::NonEmptyList)
        }
        ?.shouldBe(expected)
    }
  }

  @Test
  fun plusIterable() = runTest {
    checkAll(
      Arb.list(Arb.int(), range = 0..10),
      Arb.list(Arb.int(), range = 0..10),
    ) { a, b ->
      a.toNonEmptyListOrNull()
        ?.also {
          it + b shouldBe (a + b)
        }
    }
  }

  @Test
  fun toStringContainsNelValues() = runTest {
    checkAll(20, Arb.set(Arb.int(0..9), 0..10)) { a ->
      a.toNonEmptyListOrNull().toString().let { s ->
        a.forEach {
          s shouldContainOnlyOnce it.toString()
        }
      }
    }
  }

  @Test
  fun zip10() = runTest {
    data class Tuple10<out A, out B, out C, out D, out E, out F, out G, out H, out I, out J>(
      val first: A,
      val second: B,
      val third: C,
      val fourth: D,
      val fifth: E,
      val sixth: F,
      val seventh: G,
      val eighth: H,
      val ninth: I,
      val tenth: J,
    )

    checkAll(
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
      Arb.nonEmptyList(Arb.int()),
    ) { a, b, c, d, e, f, g, h, i, j ->
      val result = a.zip(b, c, d, e, f, g, h, i, j, ::Tuple10)
      val expected = a.all.zip(b.all, c.all, d.all, e.all, f.all, g.all, h.all, i.all, j.all, ::Tuple10)
        .toNonEmptyListOrNull()
      result shouldBe expected
    }
  }

  @Test
  fun compareTo() = runTest {
    checkAll(Arb.list(Arb.int(), 1..10), Arb.list(Arb.int(), 1..10)) { a, b ->
      val expected = a.compareTo(b)

      a.toNonEmptyListOrThrow()
        .compareTo(b.toNonEmptyListOrThrow()) shouldBe expected
    }
  }

  @Test
  fun flatten() = runTest {
    checkAll(Arb.list(Arb.list(Arb.int(), 1..10), 1..10)) { a ->
      val expected = a.flatten()

      a.map { it.toNonEmptyListOrThrow() }
        .toNonEmptyListOrThrow().flatten() shouldBe expected
    }
  }

  @Test
  fun unzip() = runTest {
    checkAll(Arb.list(Arb.pair(Arb.int(), Arb.string(0..10)), 1..10)) { a ->
      val expA = a.map { it.first }
      val expB = a.map { it.second }

      with(a.toNonEmptyListOrThrow().unzip()) {
        first shouldBe expA
        second shouldBe expB
      }
    }
  }

  @OptIn(PotentiallyUnsafeNonEmptyOperation::class)
  @Test
  fun wrapAsNonEmptyListOrThrow() = runTest {
    checkAll(Arb.list(Arb.int(), 0..10)) { a ->
      runCatching {
        a.wrapAsNonEmptyListOrThrow()
      }.also {
        when (a.isEmpty()) {
          true -> {
            it.isFailure shouldBe true
          }
          false -> {
            it.getOrNull() shouldBe a.toNonEmptyListOrThrow()
          }
        }
      }
    }
  }

  @OptIn(PotentiallyUnsafeNonEmptyOperation::class)
  @Test
  fun wrapAsNonEmptyListOrNull() = runTest {
    checkAll(Arb.list(Arb.int(), 0..10)) { a ->
      a.wrapAsNonEmptyListOrNull() shouldBe a.toNonEmptyListOrNull()
    }
  }
}
