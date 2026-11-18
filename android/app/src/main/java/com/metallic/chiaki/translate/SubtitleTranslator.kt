package com.metallic.chiaki.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.LruCache
import android.util.TypedValue
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Remote Play görüntüsünden altyazı bandını yakalar, OCR ile okur ve Türkçeye çevirir.
 *
 * Akış (her ~120 ms):
 *  1. PixelCopy ile sadece altyazı bandı küçük bir bitmap'e kopyalanır (tüm kare değil).
 *  2. Parlak (beyaz) piksel imzası çıkarılır; altyazı değişmediyse OCR atlanır.
 *  3. Değiştiyse ML Kit OCR (cihaz içi) çalışır, ortalanmış altyazı satırları süzülür.
 *  4. Cihaz içi ML Kit çevirisi anında gösterilir; API anahtarı varsa Claude çevirisi
 *     gelince daha doğal Türkçe metinle değiştirilir. Tüm sonuçlar önbelleğe alınır.
 */
class SubtitleTranslator(
	private val context: Context,
	private val surfaceView: SurfaceView,
	private val overlay: TextView,
	private val scope: CoroutineScope
)
{
	companion object
	{
		private const val TAG = "SubtitleTranslator"
		private const val TICK_MS = 120L
		private const val MAX_CAPTURE_WIDTH = 1280
		private const val GRID_W = 64
		private const val GRID_H = 16
		private const val EMPTY_TICKS_TO_HIDE = 3
	}

	private val prefs = TranslationPrefs(context)

	private var recognizer: TextRecognizer? = null
	private var mlTranslator: Translator? = null
	@Volatile private var mlReady = false
	private var llm: LlmTranslator? = null

	private val copyThread = HandlerThread("subtitle-capture").also { it.start() }
	private val copyHandler = Handler(copyThread.looper)

	private var loopJob: Job? = null
	private var bitmap: Bitmap? = null
	private var pixels = IntArray(0)

	private var lastSignature: IntArray? = null
	private var emptyTicks = 0
	private var currentKey: String? = null
	private val history = ArrayDeque<String>()

	/** normalize edilmiş kaynak -> (çeviri, llm mi?) */
	private val cache = LruCache<String, Pair<String, Boolean>>(500)

	fun start()
	{
		if(!prefs.enabled || loopJob != null)
			return

		applyOverlayStyle()

		recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

		val translator = Translation.getClient(
			TranslatorOptions.Builder()
				.setSourceLanguage(TranslateLanguage.ENGLISH)
				.setTargetLanguage(TranslateLanguage.TURKISH)
				.build())
		mlTranslator = translator
		translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
			.addOnSuccessListener { mlReady = true }
			.addOnFailureListener {
				Log.e(TAG, "Çeviri modeli indirilemedi", it)
				Toast.makeText(context, "Cihaz içi çeviri modeli indirilemedi (internet?)", Toast.LENGTH_LONG).show()
			}

		prefs.apiKey.takeIf { it.isNotEmpty() }?.let { llm = LlmTranslator(it, prefs.model) }

		loopJob = scope.launch(Dispatchers.Main.immediate) {
			while(isActive)
			{
				val started = System.currentTimeMillis()
				try { tick() } catch(e: CancellationException) { throw e } catch(e: Exception) { Log.w(TAG, "tick", e) }
				delay(max(0L, TICK_MS - (System.currentTimeMillis() - started)))
			}
		}
	}

	fun stop()
	{
		loopJob?.cancel()
		loopJob = null
		recognizer?.close()
		recognizer = null
		mlTranslator?.close()
		mlTranslator = null
		copyThread.quitSafely()
		overlay.visibility = View.GONE
	}

	private fun applyOverlayStyle()
	{
		overlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.textSizeSp.toFloat())
		overlay.setBackgroundColor(if(prefs.hideOriginal) Color.argb(240, 0, 0, 0) else Color.argb(150, 0, 0, 0))
	}

	private suspend fun tick()
	{
		val holder = surfaceView.holder
		val surface = holder.surface
		if(surface == null || !surface.isValid)
			return
		val frame = holder.surfaceFrame
		val fw = frame.width()
		val fh = frame.height()
		if(fw <= 0 || fh <= 0)
			return

		val src = Rect(
			(fw * 0.08f).toInt(),
			(fh * prefs.regionTopPercent / 100f).toInt(),
			(fw * 0.92f).toInt(),
			(fh * 0.98f).toInt())
		val scale = min(1f, MAX_CAPTURE_WIDTH.toFloat() / src.width())
		val bw = max(1, (src.width() * scale).toInt())
		val bh = max(1, (src.height() * scale).toInt())

		val bmp: Bitmap = bitmap?.takeIf { it.width == bw && it.height == bh }
			?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
				bitmap?.recycle()
				bitmap = it
				pixels = IntArray(bw * bh)
				lastSignature = null
			}

		if(!capture(src, bmp))
			return

		// 1) Parlak piksel imzası (altyazılar beyaz)
		val signature = withContext(Dispatchers.Default) { signature(bmp) }
		val brightTotal = signature.sum()
		if(brightTotal < bw * bh * 0.0015f)
		{
			lastSignature = signature
			onNoSubtitle()
			return
		}
		val prev = lastSignature
		if(prev != null && signatureDiff(prev, signature, brightTotal) < 0.12f)
		{
			emptyTicks = 0
			return // aynı altyazı hâlâ ekranda
		}
		lastSignature = signature

		// 2) OCR
		val rec = recognizer ?: return
		val result = rec.process(InputImage.fromBitmap(bmp, 0)).awaitTask()
		val text = extractSubtitle(result, bw, fh * scale)
		if(text == null)
		{
			onNoSubtitle()
			return
		}
		emptyTicks = 0

		val key = normalize(text)
		val cur = currentKey
		if(cur != null && similarity(cur, key) >= 0.85f)
			return // OCR gürültüsü, aynı satır
		currentKey = key

		// 3) Çeviri
		cache.get(key)?.let { (tr, isLlm) ->
			show(tr)
			if(isLlm || llm == null)
			{
				pushHistory(text)
				return
			}
		}

		val context = history.toList()
		pushHistory(text)

		llm?.let { client ->
			scope.launch {
				val tr = client.translate(text, context) ?: return@launch
				cache.put(key, tr to true)
				if(currentKey == key)
					show(tr)
			}
		}

		if(cache.get(key) == null)
		{
			val ml = mlTranslator
			if(ml != null && mlReady)
			{
				try
				{
					val tr = ml.translate(text).awaitTask()
					if(cache.get(key) == null) // LLM daha önce gelmediyse
					{
						cache.put(key, tr to false)
						if(currentKey == key)
							show(tr)
					}
				}
				catch(e: Exception) { Log.w(TAG, "mlkit translate", e) }
			}
		}
	}

	private fun pushHistory(text: String)
	{
		history.addLast(text)
		while(history.size > 4)
			history.removeFirst()
	}

	private fun onNoSubtitle()
	{
		emptyTicks++
		if(emptyTicks >= EMPTY_TICKS_TO_HIDE && overlay.visibility == View.VISIBLE)
		{
			overlay.visibility = View.GONE
			currentKey = null
		}
	}

	private fun show(text: String)
	{
		overlay.text = text
		overlay.visibility = View.VISIBLE
	}

	private suspend fun capture(src: Rect, dst: Bitmap): Boolean = suspendCancellableCoroutine { cont ->
		try
		{
			PixelCopy.request(surfaceView, src, dst, { res ->
				if(cont.isActive) cont.resume(res == PixelCopy.SUCCESS)
			}, copyHandler)
		}
		catch(e: Exception)
		{
			if(cont.isActive) cont.resume(false)
		}
	}

	private fun signature(bmp: Bitmap): IntArray
	{
		val w = bmp.width
		val h = bmp.height
		bmp.getPixels(pixels, 0, w, 0, 0, w, h)
		val sig = IntArray(GRID_W * GRID_H)
		for(y in 0 until h)
		{
			val gy = y * GRID_H / h
			val row = y * w
			for(x in 0 until w)
			{
				val c = pixels[row + x]
				val r = (c shr 16) and 0xff
				val g = (c shr 8) and 0xff
				val b = c and 0xff
				if(r > 210 && g > 210 && b > 210)
					sig[gy * GRID_W + x * GRID_W / w]++
			}
		}
		return sig
	}

	private fun signatureDiff(a: IntArray, b: IntArray, total: Int): Float
	{
		var d = 0
		for(i in a.indices) d += abs(a[i] - b[i])
		return d.toFloat() / max(1, max(total, a.sum()))
	}

	/** Ortalanmış, yeterince büyük satırları altyazı olarak kabul eder. */
	private fun extractSubtitle(result: com.google.mlkit.vision.text.Text, bw: Int, frameHeightScaled: Float): String?
	{
		val lines = result.textBlocks
			.flatMap { it.lines }
			.filter { line ->
				val box = line.boundingBox ?: return@filter false
				val cx = box.exactCenterX() / bw
				val centered = cx in 0.28f..0.72f
				val bigEnough = box.height() / frameHeightScaled >= 0.016f
				val letters = line.text.count { it.isLetter() }
				centered && bigEnough && letters >= 2
			}
			.sortedBy { it.boundingBox?.top ?: 0 }
		if(lines.isEmpty())
			return null
		val text = lines.joinToString(" ") { it.text }
			.replace(Regex("[|_~]+"), " ")
			.replace(Regex("\\s+"), " ")
			.trim()
		return text.takeIf { it.count { c -> c.isLetter() } >= 3 }
	}

	private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.replace(Regex("\\s+"), " ").trim()

	private fun similarity(a: String, b: String): Float
	{
		if(a == b) return 1f
		val n = a.length
		val m = b.length
		if(n == 0 || m == 0) return 0f
		var prev = IntArray(m + 1) { it }
		var curr = IntArray(m + 1)
		for(i in 1..n)
		{
			curr[0] = i
			for(j in 1..m)
			{
				val cost = if(a[i - 1] == b[j - 1]) 0 else 1
				curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
			}
			val t = prev; prev = curr; curr = t
		}
		return 1f - prev[m].toFloat() / max(n, m)
	}
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
	addOnSuccessListener { if(cont.isActive) cont.resume(it) }
	addOnFailureListener { if(cont.isActive) cont.resumeWithException(it) }
	addOnCanceledListener { cont.cancel() }
}
