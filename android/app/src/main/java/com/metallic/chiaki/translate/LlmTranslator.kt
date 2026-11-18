package com.metallic.chiaki.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Claude ile yüksek kaliteli altyazı çevirisi.
 * Önceki birkaç satır bağlam olarak gönderilir, böylece isimler / hitaplar tutarlı kalır.
 */
class LlmTranslator(private val apiKey: String, private val model: String)
{
	private val system = """
		Sen GTA 6 oyununun altyazı çevirmenisin. Gelen İngilizce altyazı satırını doğal, akıcı, günlük konuşma Türkçesine çevir.
		Kurallar:
		- Sadece çeviriyi yaz; açıklama, tırnak, not ekleme.
		- Satır başındaki konuşmacı adını (ör. "LUCIA:" veya "Jason:") aynen koru.
		- Argo, küfür ve sokak ağzını yumuşatmadan Türkçedeki doğal karşılığıyla çevir.
		- Özel isimleri, mekan adlarını (Vice City, Leonida vb.) ve marka adlarını çevirme.
		- OCR kaynaklı küçük yazım hatalarını bağlamdan düzelterek çevir.
		- Kısa tut; altyazı ekranda okunacak.
	""".trimIndent()

	suspend fun translate(text: String, context: List<String>): String? = withContext(Dispatchers.IO) {
		val user = buildString {
			if(context.isNotEmpty())
			{
				append("Önceki satırlar (sadece bağlam, çevirme):\n")
				context.forEach { append("- ").append(it).append('\n') }
				append('\n')
			}
			append("Çevrilecek satır:\n").append(text)
		}
		val body = JSONObject()
			.put("model", model)
			.put("max_tokens", 256)
			.put("temperature", 0.2)
			.put("system", system)
			.put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))

		val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
			requestMethod = "POST"
			connectTimeout = 2500
			readTimeout = 5000
			doOutput = true
			setRequestProperty("content-type", "application/json")
			setRequestProperty("x-api-key", apiKey)
			setRequestProperty("anthropic-version", "2023-06-01")
		}
		try
		{
			conn.outputStream.use { it.write(body.toString().toByteArray()) }
			if(conn.responseCode !in 200..299)
				return@withContext null
			val resp = JSONObject(conn.inputStream.bufferedReader().readText())
			val content = resp.optJSONArray("content") ?: return@withContext null
			val sb = StringBuilder()
			for(i in 0 until content.length())
			{
				val block = content.getJSONObject(i)
				if(block.optString("type") == "text")
					sb.append(block.optString("text"))
			}
			sb.toString().trim().ifEmpty { null }
		}
		catch(e: Exception)
		{
			null
		}
		finally
		{
			conn.disconnect()
		}
	}
}
