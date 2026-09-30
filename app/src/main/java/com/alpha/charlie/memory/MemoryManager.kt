package com.alpha.charlie.memory

class MemoryManager(private val dao: FactDao) {

    suspend fun saveFact(rawText: String, subject: String, value: String) {
        dao.insert(
            Fact(
                subject = subject.trim(),
                value = value.trim(),
                rawText = rawText.trim()
            )
        )
    }

    suspend fun searchFacts(query: String): List<Fact> {
        return dao.search(query.trim())
    }

    suspend fun getRecentFacts(limit: Int = 20): List<Fact> {
        return dao.getRecent(limit)
    }

    suspend fun getAllFacts(): List<Fact> {
        return dao.getAll()
    }

    suspend fun factCount(): Int {
        return dao.count()
    }

    suspend fun clearAll() {
        dao.deleteAll()
    }

    fun formatFactsForPrompt(facts: List<Fact>): String {
        if (facts.isEmpty()) return "No facts stored yet."
        return facts.joinToString("\n") { "- ${it.subject}: ${it.value}" }
    }
}
