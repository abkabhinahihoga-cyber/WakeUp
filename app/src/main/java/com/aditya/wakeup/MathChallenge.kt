package com.aditya.wakeup

import java.io.Serializable
import java.util.Random

data class MathProblem(
    val questionText: String,
    val answer: Int
) : Serializable

object MathChallenge {
    val difficulties = listOf("easy", "medium", "hard")

    fun difficultyTitle(difficulty: String): String = when (difficulty.lowercase()) {
        "hard" -> "Hard (×, ÷, multi-step)"
        "medium" -> "Medium (+, -, ×)"
        else -> "Easy (+, -)"
    }

    fun difficultyDescription(difficulty: String): String = when (difficulty.lowercase()) {
        "hard" -> "Double-digit multiplication, exact integer division, and multi-step arithmetic."
        "medium" -> "Two-digit addition and subtraction, plus single-digit multiplication."
        else -> "Simple non-negative addition and subtraction with small numbers."
    }

    /**
     * Generates a single valid arithmetic question tailored to the chosen difficulty.
     * Guaranteed:
     * - No division by zero.
     * - Division problems ALWAYS produce exact integer quotients without remainders.
     * - Easy difficulty yields strictly non-negative results with single/small operands.
     * - Non-repeating random variety.
     */
    fun generateProblem(difficulty: String, random: Random = Random()): MathProblem {
        return when (difficulty.lowercase()) {
            "hard" -> generateHardProblem(random)
            "medium" -> generateMediumProblem(random)
            else -> generateEasyProblem(random)
        }
    }

    /**
     * Generates a list of [count] arithmetic problems for a full challenge sequence.
     */
    fun generateSet(difficulty: String, count: Int, random: Random = Random()): List<MathProblem> {
        val targetCount = count.coerceIn(1, 20)
        val problems = mutableListOf<MathProblem>()
        val seenQuestions = mutableSetOf<String>()

        var attempts = 0
        while (problems.size < targetCount && attempts < 100) {
            attempts++
            val p = generateProblem(difficulty, random)
            if (p.questionText !in seenQuestions) {
                seenQuestions.add(p.questionText)
                problems.add(p)
            }
        }

        // Fallback fill if needed
        while (problems.size < targetCount) {
            problems.add(generateProblem(difficulty, random))
        }

        return problems
    }

    fun isAnswerCorrect(problem: MathProblem, userInput: String): Boolean {
        val parsed = userInput.trim().toIntOrNull() ?: return false
        return parsed == problem.answer
    }

    private fun generateEasyProblem(random: Random): MathProblem {
        val op = random.nextInt(2) // 0: +, 1: -
        return if (op == 0) {
            val a = random.nextInt(19) + 2 // 2..20
            val b = random.nextInt(19) + 2 // 2..20
            MathProblem("$a + $b", a + b)
        } else {
            val a = random.nextInt(26) + 5 // 5..30
            val b = random.nextInt(a - 1) + 1 // 1..(a-1), ensuring result >= 1
            MathProblem("$a - $b", a - b)
        }
    }

    private fun generateMediumProblem(random: Random): MathProblem {
        val op = random.nextInt(3) // 0: +, 1: -, 2: *
        return when (op) {
            0 -> {
                val a = random.nextInt(80) + 15 // 15..94
                val b = random.nextInt(80) + 15 // 15..94
                MathProblem("$a + $b", a + b)
            }
            1 -> {
                val a = random.nextInt(90) + 25 // 25..114
                val b = random.nextInt(a - 15) + 10 // 10..(a-6), non-negative
                MathProblem("$a - $b", a - b)
            }
            else -> {
                val a = random.nextInt(10) + 3 // 3..12
                val b = random.nextInt(10) + 3 // 3..12
                MathProblem("$a × $b", a * b)
            }
        }
    }

    private fun generateHardProblem(random: Random): MathProblem {
        val op = random.nextInt(3) // 0: *, 1: /, 2: multi-step
        return when (op) {
            0 -> {
                val a = random.nextInt(15) + 11 // 11..25
                val b = random.nextInt(11) + 4 // 4..14
                MathProblem("$a × $b", a * b)
            }
            1 -> {
                // Exact integer division: choose divisor b and quotient c, dividend a = b * c
                val b = random.nextInt(10) + 3 // 3..12 (no div by zero)
                val c = random.nextInt(18) + 3 // 3..20
                val a = b * c
                MathProblem("$a ÷ $b", c)
            }
            else -> {
                // Multi-step arithmetic: (a * b) + c or (a + b) - c
                if (random.nextBoolean()) {
                    val a = random.nextInt(8) + 3 // 3..10
                    val b = random.nextInt(8) + 3 // 3..10
                    val c = random.nextInt(25) + 5 // 5..29
                    MathProblem("$a × $b + $c", (a * b) + c)
                } else {
                    val a = random.nextInt(45) + 20 // 20..64
                    val b = random.nextInt(45) + 20 // 20..64
                    val c = random.nextInt(35) + 10 // 10..44
                    MathProblem("$a + $b - $c", a + b - c)
                }
            }
        }
    }
}
