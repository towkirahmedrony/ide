package com.agentx.app.context

/**
 * Paths the Context Engine must never read into a model prompt: environment
 * files, credentials, private keys and keystores.
 *
 * The rules are deliberately name- and location-based and are evaluated before
 * any read, so a protected file is never even opened. Matching is intentionally
 * conservative: it protects every `.env*` file (including `.env.example`) and
 * every secret-like name in a data/config format, and it does not try to guess
 * from source-file names such as `Auth.kt` or `SecretRedactor.kt`.
 */
object ProtectedPaths {

    private val protectedDirectorySegments = setOf(
        ".ssh",
        ".aws",
        ".gnupg",
        ".docker",
        ".kube",
        "keystores",
    )

    private val protectedExtensions = setOf(
        "pem",
        "key",
        "p12",
        "pfx",
        "jks",
        "keystore",
        "ppk",
    )

    private val protectedNames = setOf(
        "id_rsa",
        "id_dsa",
        "id_ecdsa",
        "id_ed25519",
        "netrc",
        ".netrc",
        ".htpasswd",
        "credentials",
        "secrets",
        "secrets.json",
        "secrets.yml",
        "secrets.yaml",
        "service-account.json",
        "google-services.json",
        "local.properties",
    )

    /** Formats in which a secret-like name really does hold a secret. */
    private val dataExtensions = setOf(
        "json",
        "yaml",
        "yml",
        "toml",
        "properties",
        "ini",
        "env",
        "txt",
        "cfg",
        "conf",
        "xml",
        "sh",
        "bash",
        "zsh",
        "ps1",
        "cmd",
        "bat",
        "lock",
        "credentials",
    )

    private val secretishKeywords = listOf(
        "secret",
        "credential",
        "password",
        "apikey",
        "api-key",
        "api_key",
        "privatekey",
        "private-key",
    )

    /** Human-readable reason [path] is protected, or null when it is fine. */
    fun reason(path: String): String? {
        val normalized = path.replace('\\', '/').trim().trim('/')
        if (normalized.isEmpty()) return null
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        val name = segments.last().lowercase()
        val extension = name.substringAfterLast('.', "")

        segments.dropLast(1).firstOrNull { it.lowercase() in protectedDirectorySegments }?.let {
            return "Inside the protected directory '$it'"
        }
        if (name.startsWith(".env")) return "Environment file"
        if (name.endsWith(".env")) return "Environment file"
        if (extension in protectedExtensions) return "Credential or key file (*.$extension)"
        if (name in protectedNames) return "Credential file"
        if (secretishKeywords.any { it in name } && (extension.isEmpty() || extension in dataExtensions)) {
            return "Secret-like file name"
        }
        return null
    }

    fun isProtected(path: String): Boolean = reason(path) != null
}
