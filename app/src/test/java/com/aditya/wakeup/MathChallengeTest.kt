package com.aditya.wakeup

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class MathChallengeTest {

    @Test
    fun easyDifficultyQuestionsAreNonNegativeAndValid() {
        val random = Random(42)
        repeat(100) {
            val problem = MathChallenge.generateProblem("easy", random)
            assertTrue("Question must not be empty", problem.questionText.isNotBlank())
            assertTrue("Easy answers must be non-negative, got ${problem.answer} for '${problem.questionText}'", problem.answer >= 0)

            // Verify evaluation matches question
            val parts = problem.questionText.split(" ")
            assertEquals(3, parts.size)
            val a = parts[0].toInt()
            val op = parts[1]
            val b = parts[2].toInt()

            val expected = if (op == "+") a + b else a - b
            assertEquals(expected, problem.answer)
            assertTrue(MathChallenge.isAnswerCorrect(problem, problem.answer.toString()))
            assertFalse(MathChallenge.isAnswerCorrect(problem, (problem.answer + 1).toString()))
            assertFalse(MathChallenge.isAnswerCorrect(problem, "invalid"))
        }
    }

    @Test
    fun mediumDifficultyQuestionsIncludeMultiplicationAndAreValid() {
        val random = Random(123)
        var sawMultiplication = false
        var sawAddition = false
        var sawSubtraction = false

        repeat(150) {
            val problem = MathChallenge.generateProblem("medium", random)
            val text = problem.questionText
            if (text.contains("×")) sawMultiplication = true
            if (text.contains("+")) sawAddition = true
            if (text.contains("-")) sawSubtraction = true

            val parts = text.split(" ")
            val a = parts[0].toInt()
            val op = parts[1]
            val b = parts[2].toInt()
            val expected = when (op) {
                "+" -> a + b
                "-" -> a - b
                "×" -> a * b
                else -> fail("Unknown op: $op")
            }
            assertEquals(expected, problem.answer)
            assertTrue(MathChallenge.isAnswerCorrect(problem, expected.toString()))
        }

        assertTrue("Medium difficulty must generate multiplication", sawMultiplication)
        assertTrue("Medium difficulty must generate addition", sawAddition)
        assertTrue("Medium difficulty must generate subtraction", sawSubtraction)
    }

    @Test
    fun hardDifficultyQuestionsIncludeExactIntegerDivisionAndMultiStep() {
        val random = Random(999)
        var sawDivision = false
        var sawMultiStep = false

        repeat(200) {
            val problem = MathChallenge.generateProblem("hard", random)
            val text = problem.questionText
            if (text.contains("÷")) {
                sawDivision = true
                val parts = text.split(" ")
                val a = parts[0].toInt()
                val b = parts[2].toInt()
                assertTrue("Division must never divide by zero", b != 0)
                assertEquals("Dividend must be an exact multiple of divisor", 0, a % b)
                assertEquals(a / b, problem.answer)
            } else if (text.contains("+") && text.contains("-") || text.contains("×") && text.contains("+")) {
                sawMultiStep = true
            }

            assertTrue(MathChallenge.isAnswerCorrect(problem, problem.answer.toString()))
            assertFalse(MathChallenge.isAnswerCorrect(problem, (problem.answer + 5).toString()))
        }

        assertTrue("Hard difficulty must generate integer division", sawDivision)
        assertTrue("Hard difficulty must generate multi-step arithmetic", sawMultiStep)
    }

    @Test
    fun generateSetProducesUniqueProblems() {
        val set = MathChallenge.generateSet("medium", 5, Random(555))
        assertEquals(5, set.size)
        val uniqueQuestions = set.map { it.questionText }.toSet()
        assertEquals("Problems within set should be distinct", set.size, uniqueQuestions.size)
    }

    @Test
    fun difficultyTitlesAndDescriptionsArePresent() {
        for (d in MathChallenge.difficulties) {
            val title = MathChallenge.difficultyTitle(d)
            val desc = MathChallenge.difficultyDescription(d)
            assertTrue("Title for $d must not be blank", title.isNotBlank())
            assertTrue("Description for $d must not be blank", desc.isNotBlank())
        }
    }
}
