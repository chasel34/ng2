package com.chasel.ng2n.core.local

data class DiceSeed(
  val authorId: Long,
  val tid: Long,
  val pid: Long,
)

sealed interface DiceTerm {
  val value: Long

  data class Roll(val faces: Long, override val value: Long) : DiceTerm

  data class Constant(override val value: Long) : DiceTerm
}

data class DiceOutcome(
  val expression: String,
  val terms: List<DiceTerm>,
  val sum: Long?,
)

data class DiceScope(
  val expressions: List<String> = emptyList(),
  val collapses: List<DiceScope> = emptyList(),
) {
  fun flatten(): List<String> = buildList {
    addAll(expressions)
    collapses.forEach { addAll(it.flatten()) }
  }
}

private const val MAX_DICE = 10L

private const val MAX_FACES = 100000L

private val TERM_PATTERN = Regex("""(\+)(\d{0,10})(?:(d)(\d{1,10}))?""")

private const val SEED_OFFSET_MIN_TID = 10246184L
private const val SEED_OFFSET_MIN_PID = 200188932L

private const val LCG_MULTIPLIER = 9301L
private const val LCG_INCREMENT = 49297L
private const val LCG_MODULUS = 233280L

private class DiceStream(val origin: Long, var state: Long)

private fun nextRandom(stream: DiceStream): Double {
  if (stream.state == 0L) stream.state = stream.origin
  stream.state = (stream.state * LCG_MULTIPLIER + LCG_INCREMENT) % LCG_MODULUS
  return stream.state.toDouble() / LCG_MODULUS.toDouble()
}

private fun rollExpression(expression: String, stream: DiceStream): DiceOutcome {
  val terms = ArrayList<DiceTerm>()
  var sum = 0L
  var outOfLimit = false

  for (match in TERM_PATTERN.findAll("+$expression")) {
    val countText = match.groupValues[2]
    val diceMark = match.groups[3]
    val facesText = match.groupValues[4]
    val count = if (countText.isEmpty()) {
      if (diceMark == null) 0L else 1L
    } else {
      countText.toLong()
    }

    if (diceMark == null) {
      terms.add(DiceTerm.Constant(count))
      sum += count
      continue
    }

    val faces = facesText.toLong()
    if (count > MAX_DICE || faces > MAX_FACES) {
      outOfLimit = true
      continue
    }
    repeat(count.toInt()) {
      val value = kotlin.math.floor(nextRandom(stream) * faces.toDouble()).toLong() + 1L
      terms.add(DiceTerm.Roll(faces, value))
      sum += value
    }
  }

  return DiceOutcome(expression, terms, if (outOfLimit) null else sum)
}

fun resolveDice(scope: DiceScope, seed: DiceSeed): List<DiceOutcome> {
  val outcomes = ArrayList<DiceOutcome>()
  val base = seed.authorId + seed.tid + seed.pid
  val offsetAllowed = seed.tid > SEED_OFFSET_MIN_TID || seed.pid > SEED_OFFSET_MIN_PID

  fun fill(current: DiceScope, stream: DiceStream) {
    for (expression in current.expressions) {
      outcomes.add(rollExpression(expression, stream))
    }

    current.collapses.forEachIndexed { index, child ->
      val origin = if (stream.state != 0L) {
        stream.state
      } else {
        base + (if (offsetAllowed) index + 1L else 0L)
      }
      fill(child, DiceStream(origin, stream.state))
    }
  }

  fill(scope, DiceStream(base, 0L))
  return outcomes
}

fun formatDiceTerms(terms: List<DiceTerm>): String = terms.joinToString("+") { term ->
  when (term) {
    is DiceTerm.Roll -> "d${term.faces}(${term.value})"
    is DiceTerm.Constant -> term.value.toString()
  }
}
