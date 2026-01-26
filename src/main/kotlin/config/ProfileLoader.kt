package org.example.config

import java.io.File

/**
 * Loads developer profile from YAML config file.
 * Uses manual parsing (no YAML library dependency).
 */
object ProfileLoader {

    private const val CONFIG_PATH = "config/developer_profile.yaml"

    /**
     * Loads and parses developer profile from config/developer_profile.yaml.
     * @return DeveloperProfile if file exists and parsing succeeds, null otherwise.
     */
    fun load(): DeveloperProfile? {
        val file = File(CONFIG_PATH)
        if (!file.exists()) return null

        return try {
            val content = file.readText()
            val parsed = parseYaml(content)
            buildProfile(parsed)
        } catch (e: Exception) {
            println("Failed to load profile: ${e.message}")
            null
        }
    }

    /**
     * Parses YAML content into a nested Map structure.
     */
    private fun parseYaml(content: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val lines = content.lines()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            // Skip empty lines and comments
            if (line.isBlank() || line.trimStart().startsWith("#")) {
                i++
                continue
            }

            val indent = line.takeWhile { it == ' ' }.length

            // Only process top-level keys (indent 0)
            if (indent == 0) {
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val key = line.substring(0, colonIndex).trim()
                    val valueAfterColon = line.substring(colonIndex + 1).trim()

                    if (valueAfterColon.isNotEmpty()) {
                        // Simple key: value
                        result[key] = parseValue(valueAfterColon)
                    } else {
                        // Block starts - could be list or nested object
                        val (blockValue, newIndex) = parseBlock(lines, i + 1, 2)
                        result[key] = blockValue
                        i = newIndex
                        continue
                    }
                }
            }
            i++
        }

        return result
    }

    /**
     * Parses a block (list or object) starting at the given index with expected indentation.
     * Returns the parsed value and the new line index.
     */
    private fun parseBlock(lines: List<String>, startIndex: Int, expectedIndent: Int): Pair<Any, Int> {
        if (startIndex >= lines.size) return Pair(emptyList<String>(), startIndex)

        // Look at first non-empty line to determine block type
        var firstContentIndex = startIndex
        while (firstContentIndex < lines.size) {
            val line = lines[firstContentIndex]
            if (line.isBlank() || line.trimStart().startsWith("#")) {
                firstContentIndex++
                continue
            }
            break
        }

        if (firstContentIndex >= lines.size) return Pair(emptyList<String>(), firstContentIndex)

        val firstLine = lines[firstContentIndex]
        val firstIndent = firstLine.takeWhile { it == ' ' }.length

        // Check if we've exited the block
        if (firstIndent < expectedIndent) {
            return Pair(emptyList<String>(), startIndex)
        }

        val trimmedFirst = firstLine.trimStart()

        return if (trimmedFirst.startsWith("- ")) {
            // It's a list
            parseList(lines, startIndex, expectedIndent)
        } else {
            // It's a nested object
            parseNestedObject(lines, startIndex, expectedIndent)
        }
    }

    /**
     * Parses a YAML list starting at the given index.
     */
    private fun parseList(lines: List<String>, startIndex: Int, expectedIndent: Int): Pair<Any, Int> {
        val items = mutableListOf<Any>()
        var i = startIndex

        while (i < lines.size) {
            val line = lines[i]

            // Skip empty lines and comments
            if (line.isBlank() || line.trimStart().startsWith("#")) {
                i++
                continue
            }

            val indent = line.takeWhile { it == ' ' }.length

            // Check if we've exited the block
            if (indent < expectedIndent) {
                break
            }

            val trimmed = line.trimStart()

            if (trimmed.startsWith("- ")) {
                val itemContent = trimmed.substring(2)

                // Check if this is a list item with nested content (like "- name: value")
                if (itemContent.contains(":")) {
                    val colonIndex = itemContent.indexOf(':')
                    val key = itemContent.substring(0, colonIndex).trim()
                    val value = itemContent.substring(colonIndex + 1).trim()

                    // This is start of a list-of-objects item
                    val obj = mutableMapOf<String, Any>()
                    obj[key] = parseValue(value)

                    // Parse remaining properties of this object
                    i++
                    while (i < lines.size) {
                        val nextLine = lines[i]
                        if (nextLine.isBlank() || nextLine.trimStart().startsWith("#")) {
                            i++
                            continue
                        }

                        val nextIndent = nextLine.takeWhile { it == ' ' }.length
                        val nextTrimmed = nextLine.trimStart()

                        // If we hit another list item or lower indent, stop
                        if (nextIndent <= expectedIndent) {
                            break
                        }

                        // Parse property of current object
                        if (nextTrimmed.contains(":")) {
                            val propColonIndex = nextTrimmed.indexOf(':')
                            val propKey = nextTrimmed.substring(0, propColonIndex).trim()
                            val propValueStr = nextTrimmed.substring(propColonIndex + 1).trim()

                            if (propValueStr.isEmpty()) {
                                // Nested block inside object (like stack list in pet_project)
                                val (nestedValue, newIndex) = parseBlock(lines, i + 1, nextIndent + 2)
                                obj[propKey] = nestedValue
                                i = newIndex
                                continue
                            } else {
                                obj[propKey] = parseValue(propValueStr)
                            }
                        }
                        i++
                    }
                    items.add(obj)
                    continue
                } else {
                    // Simple list item
                    items.add(parseValue(itemContent))
                }
            }
            i++
        }

        return Pair(items, i)
    }

    /**
     * Parses a nested YAML object starting at the given index.
     */
    private fun parseNestedObject(lines: List<String>, startIndex: Int, expectedIndent: Int): Pair<Map<String, Any>, Int> {
        val obj = mutableMapOf<String, Any>()
        var i = startIndex

        while (i < lines.size) {
            val line = lines[i]

            // Skip empty lines and comments
            if (line.isBlank() || line.trimStart().startsWith("#")) {
                i++
                continue
            }

            val indent = line.takeWhile { it == ' ' }.length

            // Check if we've exited the block
            if (indent < expectedIndent) {
                break
            }

            val trimmed = line.trimStart()

            if (trimmed.contains(":")) {
                val colonIndex = trimmed.indexOf(':')
                val key = trimmed.substring(0, colonIndex).trim()
                val valueStr = trimmed.substring(colonIndex + 1).trim()

                if (valueStr.isEmpty()) {
                    // Nested block
                    val (nestedValue, newIndex) = parseBlock(lines, i + 1, indent + 2)
                    obj[key] = nestedValue
                    i = newIndex
                    continue
                } else {
                    obj[key] = parseValue(valueStr)
                }
            }
            i++
        }

        return Pair(obj, i)
    }

    /**
     * Parses a scalar YAML value (string, boolean, number).
     */
    private fun parseValue(value: String): Any {
        val trimmed = value.trim()

        // Remove quotes if present
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
            (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length - 1)
        }

        // Boolean
        if (trimmed.equals("true", ignoreCase = true)) return true
        if (trimmed.equals("false", ignoreCase = true)) return false

        // Number
        trimmed.toIntOrNull()?.let { return it }
        trimmed.toDoubleOrNull()?.let { return it }

        // String
        return trimmed
    }

    /**
     * Builds DeveloperProfile from parsed YAML map.
     */
    private fun buildProfile(data: Map<String, Any>): DeveloperProfile {
        return DeveloperProfile(
            name = data["name"] as? String ?: "",
            role = data["role"] as? String ?: "",
            company = data["company"] as? String ?: "",
            project = data["project"] as? String ?: "",
            expertiseLevel = data["expertise_level"] as? String ?: "",
            primaryStack = getStringList(data["primary_stack"]),
            secondaryStack = getStringList(data["secondary_stack"]),
            architecture = buildArchitecturePrefs(data["architecture"]),
            communication = buildCommunicationPrefs(data["communication"]),
            workflow = buildWorkflowPrefs(data["workflow"]),
            petProjects = buildPetProjects(data["pet_projects"]),
            interests = getStringList(data["interests"]),
            growthAreas = getStringList(data["growth_areas"])
        )
    }

    private fun buildArchitecturePrefs(data: Any?): ArchitecturePrefs {
        val map = data as? Map<*, *> ?: return ArchitecturePrefs(emptyList(), emptyList(), "")
        return ArchitecturePrefs(
            patterns = getStringList(map["patterns"]),
            principles = getStringList(map["principles"]),
            structure = map["structure"] as? String ?: ""
        )
    }

    private fun buildCommunicationPrefs(data: Any?): CommunicationPrefs {
        val map = data as? Map<*, *> ?: return CommunicationPrefs("", "", "", "")
        return CommunicationPrefs(
            style = map["style"] as? String ?: "",
            feedback = map["feedback"] as? String ?: "",
            explanations = map["explanations"] as? String ?: "",
            language = map["language"] as? String ?: ""
        )
    }

    private fun buildWorkflowPrefs(data: Any?): WorkflowPrefs {
        val map = data as? Map<*, *> ?: return WorkflowPrefs("", "", false)
        return WorkflowPrefs(
            planning = map["planning"] as? String ?: "",
            sessionLength = map["session_length"] as? String ?: "",
            aiAssisted = map["ai_assisted"] as? Boolean ?: false
        )
    }

    private fun buildPetProjects(data: Any?): List<PetProject> {
        val list = data as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            PetProject(
                name = map["name"] as? String ?: "",
                description = map["description"] as? String ?: "",
                stack = getStringList(map["stack"])
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getStringList(data: Any?): List<String> {
        return (data as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    }
}
