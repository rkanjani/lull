package com.rohankanjani.lull

import kotlinx.serialization.json.Json

/** How Lull reads and writes what it saves: anything a later version added is skipped rather than refused. */
val lullJson = Json { ignoreUnknownKeys = true }
