package org.example.config

/**
 * Developer profile for agent personalization.
 * Loaded from config/developer_profile.yaml at startup.
 */
data class DeveloperProfile(
    val name: String,
    val role: String,
    val company: String,
    val project: String,
    val expertiseLevel: String,
    val primaryStack: List<String>,
    val secondaryStack: List<String>,
    val architecture: ArchitecturePrefs,
    val communication: CommunicationPrefs,
    val workflow: WorkflowPrefs,
    val petProjects: List<PetProject>,
    val interests: List<String>,
    val growthAreas: List<String>
)

data class ArchitecturePrefs(
    val patterns: List<String>,
    val principles: List<String>,
    val structure: String
)

data class CommunicationPrefs(
    val style: String,
    val feedback: String,
    val explanations: String,
    val language: String
)

data class WorkflowPrefs(
    val planning: String,
    val sessionLength: String,
    val aiAssisted: Boolean
)

data class PetProject(
    val name: String,
    val description: String,
    val stack: List<String>
)
