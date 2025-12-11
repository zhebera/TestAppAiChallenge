# Code Review Checklist

This document provides a structured checklist for LLM-assisted code reviews. Use it to systematically evaluate code changes in this project.

---

## 1. Correctness

- [ ] Does the code do what it's supposed to do?
- [ ] Are all edge cases handled (null values, empty collections, boundary conditions)?
- [ ] Are error states handled appropriately?
- [ ] Does the logic flow make sense? Are there any unreachable code paths?
- [ ] Are all resources properly closed/released (HTTP clients, streams, files)?

## 2. Architecture & Design

- [ ] Does the code follow the project's layered architecture (presentation → domain → data)?
- [ ] Are dependencies flowing in the correct direction (inward toward domain)?
- [ ] Is the Single Responsibility Principle followed?
- [ ] Are interfaces used appropriately for abstraction (e.g., `LlmClient`, `SummaryClient`)?
- [ ] Is there unnecessary coupling between components?
- [ ] Are use cases focused on single business operations?

## 3. Kotlin Idioms & Best Practices

- [ ] Are nullable types handled safely (`?.`, `?:`, `let`, `require`, `check`)?
- [ ] Is immutability preferred where possible (`val` over `var`, immutable collections)?
- [ ] Are data classes used for DTOs and value objects?
- [ ] Are extension functions used appropriately?
- [ ] Is scope function usage idiomatic (`let`, `apply`, `run`, `also`, `with`)?
- [ ] Are sealed classes/interfaces used for representing restricted hierarchies?

## 4. Coroutines & Concurrency

- [ ] Are suspend functions used correctly?
- [ ] Is structured concurrency followed (proper scope usage)?
- [ ] Are Flows collected safely?
- [ ] Is there potential for race conditions or deadlocks?
- [ ] Are cancellation and timeouts handled properly?
- [ ] Is `runBlocking` avoided outside of main/test entry points?

## 5. Error Handling

- [ ] Are exceptions caught at appropriate levels?
- [ ] Are errors logged with sufficient context?
- [ ] Is the user informed of errors in a meaningful way?
- [ ] Are retries implemented with backoff where appropriate?
- [ ] Are fallback mechanisms in place for critical operations?

## 6. Security

- [ ] Are API keys and secrets handled securely (not hardcoded, not logged)?
- [ ] Is user input validated and sanitized?
- [ ] Are HTTP connections using HTTPS?
- [ ] Is sensitive data excluded from logs and error messages?
- [ ] Are there any potential injection vulnerabilities?

## 7. Performance

- [ ] Are there unnecessary allocations or object creation in hot paths?
- [ ] Is lazy initialization used where beneficial?
- [ ] Are collections sized appropriately?
- [ ] Is there potential for N+1 query problems or excessive API calls?
- [ ] Are timeouts configured for external calls?

## 8. Testability

- [ ] Can the code be unit tested in isolation?
- [ ] Are dependencies injected rather than created internally?
- [ ] Are side effects isolated and mockable?
- [ ] Is business logic separated from I/O operations?

## 9. Readability & Maintainability

- [ ] Are variable and function names clear and descriptive?
- [ ] Is the code self-documenting? Are complex parts commented?
- [ ] Are magic numbers replaced with named constants?
- [ ] Is the code formatted consistently?
- [ ] Are functions/classes of reasonable size?

## 10. API & Contract Compliance

- [ ] Do DTOs match expected API request/response formats?
- [ ] Are required fields marked appropriately in serialization?
- [ ] Is JSON serialization configured correctly (`ignoreUnknownKeys`, etc.)?
- [ ] Are API error responses parsed and handled?

## 11. Code Style & Formatting

### Naming Conventions
- [ ] Classes use `PascalCase` (e.g., `ChatRepository`, `LlmClient`)
- [ ] Functions and properties use `camelCase` (e.g., `sendMessage`, `inputTokens`)
- [ ] Constants use `SCREAMING_SNAKE_CASE` (e.g., `MAX_RETRIES`, `API_URL`)
- [ ] Packages use lowercase with no underscores (e.g., `org.example.data.network`)
- [ ] Boolean properties/functions use prefixes: `is`, `has`, `can`, `should` (e.g., `isEnabled`, `hasError`)
- [ ] Factory functions use `create` or `build` prefix (e.g., `buildHttpClient`, `createRequest`)
- [ ] Suspend functions that return `Flow` should NOT have `suspend` modifier
- [ ] Acronyms in names follow camelCase rules (e.g., `LlmClient`, not `LLMClient`; `apiKey`, not `APIKey`)

### Function Guidelines
- [ ] Functions should do one thing and do it well (Single Responsibility)
- [ ] Function length: prefer under 30 lines; maximum 50 lines
- [ ] Parameter count: prefer 3 or fewer; maximum 5 (use data class for more)
- [ ] Avoid boolean parameters — use sealed class or enum instead
- [ ] Use named arguments for functions with 3+ parameters or boolean arguments
- [ ] Expression body (`=`) for single-expression functions
- [ ] Return early to reduce nesting (guard clauses)

### Class Guidelines
- [ ] Class length: prefer under 200 lines; maximum 400 lines
- [ ] One class per file (except small related classes like DTOs)
- [ ] File name matches the primary class name
- [ ] Order: properties → init → public methods → private methods → companion object
- [ ] Use `object` for stateless utilities, `class` for stateful components
- [ ] Prefer composition over inheritance

### Formatting
- [ ] Indentation: 4 spaces (no tabs)
- [ ] Line length: maximum 120 characters
- [ ] Blank line between functions and logical blocks
- [ ] No trailing whitespace
- [ ] File ends with a single newline
- [ ] Consistent brace style (opening brace on same line)
- [ ] Space after `if`, `when`, `for`, `while`, `catch` keywords
- [ ] No space before `:` in type declarations, space after

### Import Guidelines
- [ ] No wildcard imports (`import foo.*`)
- [ ] Group imports: Kotlin stdlib → third-party → project imports
- [ ] Remove unused imports
- [ ] Use import aliases sparingly and only when necessary

### Documentation
- [ ] Public API should have KDoc comments
- [ ] Use `/** */` for documentation, `//` for implementation notes
- [ ] Document non-obvious behavior, edge cases, and "why" (not "what")
- [ ] Keep comments up-to-date with code changes
- [ ] Avoid redundant comments that repeat the code

### String & Literal Guidelines
- [ ] Use string templates `"$variable"` instead of concatenation
- [ ] Multi-line strings use `trimIndent()` or `trimMargin()`
- [ ] Extract magic numbers to named constants
- [ ] Use `_` as separator in large numbers (e.g., `1_000_000`)

---

## Review Output Format

When reviewing code, structure your feedback as:

```
### Summary
Brief overview of the changes and overall assessment.

### Critical Issues
Issues that must be fixed before merging.

### Suggestions
Improvements that would enhance the code but aren't blocking.

### Questions
Clarifications needed to complete the review.

### Positive Notes
What was done well.
```

---

## Project-Specific Considerations

### LLM Client Implementation
- Verify streaming is handled correctly with proper buffer management
- Check that token counting is accurate
- Ensure system prompts are applied consistently

### History Compression
- Verify compression threshold is appropriate
- Check that recent messages are preserved correctly
- Ensure summary quality doesn't degrade conversation context

### Console Interaction
- Verify command parsing handles edge cases
- Check that output formatting is consistent
- Ensure graceful handling of EOF/input errors