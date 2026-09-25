package com.aicodemax.ai.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** CP-148: the one JSON plan dialect the LLM planners speak (shared, tested once). */
object PlanJson {
    private val json = Json { ignoreUnknownKeys = true }

    data class JsonStep(
        val capability: String,
        val args: Map<String, String>,
        val why: String,
    )

    data class ParsedPlan(val steps: List<JsonStep>, val note: String)

    /** Extracts the first {...} block (the model may wrap it in prose) and parses it. */
    fun parse(text: String): ParsedPlan? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = try {
            json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        } catch (_: Exception) {
            return null
        }
        val steps = try {
            obj["steps"]?.jsonArray?.map { el ->
                val o: JsonObject = el.jsonObject
                JsonStep(
                    capability = o["capability"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                    args = o["args"]?.jsonObject?.entries?.associate { (k, v) ->
                        k to (v.jsonPrimitive.contentOrNull ?: v.toString())
                    }.orEmpty(),
                    why = o["why"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                )
            }
        } catch (_: Exception) {
            return null
        } ?: return null
        if (steps.any { it.capability.isEmpty() }) return null
        val note = obj["note"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return ParsedPlan(steps, note)
    }

    const val SKELETON = "{\"steps\":[{\"capability\":\"files.read\",\"args\":{\"path\":\"a.txt\"},\"why\":\"...\"}]}"
}
