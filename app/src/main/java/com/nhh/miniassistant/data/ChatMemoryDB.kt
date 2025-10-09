package com.nhh.miniassistant.data

import org.koin.core.annotation.Single

@Single
class ChatMemoryDB {
    private val memoryBox = ObjectBoxStore.store.boxFor(ChatMemory::class.java)

    fun addMemory(memory: ChatMemory) {
        memoryBox.put(memory)

        val q = memoryBox.query(ChatMemory_.memoryId.greater(0))
            .orderDesc(ChatMemory_.ts)
            .build()

        val oldIds: List<Long> = q.findIds(10, Long.MAX_VALUE).toList()
        if (oldIds.isNotEmpty()) {
            memoryBox.removeByIds(oldIds)
        }
    }

    fun getRecentMemories(): List<ChatMemory> {
        val recent =  memoryBox.query(ChatMemory_.memoryId.greater(0))
            .orderDesc(ChatMemory_.ts)
            .build()
            .find(0, 10)
        return recent.asReversed()
    }

    fun clearAllMemories() {
        memoryBox.removeAll()
    }
}