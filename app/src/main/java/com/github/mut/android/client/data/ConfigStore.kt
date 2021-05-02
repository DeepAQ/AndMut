package com.github.mut.android.client.data

import android.content.Context
import org.json.JSONArray

class ConfigStore(val ctx: Context) {
    companion object {
        private const val CONFIG_FILENAME = "config.json"
    }

    private val configList = ArrayList<MutConfig>()

    init {
        reload()
    }

    fun count() = configList.size

    operator fun get(i: Int) = configList[i]

    operator fun set(i: Int, config: MutConfig) {
        configList[i] = config
        save()
    }

    operator fun plusAssign(config: MutConfig) {
        configList += config
        save()
    }

    fun removeAt(i: Int) {
        configList.removeAt(i)
        save()
    }

    fun reload() {
        configList.clear()

        try {
            ctx.openFileInput(CONFIG_FILENAME).use { input ->
                input.reader().use { reader ->
                    val objs = JSONArray(reader.readText())
                    for (i in 0..objs.length()) {
                        val obj = objs.getJSONObject(i)
                        configList.add(MutConfig(obj.getString("name"), obj.getString("outbound")))
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun save() {
        try {
            val json = JSONArray(configList
                .map { x -> mapOf("name" to x.displayName, "outbound" to x.outboundUrl) }
                .toList()).toString()
            ctx.openFileOutput(CONFIG_FILENAME, Context.MODE_PRIVATE).use { output ->
                output.writer().use { writer ->
                    writer.write(json)
                    writer.flush()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}