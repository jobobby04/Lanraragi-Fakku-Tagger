package ca.gosyer.link

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

class RateLimitInterceptor(
    private val permits: Int,
    period: Long,
    unit: TimeUnit,
) : Interceptor {

    private val requestQueue = ArrayList<Long>(permits)
    private val rateLimitMillis = unit.toMillis(period)

    override fun intercept(chain: Interceptor.Chain): Response {
        synchronized(requestQueue) {
            val now = System.currentTimeMillis()
            val waitTime = if (requestQueue.size < permits) {
                0
            } else {
                val oldestReq = requestQueue[0]
                val newestReq = requestQueue[permits - 1]

                if (newestReq - oldestReq > rateLimitMillis) {
                    0
                } else {
                    oldestReq + rateLimitMillis - now // Remaining time
                }
            }

            if (requestQueue.size == permits) {
                requestQueue.removeAt(0)
            }
            if (waitTime > 0) {
                requestQueue.add(now + waitTime)
                Thread.sleep(waitTime) // Sleep inside synchronized to pause queued requests
            } else {
                requestQueue.add(now)
            }
        }

        return chain.proceed(chain.request())
    }
}