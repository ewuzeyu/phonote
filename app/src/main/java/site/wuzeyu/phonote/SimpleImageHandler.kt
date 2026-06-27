package site.wuzeyu.phonote

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import com.fluid.afm.func.Callback
import com.fluid.afm.handler.ImageHandler
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class SimpleImageHandler : ImageHandler {
    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun loadImage(context: Context, url: String, callback: Callback<Drawable>) {
        executor.execute {
            try {
                val drawable = downloadImage(url)
                if (drawable != null) mainHandler.post { callback.onSuccess(drawable) }
                else mainHandler.post { callback.onFail() }
            } catch (e: Exception) {
                mainHandler.post { callback.onFail() }
            }
        }
    }

    override fun loadImage(context: Context, url: String, width: Int, height: Int, callback: Callback<Drawable>) {
        loadImage(context, url, callback)
    }

    override fun loadImageSync(context: Context, url: String): Drawable? = downloadImage(url)

    override fun loadImageSync(context: Context, raw: String, width: Int, height: Int): Drawable? = downloadImage(raw)

    private fun downloadImage(url: String): Drawable? {
        var connection: HttpURLConnection? = null
        try {
            val u = URL(url)
            connection = u.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"
            connection.doInput = true
            connection.connect()
            val inputStream: InputStream = connection.inputStream
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            return if (bitmap != null) BitmapDrawable(null, bitmap) else null
        } catch (e: Exception) {
            return null
        } finally {
            connection?.disconnect()
        }
    }
}
