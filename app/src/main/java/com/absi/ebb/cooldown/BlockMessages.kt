package com.absi.ebb.cooldown

import android.content.Context
import com.absi.ebb.R
import org.json.JSONObject
import kotlin.random.Random

data class BlockMessage(val text: String, val author: String? = null, val source: String? = null)

object BlockMessages {

    /** A random message that isn't the one shown last time. */
    fun pick(all: List<BlockMessage>, previous: String?, random: Random = Random.Default): BlockMessage =
        all.filter { it.text != previous }.ifEmpty { all }.random(random)

    fun load(context: Context): List<BlockMessage> {
        val json = context.resources.openRawResource(R.raw.messages).bufferedReader().use { it.readText() }
        val bank = JSONObject(json)
        val encouraging = bank.getJSONArray("encouraging")
        val quotes = bank.getJSONArray("quotes")
        return buildList {
            for (index in 0 until encouraging.length()) add(BlockMessage(encouraging.getString(index)))
            for (index in 0 until quotes.length()) {
                val quote = quotes.getJSONObject(index)
                add(BlockMessage(quote.getString("text"), quote.getString("author"), quote.getString("source")))
            }
        }
    }
}
