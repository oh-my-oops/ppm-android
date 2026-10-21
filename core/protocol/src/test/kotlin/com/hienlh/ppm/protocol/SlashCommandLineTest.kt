package com.hienlh.ppm.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class SlashCommandLineTest {
    @Test
    fun `a command is read as the web's pattern reads it`() {
        // Each answer as `/^\/(\S+)(?:\s+([\s\S]+))?$/` gives it in node.
        val cases = listOf(
            "/clear" to SlashCommandLine("clear"),
            "/clear My topic" to SlashCommandLine("clear", "My topic"),
            "/clear \n  two\nlines" to SlashCommandLine("clear", "two\nlines"),
            "/sk" to SlashCommandLine("sk"),
            "//x y" to SlashCommandLine("/x", "y"),
            "/clear " to null,
            "/clear  " to SlashCommandLine("clear", " "),
            "/" to null,
            "/ clear" to null,
            "clear" to null,
            " /clear" to null,
            "Use the planner agent to /clear" to null,
        )
        for ((text, expected) in cases) assertEquals(text, expected, SlashCommandLine.parse(text))
    }
}
