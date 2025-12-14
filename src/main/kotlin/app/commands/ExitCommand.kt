package org.example.app.commands

class ExitCommand : Command {
    override fun matches(input: String): Boolean =
        input.equals("exit", ignoreCase = true)

    override suspend fun execute(input: String, context: CommandContext): CommandResult {
        println("Выход.")
        return CommandResult.Exit
    }
}
